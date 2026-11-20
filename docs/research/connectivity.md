# Connectivity without a VPN: relay options

Research for replacing the "private VPN between phone and computer" plan with
ordinary outbound HTTPS from both sides to a relay.

- **Researched:** 2026-11-20. Every source in [Sources](#sources) was fetched
  on that date; that is the access date for every number in this document.
- **Status:** research; **narrowed by the owner on 2026-11-20** (see the
  next section). The owner chose the Realtime Database mailbox; first test
  results are in section 8.

## 0. Final direction (owner, 2026-11-20)

After reading this research the owner decided:

- The VPN is dropped, not kept as a fallback. The relay is the only
  transport.
- **The relay runs on Google Cloud**, in the owner's existing account. Only
  two families remain candidates: **a database mailbox in a Firebase
  project** (sections B2-a and B2-b; a Firebase project is a Google Cloud
  project) and **the project's own relay on the free-tier VM** (B1).
- **Push goes through FCM**, works while the app is closed, and carries no
  readable content (section 3.4 and `docs/security.md`, section 5.1).

Everything else in section 4 (a rented server elsewhere, Cloud Run, other
serverless platforms, managed brokers, chat bots) is **not pursued** and is
kept only as a record of why.

| Candidate | Monthly cost | Billing account | Listener from the managed network | Owner effort |
|---|---|---|---|---|
| Firestore mailbox (B2-a) | USD 0 within the free quota | not needed | **Uncertain:** the realtime listener exists only over gRPC; the fallback is polling that uses most of the free daily reads (one query every 2 s = 43,200 of 50,000) | Low: one Firebase project |
| Realtime Database mailbox (B2-b) | USD 0 on the no-cost plan | not needed | **Best documented fit:** streaming over ordinary HTTPS, with plain HTTPS polling that is not charged per read | Low: the same Firebase project |
| Own relay on the free-tier VM (B1) | up to about USD 3.65 for the IPv4 address (exemption not verified) plus a domain name | **required** | Good in principle (streaming, long-polling under our control); a new, unknown domain may be filtered | High: billing, VM, domain, keeping a public server patched |

The owner named Firestore's realtime listeners as the mailbox candidate.
This research found that the sibling database in the same Firebase project,
the Realtime Database, fits the managed network better, so the decision
issue offers both, clearly separated, next to the VM. Nothing has been
tested on the managed network yet; whichever is chosen is tested first
(section 7).
- **Reading the numbers:** a number followed by a key in brackets, such as
  [gcp-free], was read on the official page listed under that key. Anything
  marked **not verified** could not be confirmed on an official page and must
  not be relied on. Prices scraped from Google Cloud pricing pages are those of
  the default region tab (Iowa, `us-central1`) in USD; other regions differ.

## 1. Summary

- Model: bridge and phone make only outbound HTTPS on port 443 to a relay that forwards end-to-end-encrypted blobs; the bridge never listens.
- (Before the owner narrowed the options; see section 0.) **First choice: Firebase Realtime Database as a "mailbox" plus FCM for push (variant B2-b), USD 0 per month** on the no-cost plan with no billing account; nothing to patch.
- Why: its listener is SSE over ordinary HTTPS with a plain HTTPS polling fallback, and the same Firebase project is needed for push anyway.
- **Fallback: the project's own small relay (A / B1)** with WebSocket, SSE and long-polling; on the Google free-tier VM budget **up to about USD 3.65 per month** for the external IPv4 address (exemption not verified), plus a domain.
- Cloud Run (B3) does not fit a permanently connected listener (about USD 44–60 per month); Firestore (B2-a) is weaker than Realtime Database (gRPC-only listener, TTL neither free nor prompt).
- Push is independent of the relay: in every option the bridge calls the FCM HTTPS API itself.
- The recommendation is conditional on the tests in section 7 on the managed network.

## 2. The problem and the requirements

The computer that runs the bridge is usually on a managed network that blocks
VPNs. A security appliance on that network inspects TLS; the VPN client stays
offline. The owner will not circumvent the block, and the system has to work
on that network. So the design changes to:

- No VPN, no tunnel, no proxy, nothing designed to evade filtering. Tunnelling
  products are out of scope and are not evaluated here.
- The bridge and the phone each make ordinary HTTPS (or WebSocket-over-HTTPS)
  connections to a relay on port 443. The bridge accepts no inbound
  connections and keeps binding to localhost.
- Everything is end-to-end encrypted between phone and bridge. The relay is
  treated as hostile: it forwards opaque ciphertext and cannot read or forge
  messages. That layer is designed in `docs/security.md`; this document only
  checks that each option can carry opaque blobs (a few KB up to about 1 MB,
  binary or base64) without needing to read them.

What TLS inspection on the managed network means, stated plainly:

1. Connections from the bridge may be decrypted and inspected by the
   appliance. This is why the relay credentials are not enough and the content
   needs its own end-to-end encryption.
2. Certificate pinning **from the bridge side would fail** on that network,
   because the bridge sees the appliance's certificate, not the provider's. No
   option may depend on pinning at the bridge. (The phone may pin when it is
   on mobile data, but must not require it either.)
3. Long-lived connections (WebSocket, SSE, gRPC streams) may be cut, buffered
   or refused. Every option is therefore rated on whether it can fall back to
   **plain HTTPS request/response polling**.

Requirements used for the evaluation:

| # | Requirement |
|---|---|
| R1 | Bridge outbound only; works with 443/HTTPS only, possible TLS inspection, idle timeouts |
| R2 | Free or a few USD per month for one user; known growth factors |
| R3 | Bridge receives a phone prompt within about 1–2 s; answer chunks stream back |
| R4 | Push to the phone later (app in background / Doze) |
| R5 | Low operational burden: accounts, patching, stored credentials, free-tier changes |
| R6 | Privacy against the relay operator: metadata, retention, short TTL / delete on delivery |
| R7 | Abuse resistance of the relay endpoint: client authentication, rate limits, cost exposure |

## 3. Common architecture for all options

### 3.1 Message flow

```
 Phone app                    Relay (hostile, sees ciphertext only)             Bridge
 ---------                    -------------------------------------             ------
                                                                    (1) outbound "listen"
                                   <============ stream or (long-)poll ============  |
 (2) request
  E2E-encrypt(prompt) -- HTTPS PUT/POST -->  mailbox  to-bridge  -- delivered on --> decrypt, run
                                                                    the open listen   Claude Code
 (3) streamed answer
  decrypt, render  <-- stream or poll ----  mailbox  to-phone   <-- HTTPS PUT/POST -- E2E-encrypt
                                             (chunk 1..n, final marker)                (chunk i)
 (4) acknowledgement
  ack(ids) ------------------------------->  delete acked blobs  <------------------- ack(ids)

 (5) push, later (app in background / Doze); independent of the relay
  FCM client  <== Google push channel ==  FCM service  <-- HTTPS POST (FCM HTTP v1) -- bridge
  wakes app, app fetches the mailbox as in (3); the push carries no readable content
```

### 3.2 What any relay must do

- **Forward opaque blobs between two paired endpoints** (one phone, one
  bridge), in order, per direction. Blob size up to about 1 MB; larger content
  is chunked by the end-to-end layer.
- **Short retention.** Keep a blob only until the receiver acknowledges it,
  with an upper bound (minutes to hours) after which it is deleted anyway.
- **Authenticate clients to the relay**, separately from the end-to-end keys:
  only the two paired endpoints may read or write the mailbox, each only in
  its own direction. A leaked relay credential must give an attacker at most
  denial of service and metadata, never content.
- **Rate-limit and bound storage**, so that somebody who finds the endpoint
  cannot create cost or fill the mailbox.
- **Carry nothing in clear** beyond what routing needs: a mailbox id, a
  direction, a sequence number, a size.

Every message carries a unique id and a per-direction sequence number, so
that reconnecting with "give me everything after N" is safe and duplicates
are dropped. That makes all transports below interchangeable.

### 3.3 Transport ladder and long-poll fallback

The listener on each side tries, in this order, and steps down when a
transport fails or is cut repeatedly:

1. **Streaming** — WebSocket, SSE or a provider's realtime stream, with a
   heartbeat every 20–30 s so idle timeouts do not silently kill it.
2. **Long-polling** — `GET /messages?after=N&wait=25`: the relay holds the
   request until a message arrives or the wait expires, the client
   immediately asks again. Looks like ordinary HTTPS; latency stays below a
   second. Only possible where we control the relay code (A, B1, C) or the
   provider offers it (E, some of D).
3. **Short polling** — a plain `GET` every 1–2 s while active. Works
   wherever HTTPS works at all; costs requests. This is the floor for the
   database-mailbox options (B2).

Answer streaming uses the same ladder in the other direction; the bridge
batches text into chunks (for example every 300–500 ms) so that one answer is
tens of messages, not thousands.

### 3.4 Push is the same in every option

FCM's send API is a plain HTTPS call,
`POST https://fcm.googleapis.com/v1/projects/<project>/messages:send`,
authorised with a short-lived OAuth 2.0 access token derived from a service
account [fcm-send]. The bridge can therefore send the push itself, outbound,
in every option; the relay never needs FCM credentials.

- Payload limit: 4096 bytes for data and notification messages [fcm-types],
  so the push carries only a small encrypted "wake up and fetch" hint.
- Normal-priority messages may be delayed while the device is in Doze; high
  priority asks FCM to deliver immediately and wake the device [fcm-prio].
- It implies: a Firebase project; a phone with Google's services ("devices
  running Android 7.0 or higher that also have the Google Play Store app
  installed") [fcm-android]; a service-account key on the bridge that should
  be limited to sending messages (exact IAM role name: **not verified**);
  Google learns when a push is sent and to which device token.
- FCM cannot be the transport *to* the bridge: the documented receivers are
  the client SDKs (Android, Apple, web, and so on); an official server-side
  or Linux receive API was **not found** in the documentation read for this
  research. FCM is push-to-phone only.

The Firebase client configuration file and project identifiers identify the
owner's project and must stay out of this public repository; they belong in
the private config folder and are injected at build time.

## 4. Options

### A. Small self-hosted relay on a cheap VPS

**How it works.** A tiny program (a few hundred lines) on a rented VM with a
public address and a normal TLS certificate. It implements section 3
directly: WebSocket and SSE for streaming, long-poll as fallback, in-memory
mailboxes with delete-on-ack, a static secret per endpoint (sent as a bearer
token over TLS) plus per-IP and per-mailbox rate limits.

**Long-lived listener:** yes. The only option family where all three rungs of
the ladder, including true long-polling, are under the project's control.

**Limits and cost.** Whatever the VM allows; the workload is negligible.
Hetzner's cloud landing page lists shared-vCPU cloud servers
"starting from" EUR 5.49–5.99 per month excluding VAT [vps]; the exact plan,
included traffic and IPv4 terms were **not verified**. A domain name for the
TLS certificate is an additional yearly cost (**not verified**). Cost grows
only with a bigger plan; traffic for one user is far below any included
allowance.

**What the owner must do.** Create an account with a hosting provider and pay
for it; register a domain; then keep a public server healthy: OS security
updates (automatable), TLS renewal (automatable), firewall, backups of
nothing (the relay is stateless). Credentials: one relay secret on the phone,
one on the bridge, SSH access for administration.

**What the provider sees.** The hosting provider can see connection metadata
(source addresses, timing, volume) and could read the VM's memory; the
content is ciphertext. Retention is whatever the relay program does: with
memory-only mailboxes nothing is written to disk.

**Risks.** A public server owned by the project is a thing to attack and to
forget to patch. A personal domain or a bare VM address may be classed as
"unknown" by the managed network's filter and blocked, where a large
provider's endpoint would not be (must be tested, section 7). A flat-priced
VM has no cost exposure beyond its price.

### B1. Google Cloud free-tier `e2-micro` VM running the same relay

**How it works.** Exactly option A, hosted on the Compute Engine free tier.

**Long-lived listener:** yes (same as A).

**Limits (free tier) [gcp-free].**

- "1 non-preemptible `e2-micro` VM instance per month" in one of three US
  regions: Oregon (`us-west1`), Iowa (`us-central1`), South Carolina
  (`us-east1`). The allowance is counted in hours, equal to the hours in the
  month, combined across those regions.
- "30 GB-months standard persistent disk."
- "1 GB of outbound data transfer from North America to all region
  destinations (excluding China and Australia) per month."
- The free tier "has no end date", but Google may change it with 30 days'
  notice. Usage beyond the limits is billed on a paid billing account.
- `e2-micro` is a shared-core machine: 2 vCPUs, each sustained at 12.5 % of
  CPU time, with short bursts [gce-machines]. Memory size: **not verified**
  on the pages read. Enough for a forwarder; nothing else should run there.

**External IPv4 address — this is not free.** The network pricing page lists
"Static and ephemeral IP addresses in use on standard VM instances" at
**USD 0.005 per hour**, and says of the free tier for these addresses: "This
free usage is limited to one hour per month per account" [gcp-net]. The
free-tier page lists no external-IP allowance for the free VM [gcp-free]. A
relay needs a public address, so budget 730 h × 0.005 = **about USD 3.65 per
month**. The free-tier landing page says only "1 e2-micro instance per
month" and mentions no address [gcp-free-main]. Whether billing in practice
offsets the charge for the free-tier instance was **not verified**; plan for
it as an upper bound. External IPv6 addresses assigned
to VM instances are not charged [gcp-net], but an IPv6-only relay would be
unreachable from IPv4-only networks, so that is not a way around it.

**Egress beyond the free 1 GB.** On the Standard network tier the pricing
page shows "First 200 GiB per month free (per account, across all regions)"
and USD 0.085 per GiB after that, and notes that "Always Free usage limits do
not apply to Standard Tier" [gcp-net]. Premium-tier per-GiB prices by
destination: **not verified** in detail. One user's ciphertext traffic is
expected to stay well under 1 GB per month.

**Monthly cost.** Up to about USD 3.65 (IPv4; exemption for the free-tier VM
not verified) plus a domain name. Growth factors:
a second VM or a larger machine type, a snapshot schedule, premium-tier
egress above 1 GB, load balancers (not needed).

**Uptime.** A single VM in a single zone: host maintenance and reboots cause
short outages; there is no failover. Both sides must reconnect and resume
from the last sequence number (they do so anyway).

**What the owner must do.** Enable billing on the existing cloud account (a
payment method is required for a VM), create the VM, register a domain, and
accept the same maintenance duty as in A. Set a budget alert — and note that
a budget alert "doesn't automatically cap" spending [gcp-budget], and that
the newer spend-cap budgets cover only a short list of services that does not
include Compute Engine; persistent resources keep running and accruing
charges [gcp-spendcap].

**What the provider sees.** As in A, with Google as the hosting provider.

**Risks.** As in A, plus: the "free" label hides the address charge; US-only
regions add intercontinental round-trip time (fine for chat, to be measured);
free-tier terms can change with 30 days' notice.

### B2. Serverless mailbox in a Google database, plus FCM

Phone and bridge each write encrypted messages into a database and listen for
the other side's writes. No relay code runs anywhere; access is controlled by
security rules. Two databases qualify. They differ in exactly the property
that matters on the managed network, so they are treated separately.

#### B2-a. Firestore

**Free quota [fs-quota] [fs-pricing] [gcp-free].** 1 GiB stored; 50,000
document reads, 20,000 writes and 20,000 deletes per day; 10 GiB outbound
data transfer per month. Daily quotas reset at midnight Pacific time. The
free quota applies to exactly one database per project.

**Limits [fs-quota].** Maximum document size 1 MiB (1,048,576 bytes);
maximum API request size 10 MiB. A 1 MB blob in base64 does not fit in one
document; the end-to-end layer must chunk at roughly 700 KB of raw data.

**Listener billing [fs-pricing].** A listener is charged one read each time a
document in the result set is added or updated; a deleted document is not
charged as a read. After a disconnect, the mobile SDK is charged as for a new
query (immediately without offline persistence, after 30 minutes with it).
Every query costs at least one read even when it returns nothing.

**Prices beyond the free quota (default region tab) [fs-gcp].** USD 0.03 per
100,000 reads, USD 0.09 per 100,000 writes, USD 0.01 per 100,000 deletes.
Cheap, but paying requires a billing account on the project.

**TTL policies [fs-ttl] [fs-pricing].** One TTL field per collection group.
"Data is typically deleted within 24 hours after its expiration date" — TTL
is a clean-up tool, not a retention guarantee. TTL deletes are listed among
the features that "don't include free usage. You must enable billing".
**Short retention therefore has to be delete-on-acknowledgement by the
receiver**, with TTL at most as a paid safety net.

**Authentication.**

- Phone: Firebase Authentication (anonymous accounts are "temporary anonymous
  accounts ... to work with data protected by security rules" [fb-anon]; a
  custom-token sign-in is the alternative, limit 45,000 sign-ins per minute
  per project [fb-auth-limits]). Security rules then allow only the paired
  user ids to read and write the mailbox paths.
- Bridge: the documented way is the server client library with a service
  account. "The server client libraries bypass all Cloud Firestore Security
  Rules and instead authenticate through Google Application Default
  Credentials"; access is then governed by IAM [fs-rules]. The narrowest
  predefined role seen is `roles/datastore.user`, which grants read/write on
  **all** documents of the database [fs-iam]. So the bridge would hold a
  service-account key file with database-wide access. Using the Python server
  library as an ordinary Firebase Auth user, so that security rules apply to
  the bridge too: **not verified**.

**Python realtime listener.** The Python server library supports
`on_snapshot` on documents and queries [fs-listen]. The underlying `Listen`
call "is only available via gRPC or WebChannel (not REST)" [fs-rpc].
Consequences for the managed network:

- The bridge's listener is a long-lived gRPC stream over HTTP/2 on port 443.
  Whether that survives the inspection appliance is unknown and must be
  tested (section 7). gRPC's Python library takes its trusted roots from "a
  default location chosen by gRPC runtime" unless told otherwise [grpc-py];
  whether that is the system trust store was **not verified**, so the
  listener may fail under TLS inspection even where ordinary HTTPS clients
  work.
- **There is no REST streaming or long-poll fallback.** The fallback is
  short polling with REST queries: one query every 2 s is 43,200 reads per
  day at the minimum charge of one read per query — most of the 50,000 free
  reads; every 1 s is 86,400 and exceeds the quota.
- Streaming an answer costs one write per chunk; with 20,000 free writes per
  day, chunk batching is mandatory (100 answers of 150 chunks each is 15,000
  writes).

**Long-lived listener:** yes with caveats (gRPC only; polling fallback is
quota-bound).

#### B2-b. Firebase Realtime Database (recommended variant)

**How it works.** The mailbox is a small JSON tree
(`/mailbox/<pairing>/to-bridge/<seq>`, `/to-phone/<seq>`). The phone uses the
Android SDK. The bridge needs no SDK and no gRPC:

- **Streaming over plain HTTPS.** "Firebase REST endpoints support the
  EventSource / Server-Sent Events protocol": the client sets
  `Accept: text/event-stream`, follows redirects (status 307), and receives
  `put`, `patch`, `keep-alive`, `cancel` and `auth_revoked` events
  [rtdb-rest]. The official Python admin library's `listen()` is built on
  the same REST streaming (marked experimental there) [rtdb-py].
- **Fallback over plain HTTPS.** The same REST API reads and writes any path
  with ordinary `GET`/`PUT`/`POST`/`DELETE` requests, so short polling works
  wherever HTTPS works, and it is not metered per read (see below).

**Limits.** No-cost plan: 100 simultaneous connections, 1 GB stored, 10 GB
downloaded per month ("no-cost up to 360 MB/day") [fb-pricing]
[rtdb-limits]. Pay-as-you-go beyond that: USD 5 per GB stored, USD 1 per GB
downloaded [fb-pricing]. Maximum string size 10 MB; a single write up to
16 MB from the SDKs and 256 MB over REST; 64 MB per minute written
[rtdb-limits]. A 1 MB base64 blob fits in one value. There is no per-read or
per-write charge, so chunked answer streaming and polling cost only download
volume; the per-poll overhead that counts towards the 360 MB per day must be
measured (section 7).

**Retention.** No built-in TTL was found in the pages read (**not
verified**). Retention is delete-on-acknowledgement by the receiver, plus a
periodic sweep by the bridge for anything older than a bound.

**Authentication.** The REST API accepts either a Google OAuth 2.0 access
token from a service account (full access, bypasses rules) or "the same
Firebase ID tokens used by the client SDKs", in which case the database
rules apply [rtdb-auth]. The bridge can therefore act as an ordinary,
rule-restricted user and hold only that user's refreshable credential instead
of a project-wide key. The calls are documented plain HTTPS: anonymous
sign-in (`accounts:signUp`) or custom-token sign-in
(`accounts:signInWithCustomToken`) on `identitytoolkit.googleapis.com` return
an ID token (sample `expiresIn` 3600 s) and a refresh token, which is
exchanged for new ID tokens at `securetoken.googleapis.com` [fb-auth-rest].
Rules of the form `"$uid": { ".read": "auth !== null && auth.uid === $uid" }`
restrict a path to one user id [rtdb-rules]. The flow was read, not run: an
end-to-end test is the first implementation task. The stream reports `auth_revoked` when the token
expires, so the bridge reconnects with a fresh token.

**Long-lived listener:** yes, with the best fallback story of the serverless
options: SSE over HTTPS, then plain polling over HTTPS.

#### Common to B2-a and B2-b

**Monthly cost.** USD 0 within the quotas above. A Firebase project can stay
on the no-cost plan with "no payment information needed" [fb-plans].

**Cost exposure and abuse.** Budget alerts do not cap spending [gcp-budget],
and spend-cap budgets do not cover these databases [gcp-spendcap]. The only
hard cap is **not attaching a billing account to the project**: when a quota
is exhausted the service stops instead of billing. Abuse then becomes an
outage, never an invoice. The price of that choice: no paid TTL deletes
(Firestore) and no headroom. Security rules must restrict every path to the
paired user ids; unauthenticated access must be denied entirely. Remaining
abuse surface: the project's client identifiers are extractable from the app,
so strangers can create anonymous accounts (which the rules then deny) and
can occupy connections — on the no-cost Realtime Database plan the 100
simultaneous connections are a denial-of-service lever. App attestation as a
mitigation: **not verified**.

**What the owner must do.** Create a Firebase project in the existing Google
account (no billing account attached), enable the database, the sign-in
method and FCM, deploy the security rules that the repository ships, and put
the project's configuration and the bridge's credential into the private
config folder. Nothing to patch.

**What the provider sees.** Google sees both endpoints' IP addresses,
connection times, message sizes and timing, the account ids, and stores the
ciphertext until it is deleted. Provider-side log retention: **not
verified**. Message padding to hide sizes is a matter for the security
design.

**Risks.** Quota or plan changes by the provider; the database is storage,
not a queue, so delete-on-ack must be implemented carefully; latency of
change delivery is normally well under a second but no figure was verified;
B2-a additionally carries the gRPC and polling-quota risks above.

### B3. Cloud Run

**How it works.** The relay from option A, packaged as a container.

**Limits.**

- Free tier with request-based billing: 180,000 vCPU-seconds, 360,000
  GiB-seconds and 2 million requests per month; with instance-based billing:
  240,000 vCPU-seconds and 450,000 GiB-seconds. Aggregated per billing
  account. 1 GB outbound data from North America per month [run-pricing]
  [gcp-free].
- Prices (default region tab): request-based USD 0.000024 per vCPU-second
  and USD 0.0000025 per GiB-second while active, USD 0.40 per million
  requests; an idle minimum instance USD 0.0000025 per vCPU-second and per
  GiB-second; instance-based USD 0.000018 per vCPU-second and USD 0.000002
  per GiB-second [run-pricing].
- Request timeout: default 5 minutes, maximum 60 minutes [run-timeout].
- WebSockets are supported, but "WebSockets streams are HTTP requests, which
  are still subject to the request timeout", so a connection lasts at most
  60 minutes and clients must reconnect. "A Cloud Run instance that has any
  open WebSocket connection is considered active, so CPU is allocated and
  the service is billed as instance-based billing" [run-ws]. A held
  long-poll request is an open request and keeps the instance active in the
  same way.
- With less than 1 vCPU, "maximum concurrency must be set to 1" [run-cpu].
  A relay that pairs two connections in memory needs both on one instance,
  so it needs a full vCPU (and a maximum of one instance).
- Scale to zero: with no open request the instance is removed, and with it
  any in-memory mailbox. A listener that is always connected means the
  instance never scales to zero.

**Does it fit a long-lived listener? No.** A bridge that is always listening
keeps one instance active all month: 2,592,000 seconds in a 30-day month.

| Billing | CPU (1 vCPU) | Memory (512 MiB) | Per month |
|---|---|---|---|
| Request-based | (2,592,000 − 180,000) × 0.000024 = USD 57.89 | (1,296,000 − 360,000) × 0.0000025 = USD 2.34 | about USD 60 |
| Instance-based | (2,592,000 − 240,000) × 0.000018 = USD 42.34 | (1,296,000 − 450,000) × 0.000002 = USD 1.69 | about USD 44 |

The free tier covers about 50 hours of an open connection per month. Letting
the service scale to zero would require the bridge to poll with short
requests and the state to live in a database — at which point the database
alone (B2) does the job.

**What the owner must do.** Enable billing, deploy a container, set a
spend-cap budget (Cloud Run is one of the few services they cover; the cap is
not instantaneous) [gcp-spendcap]. **What the provider sees:** as B1.
**Risk:** cost, for no gain over B1 or B2.

### Other Google services, briefly

- **Pub/Sub.** Pull subscriptions offer a streaming pull (gRPC) and a unary
  Pull that is also available over REST [pubsub-pull], so the bridge could
  poll over plain HTTPS. The first 10 GiB of throughput per month is free,
  then USD 40 per TiB [pubsub-pricing]. Fit for a bridge-side listener: yes
  with caveats. The weak side is the phone: there are no per-user security
  rules, so the app would have to carry a cloud service-account credential,
  and the project needs billing enabled (**not verified** whether the free
  allowance is usable without it). Maximum message size: **not verified**.
  Not better than B2-b for this use.
- **FCM alone.** Push to the phone only; no official receive path for the
  bridge (section 3.4).

### C. Other serverless platforms

**Cloudflare Workers with a Durable Object.** One Durable Object is the
mailbox and holds both WebSockets; the same Worker code can also serve SSE
and long-poll requests, so the whole transport ladder is under the project's
control without a server to patch.

- Workers free plan: 100,000 requests per day, 10 ms CPU per invocation; a
  WebSocket connection counts as one request and messages routed through a
  Worker are not counted as requests. Paid plan: minimum USD 5 per month per
  account, no egress charges [cf-workers].
- Durable Objects are available on the free plan with the SQLite storage
  backend: 100,000 requests per day (incoming WebSocket messages counted at
  20:1), 13,000 GB-s of duration per day, 5 GB storage. Duration is billed
  at 128 MB per object while it is active; objects that are idle and eligible
  for hibernation are not billed, and the hibernation WebSocket API keeps
  clients connected while the object sleeps. On the free plan, exceeding a
  limit makes further operations fail instead of billing [cf-do] [cf-ws].
- Received WebSocket messages up to 32 MiB [cf-do-limits]. Runtime updates
  give existing connections up to 20 minutes to finish, so reconnects are a
  normal event [cf-limits].
- A held long-poll keeps the object awake: 86,400 s × 0.128 GB ≈ 11,060 GB-s
  per day, inside but close to the 13,000 GB-s daily allowance. WebSocket
  with hibernation is far below it.
- Fit for a long-lived listener: yes. Cost: USD 0 on the free plan, USD 5 if
  the paid plan becomes necessary. Owner effort: a new account with another
  provider; a second provider besides the Firebase project needed for push.
  The provider terminates TLS and sees metadata like any relay operator.
  Whether the platform's shared hostnames are reachable from the managed
  network must be tested.

**Fly.io.** A small always-on machine running the relay from option A:
USD 2.19 per month for the smallest size (shared CPU, 256 MB); shared IPv4
and IPv6 at no charge, a dedicated IPv4 USD 2 per month; outbound data
USD 0.02 per GB in North America and Europe [fly-pricing]. No standing free
tier: new accounts get USD 1 of credit for 7 days [fly-trial]. Fit: yes; it
is option A with less server administration and a payment method on file.

### D. Managed message queues and pub/sub services

| Service | What the official page shows | Fit |
|---|---|---|
| EMQX Cloud Serverless (managed MQTT) | Free monthly tier: 1 million session minutes, 1 GB traffic [mqtt-emqx]. Two clients connected all month use about 86,400 session minutes. Message size limit **not verified**. | MQTT over WebSocket on 443 is a long-lived stream with **no polling fallback**; fails R1 if streams are cut. |
| HiveMQ (managed MQTT) | The pricing page read shows a self-managed free tier (25 connections) and paid plans from USD 299 per month; a free fully-managed plan was **not verified** [mqtt-hivemq]. | Same as above. |
| Pusher Channels | Free plan: 200,000 messages per day, 100 concurrent connections [rt-pusher]. Message size limit and fallback transports **not verified**. | Possible; server-side subscribe from Python is not the main use of such services (**not verified**). |
| Ably | Free plan priced by monthly active users (up to 500) [rt-ably]; message and connection limits **not verified**. | As above. |
| AWS IoT Core | Free tier for 12 months after account creation: 2,250,000 connection minutes and 500,000 messages; prices after that are rendered dynamically on the page and were **not verified** [aws-iot]. | MQTT stream; no polling fallback; free tier expires. |
| AWS SQS | 1 million requests per month free for all customers [aws-sqs]. Long-poll wait time and message size limit **not verified**. | Queue with native HTTPS long-polling: a good fit for the bridge side. The phone would need cloud credentials; a new cloud account is required. |
| ntfy (public instance or self-hosted) | HTTP publish; subscribe as JSON stream, SSE, WebSocket, or plain polling with `poll=1` and `since=` [ntfy-sub]. Message body limit 4,096 bytes, larger payloads become attachments; server default cache duration 12 h, can be set to 0 (forward only, store nothing); default request limit a burst of 60 then one per 5 s per visitor [ntfy-config] [ntfy-pub]. | Self-hosted, it is a ready-made implementation of option A with every rung of the ladder, but the 4 KB message size makes it a poor carrier for answers; good for notifications. The public instance adds a third party and its limits. |

All of these see the same metadata as any relay. None removes the need for
FCM for background push to an own app (ntfy has its own Android app and push
path, which is a different client, not the project's app).

### E. A chat-bot channel

**Telegram Bot API.** The bridge runs a bot and calls `getUpdates` with a
`timeout` — documented HTTPS long-polling, no inbound connection [tg-api].
The owner writes to the bot from the regular Telegram app and gets the
platform's push notifications for free.

- Limits: text messages 1–4096 characters [tg-api]; about one message per
  second in a single chat, 20 per minute in a group [tg-faq]. Streaming an
  answer by editing or sending messages runs into these limits quickly.
- Content is visible to the platform: bot chats are not end-to-end
  encrypted, and the stock client cannot add our encryption layer. A custom
  app could send ciphertext through a bot chat, but then the benefit of the
  ready-made client is gone and the platform is only an awkward relay.
  (End-to-end-encrypted chats for bots: **not verified**, none found.)
- It conflicts with a logged decision ("own Android app, no Telegram for
  now"), ties the owner's assistant to a third-party client UI and the
  platform's terms, and the managed network may block the platform (must be
  tested if it is ever considered).

**Matrix.** A homeserver is the relay; clients receive with the `/sync`
long-poll over HTTPS [matrix-spec], and the protocol has end-to-end
encryption that a custom client can use. Push to an own app still needs a
push gateway and FCM. Running a homeserver is far more to maintain than
option A; using a public homeserver adds a third party with its own terms.
Too heavy for one phone and one bridge.

**What the owner must do.** Have an account on the platform and register a
bot (or an account on a homeserver); both are account creations and therefore
steps for the owner, not for the agent.

**Fit for a long-lived listener:** yes (long-polling by design). **Cost:**
USD 0. **Verdict:** useful as an emergency text channel at most; it fails R6
with the stock client and contradicts a logged decision.

## 5. Comparison

| Option | Works on restrictive network | Long-lived listener fit | Monthly cost | Owner effort | Push path | Provider sees | Main risk |
|---|---|---|---|---|---|---|---|
| A. Relay on a VPS | Likely; WS → SSE → long-poll under our control; unknown domain may be filtered | Yes | About EUR 5–6 plus domain (plan details not verified) | High: hosting account, domain, patching a public server | Bridge → FCM | Metadata; ciphertext in memory only | Maintenance of a public server |
| B1. Relay on free-tier VM | As A | Yes | Up to about USD 3.65 (IPv4; exemption not verified) plus domain | High: billing enabled, VM, domain, patching | Bridge → FCM | As A | "Free" VM is not free; no hard spending cap |
| B2-a. Firestore mailbox | Uncertain: listener is gRPC only; fallback is quota-bound polling | Yes, with caveats | USD 0 within quota | Low: Firebase project, rules | Bridge → FCM (same project) | Metadata; ciphertext stored until deleted | gRPC under TLS inspection; 50k reads per day; TTL not free, not prompt |
| B2-b. Realtime Database mailbox | Good: SSE over HTTPS, plain HTTPS polling fallback | Yes | USD 0 within quota | Low: Firebase project, rules | Bridge → FCM (same project) | Metadata; ciphertext stored until deleted | Connection-slot exhaustion on the no-cost plan; plan changes |
| B3. Cloud Run | Yes | **No** (60 min cap, billed while connected) | About USD 44–60 if always connected | Medium | Bridge → FCM | As A | Cost |
| C. Workers + Durable Object | Likely; WS, SSE and long-poll under our control | Yes | USD 0 (USD 5 if paid plan needed) | Medium: new provider account, deploy code | Bridge → FCM | Metadata; ciphertext transient | Second provider; free-plan limits; reachability untested |
| D. Managed MQTT / realtime / queue | Mixed: MQTT has no polling fallback; SQS and ntfy do | Yes (stream) | USD 0 on free tiers; several limits not verified | Medium: new account, vendor SDKs | Bridge → FCM | Metadata; ciphertext | Free-tier changes; phone-side credentials |
| E. Chat bot | Uncertain (platform may be blocked) | Yes (long-poll) | USD 0 | Low | Platform's own push | **Content**, unless a custom client encrypts | Privacy; contradicts a logged decision |

## 6. Recommendation

**First choice: B2-b — Firebase Realtime Database as the mailbox, FCM for
push, on the no-cost plan with no billing account attached. Expected cost:
USD 0 per month.**

Reasons:

- It meets R1 with the least uncertainty among the serverless options: the
  bridge's listener is SSE over ordinary HTTPS, and when streams are cut the
  bridge falls back to plain HTTPS polling against the same endpoint, with no
  per-read charge. No gRPC, no WebSocket required on the bridge side.
- The endpoint is a large provider's well-known service on port 443, which
  is the stated model, rather than a personal server address.
- No server to patch, no domain, no TLS certificates to renew (R5).
- A Firebase project is needed for push anyway (R4), so this adds no second
  provider.
- With no billing account attached, cost exposure is zero by construction
  (R7): abuse can cause an outage, not a bill.
- The bridge can hold a rule-restricted user credential instead of a
  project-wide key.

Known weaknesses, accepted: Google sees metadata and stores ciphertext until
acknowledged (R6: delete-on-ack plus a sweep; no native TTL verified); the
polling fallback is short polling, so latency in fallback mode equals the
poll interval (1–2 s); the no-cost plan's 100-connection limit is a
denial-of-service lever.

**Fallback: the project's own small relay (option A code) on the free-tier
VM (B1). Expected cost: up to about USD 3.65 per month for the IPv4 address
(exemption for the free-tier VM not verified), plus a domain name.** It is the only family with true long-polling and memory-only
retention fully under the project's control, and it does not depend on any
database product. It costs the owner a public server to look after. The same
relay code can be deployed on a Durable Object (C) or another host later.

To keep the switch cheap, the bridge and the app should talk to the relay
through one small interface (send blob, receive blobs after N, acknowledge),
with the end-to-end layer above it. The backend is then a replaceable module.

**What would change the recommendation:**

- HTTPS to the Firebase endpoints fails or is blocked on the managed network
  → the fallback, provided its endpoint is reachable.
- SSE is cut within seconds *and* measured polling overhead does not fit the
  daily download allowance → the fallback (true long-polling).
- The owner does not want message ciphertext stored at rest with a provider,
  even briefly → the fallback (memory-only relay).
- The owner prefers not to maintain any server and the Firebase option fails
  the tests → option C.
- The no-cost plan loses the Realtime Database or its limits shrink → the
  fallback; the interface above makes this a module swap.

**Options originally offered to the owner (four; superseded by section 0, which keeps rows 1, 2 on the free-tier VM only, and 4):**

| # | Option | What it requires from the owner | Monthly cost |
|---|---|---|---|
| 1 | Realtime Database mailbox + FCM (B2-b) — recommended | Create a Firebase project in the existing Google account, no billing attached; enable database, sign-in and FCM; store config locally | USD 0 |
| 2 | Own relay on the free-tier VM (B1; or any VPS, A) — fallback | Enable billing, create the VM, register a domain, accept responsibility for a patched public server; still a Firebase project for push | Up to about USD 3.65 (IPv4 exemption not verified) plus domain (VPS: about EUR 5–6, not verified in detail) |
| 3 | Own relay code on Workers + Durable Object (C) | Create an account with a second provider and deploy; still a Firebase project for push | USD 0 (USD 5 if the paid plan becomes necessary) |
| 4 | Firestore mailbox + FCM (B2-a) | As option 1; to be chosen only if the gRPC test in section 7 passes | USD 0 within quota |

Not offered: Cloud Run (cost), managed brokers (limits not verified, no
polling fallback for MQTT), chat bots (privacy; a logged decision excludes
them for now).

## 7. Open questions — to be tested on the real managed network

These tests use only ordinary HTTPS clients against the candidate endpoints.
Nothing here may disable certificate verification or work around the filter;
a failed test is a result, and it changes the recommendation.

1. **Does plain HTTPS from the bridge host work at all under inspection?**
   That is: does the host already trust the appliance's certificate
   authority, and do the HTTP libraries the bridge uses consult that trust
   store? Many Python HTTP libraries ship their own root list instead of
   using the system's. If trust has to be configured, that is a security
   trade-off for the owner to decide (human issue), not something to assume.
2. **Are the candidate hostnames reachable?** The Realtime Database
   endpoint, the token endpoints the bridge needs, the FCM send endpoint;
   for the fallbacks: a VM address with a fresh domain, and the serverless
   platform's shared hostname.
3. **SSE:** does a `text/event-stream` response arrive event by event, or
   does the appliance buffer it? How long does an idle stream with
   keep-alive events live? Is the 307 redirect followed correctly?
4. **WebSocket:** does the upgrade succeed through the appliance, and how
   long does an idle connection with pings live?
5. **gRPC / HTTP/2:** does a long-lived gRPC stream to the Firestore
   endpoint work, and which root certificates does the gRPC library use
   under inspection? (Decides whether option 4 exists at all.)
6. **Idle timeout and maximum request duration:** the longest long-poll
   wait that completes reliably (try 25 s, 55 s), and whether held requests
   are cut or answered with an error page.
7. **Polling cost:** bytes counted per empty poll against the Realtime
   Database download allowance, with a kept-alive connection, at 1 s and 2 s
   intervals.
8. **Latency:** time from phone write to bridge event, and per-chunk delay
   of a streamed answer, in streaming mode and in polling mode; round-trip
   time to the chosen region.
9. **Token refresh under inspection:** does the bridge's hourly token
   refresh work, and does the stream recover after `auth_revoked`?
10. **Push from the bridge:** does the FCM send call succeed from the
    managed network, and how long does a high-priority data message take to
    reach a phone in Doze?
11. **Still to verify from documentation before implementation:** the narrowest
    IAM role for sending FCM messages; app attestation as protection for the
    connection limit; provider log retention; whether the free-tier VM's
    address charge is offset in practice.

## 8. Test results

`scripts/relay-check` (module `lifeos_bridge.relaycheck`) runs the tests of
section 7 that concern the chosen option, the Realtime Database mailbox,
with ordinary HTTPS from the bridge's own Python (standard library, system
trust store, verification always on).

**Run of 2026-11-20, on the network the computer was on at that time.**
The certificates were the provider's own, not re-signed by an inspecting
appliance. **Whether that network was the managed one is not known to the
test and must be confirmed by the owner;** if it was not, the script has to
be run again there. Until then the result below counts for an ordinary
network only.

| Check | Result |
|---|---|
| Certificate trusted by the system store (database, push service) | yes; TLS 1.3 |
| Access tokens for the service account | obtained in 0.4 s |
| Write and read one value | about 170 ms each |
| One value of about 1 MB | written in about 0.6 s |
| Streaming (SSE): 5 writes, 1 s apart | all 5 arrived one by one, typically about 170 ms after the write (one took 1.8 s) |
| Idle stream, 75 s | stayed open; 2 keep-alive events; a write afterwards was delivered |
| Polling an empty path on a kept-alive connection | about 75 ms and about 280 response bytes per poll: about 12 MB per day at one poll every 2 s, 24 MB at 1 s (allowance: 360 MB per day) |
| Sign-in and token-refresh endpoints | reachable |
| Push service, dry run with a made-up device | reachable and authorised (answered "invalid device", as expected) |

Not covered by this run: behaviour under TLS inspection (questions 1 and 9),
idle lifetimes beyond 75 s (`--idle` takes a longer period), delivery time of
a real push to a phone in Doze (needs the app).

## Sources

All fetched on 2026-11-20. Official documentation and pricing pages only.

| Key | Page |
|---|---|
| [gcp-free] | https://docs.cloud.google.com/free/docs/free-cloud-features |
| [gcp-free-main] | https://cloud.google.com/free |
| [gcp-net] | https://cloud.google.com/vpc/network-pricing |
| [gce-machines] | https://docs.cloud.google.com/compute/docs/general-purpose-machines |
| [gcp-budget] | https://docs.cloud.google.com/billing/docs/how-to/budgets |
| [gcp-spendcap] | https://docs.cloud.google.com/billing/docs/how-to/budgets-spend-caps |
| [fs-quota] | https://firebase.google.com/docs/firestore/quotas |
| [fs-pricing] | https://firebase.google.com/docs/firestore/pricing |
| [fs-gcp] | https://docs.cloud.google.com/firestore/pricing |
| [fs-ttl] | https://firebase.google.com/docs/firestore/ttl |
| [fs-listen] | https://firebase.google.com/docs/firestore/query-data/listen |
| [fs-rpc] | https://docs.cloud.google.com/firestore/native/docs/reference/rpc/google.firestore.v1 |
| [fs-rules] | https://firebase.google.com/docs/firestore/security/get-started |
| [fs-iam] | https://docs.cloud.google.com/firestore/native/docs/security/iam |
| [fb-anon] | https://firebase.google.com/docs/auth/android/anonymous-auth |
| [fb-auth-limits] | https://firebase.google.com/docs/auth/limits |
| [fb-pricing] | https://firebase.google.com/pricing |
| [fb-plans] | https://firebase.google.com/docs/projects/billing/firebase-pricing-plans |
| [rtdb-rest] | https://firebase.google.com/docs/database/rest/retrieve-data |
| [rtdb-auth] | https://firebase.google.com/docs/database/rest/auth |
| [fb-auth-rest] | https://firebase.google.com/docs/reference/rest/auth |
| [rtdb-rules] | https://firebase.google.com/docs/database/security/rules-conditions |
| [rtdb-limits] | https://firebase.google.com/docs/database/usage/limits |
| [rtdb-py] | https://firebase.google.com/docs/reference/admin/python/firebase_admin.db |
| [grpc-py] | https://grpc.github.io/grpc/python/grpc.html |
| [fcm-send] | https://firebase.google.com/docs/cloud-messaging/send/v1-api |
| [fcm-types] | https://firebase.google.com/docs/cloud-messaging/customize-messages/set-message-type |
| [fcm-prio] | https://firebase.google.com/docs/cloud-messaging/android/message-priority |
| [fcm-android] | https://firebase.google.com/docs/cloud-messaging/android/get-started |
| [run-pricing] | https://cloud.google.com/run/pricing |
| [run-timeout] | https://docs.cloud.google.com/run/docs/configuring/request-timeout |
| [run-ws] | https://docs.cloud.google.com/run/docs/triggering/websockets |
| [run-cpu] | https://docs.cloud.google.com/run/docs/configuring/services/cpu |
| [pubsub-pull] | https://docs.cloud.google.com/pubsub/docs/pull |
| [pubsub-pricing] | https://cloud.google.com/pubsub/pricing |
| [cf-workers] | https://developers.cloudflare.com/workers/platform/pricing/ |
| [cf-do] | https://developers.cloudflare.com/durable-objects/platform/pricing/ |
| [cf-do-limits] | https://developers.cloudflare.com/durable-objects/platform/limits/ |
| [cf-ws] | https://developers.cloudflare.com/durable-objects/best-practices/websockets/ |
| [cf-limits] | https://developers.cloudflare.com/workers/platform/limits/ |
| [fly-pricing] | https://fly.io/docs/about/pricing/ |
| [fly-trial] | https://fly.io/docs/about/free-trial/ |
| [vps] | https://www.hetzner.com/cloud/ |
| [mqtt-emqx] | https://www.emqx.com/en/pricing |
| [mqtt-hivemq] | https://www.hivemq.com/pricing/ |
| [rt-pusher] | https://pusher.com/channels/pricing/ |
| [rt-ably] | https://ably.com/pricing |
| [aws-iot] | https://aws.amazon.com/iot-core/pricing/ |
| [aws-sqs] | https://aws.amazon.com/sqs/pricing/ |
| [ntfy-sub] | https://ntfy.sh/docs/subscribe/api/ |
| [ntfy-config] | https://docs.ntfy.sh/config/ |
| [ntfy-pub] | https://docs.ntfy.sh/publish/ |
| [tg-api] | https://core.telegram.org/bots/api |
| [tg-faq] | https://core.telegram.org/bots/faq |
| [matrix-spec] | https://spec.matrix.org/latest/client-server-api/ |
