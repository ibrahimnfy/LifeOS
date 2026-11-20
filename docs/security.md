# Security design and threat model: phone ↔ bridge through a relay

Status: **the bridge side is built** (`e2e.py`, `devices.py`, `mailbox.py`,
`relay.py`: sealing, receiver checks, device store, pairing with the `pair`
command, the Realtime Database mailbox with streaming and polling, the
request dispatcher; with the tests of section 10 and one end-to-end check
through the real mailbox with a simulated phone). Not built yet: the phone
side, push, file transfer. This layer has had no independent security
review. The relay is the only
transport (the earlier VPN plan was dropped, see `decisions.md`). The relay
runs on Google Cloud; which form it takes (see
[`research/connectivity.md`](research/connectivity.md)) does not change this
document, because the relay is never trusted. Push notifications go through
Firebase Cloud Messaging (section 5.1).

Scope: the path between the phone app and the bridge. What a prompt may do
on the computer once it has arrived is a separate layer:
[`permission-policy.md`](permission-policy.md).

## 1. Principles

1. **The relay is hostile.** Assume its operator, or someone who broke into
   it, reads, stores, delays, drops, reorders, repeats and invents messages.
   None of that may reveal content or make the bridge act.
2. **Only paired devices are heard.** The bridge acts on a message only if it
   is authenticated with the key of a device paired at the computer, is new
   (not a repeat) and is recent.
3. **No home-made cryptography.** Only documented constructions of one
   widely used, audited library, used the way its documentation shows. No
   own cipher modes, no own handshake.
4. **Keys never travel over a network.** They are created at the computer and
   reach the phone optically, by QR code.
5. **Outbound only.** The bridge opens no port; it and the phone only make
   ordinary HTTPS requests. Network inspection on the way sees ciphertext.
6. **Fail closed.** A message that fails any check is dropped without an
   answer that would help an attacker; a requirement that cannot be met
   becomes a question for the owner, never a quiet weakening.

## 2. Parties

| Party | Trusted for |
|---|---|
| Bridge (owner's computer) | everything; it is the root of trust and holds the memory |
| Phone app on a paired phone | acting as the owner while unlocked |
| Relay / cloud provider | **nothing** except availability; treated as an attacker |
| Networks in between, including inspecting firewalls | nothing |
| Push service | nothing except delivering a wake-up signal |
| Model provider (Claude Code's API) | outside this document; as in every Claude Code session it sees prompts and the files a run reads |

## 3. Cryptography

Library: **libsodium** (bridge: PyNaCl bindings; phone: a maintained libsodium
binding for Android). Constructions used, all documented by libsodium:

| Purpose | Construction |
|---|---|
| Random keys, ids, nonces | `randombytes` |
| Deriving the working keys from the pairing secret | `crypto_kdf` (BLAKE2b-based key derivation). The Python binding used by the bridge does not expose `crypto_kdf`, so the bridge calls the keyed BLAKE2b function with salt and personalisation (`crypto_generichash_blake2b_salt_personal`) with exactly the inputs `crypto_kdf_derive_from_key` is defined by; the phone calls `crypto_kdf` itself. An interoperability test between the two is part of the app work |
| Encrypting and authenticating every message | `crypto_aead_xchacha20poly1305_ietf` (authenticated encryption with associated data) |
| Short comparison code at pairing | `crypto_generichash` (BLAKE2b) |

### 3.1 Keys

At pairing the computer creates, per device, a 32-byte random **device
secret**. From it both sides derive, with `crypto_kdf` and fixed context
labels:

- `k_p2b`: key for messages phone → bridge;
- `k_b2p`: key for messages bridge → phone;
- `k_push`: key for the small encrypted hint inside push notifications.

Separate keys per direction mean a message can never be reflected back to
its sender as valid. Separate secrets per device mean one phone can be
revoked without touching another.

Storage: on the computer in `~/.config/lifeos/devices/` (mode 600, folder
mode 700; never in the repository, the memory folder or logs). On the phone
the secret is encrypted with a key held in the Android Keystore that
requires the user's biometric or device credential (section 6).

### 3.2 Message format

Each message is one AEAD ciphertext:

```
header, 30 bytes (authenticated as associated data, not secret):
    "LOS" | protocol version (1) | device id (16) | direction (1)
          | counter (8, big endian) | message kind (1)
nonce (24 random bytes)
ciphertext + tag
plaintext (secret), JSON:
    {"ts": unix seconds, "rid": request id, "p": payload}
```

Directions: 1 phone → bridge, 2 bridge → phone, 3 push hint; the direction
number is also the sub-key number, so each direction has its own key.
Message kinds: hello, request, cancel, acknowledgement (phone → bridge);
answer chunk, end, error (bridge → phone); push hint. A message is at most
1.4 MB; longer content is sent in chunks.

The relay sees the associated data it needs for routing (device id,
direction, counter) but cannot change it: any change makes decryption fail.

### 3.3 Checks at the receiver (in this order; first failure drops the message)

1. Known protocol version, known and **not revoked** device id, expected
   direction.
2. Decryption succeeds (this is the authentication: only a holder of the
   device secret can produce it).
3. **Counter is greater** than the last accepted counter for that device and
   direction. Counters are stored durably before the message is acted on, so
   a repeat is rejected even after a restart.
4. **Timestamp is recent** (within a few minutes of the receiver's clock) for
   messages that start work (hello, request; five minutes either way). This
   limits how long a withheld message stays usable.
5. Size and rate limits per device.

Dropped messages are counted in a local log (count and reason only).

### 3.4 What this deliberately does not have

- **Forward secrecy.** The keys are static per pairing. Someone who records
  ciphertext at the relay *and later* obtains a device secret (from the
  phone or the computer) could decrypt the recorded messages. Getting
  forward secrecy needs a key-agreement handshake; doing that without
  home-made protocol work means adopting a complete, audited secure-channel
  protocol implementation on both platforms. That is listed as a possible
  later step (section 9), not silently skipped. Mitigations now: short relay
  retention, device secrets locked behind the Keystore, revoke-and-re-pair
  whenever a phone is lost or sold.

## 4. Pairing

1. On the computer the owner runs `lifeos-bridge pair`. The bridge creates a
   device id and device secret and shows a **QR code in the terminal** with:
   protocol version, device id, device secret, and the relay address and
   relay access data the phone needs. The code is valid for two minutes and
   for one use.
2. The phone app scans it. Nothing has crossed any network so far. The QR
   text is `lifeos-pair:1:` followed by the encoded data.
3. The app sends a first "hello" message, encrypted as in section 3. Both
   screens show a **six-digit comparison code** derived from the secret and
   the hello message; the owner confirms on the computer that they match.
   Only then is the device stored as paired. If a second, different hello
   arrives for the same pairing, two devices hold the code: the pairing is
   destroyed.
4. If no hello arrives in two minutes, or the owner does not confirm, the
   secret is destroyed.

**Pairing is a one-time step per phone** (owner's requirement). After it,
the phone and the bridge reconnect by themselves, across restarts of either
side, network changes and relay outages: the device secret and the counters
are stored durably on both sides, and relay access is renewed
automatically. The phone must be paired again only after a new phone, a
reinstall of the app (the secret is deliberately not in backups) or a
revocation.

The QR code is the key. Whoever photographs it during those two minutes
holds the same secret as the phone. Hence: shown only on the owner's own
screen, short validity, single use, and the confirmation step, which reveals
a second device trying to complete the same pairing. See the open point in
section 9 for a variant that removes this weakness at the price of sending
one encrypted public value through the relay.

## 5. The relay

- Carries opaque blobs between one phone and the bridge; keeps a blob only
  until it is acknowledged, with an upper bound of minutes, then deletes it.
- Has its own, separate access control (who may read or write which mailbox)
  so strangers cannot fill it or run up cost. Here: database rules
  (`config/firebase-database.rules.json`) that give each phone's relay
  account its own mailbox only. A phone gets that access when the bridge,
  after the comparison code was confirmed, records the account id the phone
  sent inside its sealed hello. During the two minutes of pairing the hello
  is written to a place that only somebody who has the QR code can name. Relay access data is **not** an
  end-to-end key: leaking it allows disturbance and shows metadata, nothing
  more.
- Learns unavoidable **metadata**: that the two endpoints talk, when, how
  much, their network addresses, the device id and counters.
- Push notifications contain no readable content (section 5.1).

### 5.1 Push notifications

Push goes through Firebase Cloud Messaging (FCM) and must work while the app
is closed. The bridge calls the FCM HTTPS API itself, outbound; the relay
never holds push credentials.

- **Payload rule.** A push is one of two things, never anything else:
  1. a **bare wake-up**: no fields beyond what FCM itself needs; or
  2. a **small hint encrypted with `k_push`** using the same construction
     and checks as section 3 (its own counter; a repeated or stale hint is
     ignored). The hint says what kind of event happened, its notification
     channel, which screen and item it concerns (a session, reminder or
     course id) and, for a course sync, how many items of each kind changed,
     so the app can show the right notification. The lock-screen text is
     fixed per event kind; counts and references are never shown there.
  In both cases the app then fetches from the relay and decrypts. Titles,
  prompts, answers, notes and paths never appear in a push.
- **FCM "data" messages only**, with high priority where the owner expects
  prompt delivery (a finished run, a reminder), so the app's own code builds
  the visible notification after decrypting. The "notification" message
  type, whose text FCM would display itself, is not used.
- **The phone's push address** (FCM registration token) reaches the bridge
  inside the end-to-end channel, is stored with the device record, and is
  deleted on revocation.
- **Credentials.** The bridge's service-account key and the app's Firebase
  settings file live under `~/.config/lifeos/firebase/` (mode 600 / 700) and
  are injected at build time; neither is ever committed.
- **What the push service learns:** that a push was sent, to which phone,
  when, and its size. It can delay or drop pushes. A forged push is at worst
  a useless wake-up: a hint that does not decrypt is ignored, and whatever
  the app fetches must still pass section 3.3.

## 6. On the devices

**Computer**

- `lifeos-bridge devices` lists paired devices (id, name given at pairing,
  date paired, last seen). `lifeos-bridge devices revoke <id>` deletes the
  device secret; from that moment its messages fail check 1 or 2. Revocation
  needs no network and no cooperation from the phone or relay.
- Pairing and revocation are announced through `scripts/notify.sh` and
  written to the audit log (section 6.1).
- The existing bearer-token interface stays bound to localhost for local
  tools; the relay path does not use that token.

**Phone**

- App lock: opening the app, and using the device secret at all, requires
  biometric or device-credential confirmation (Android BiometricPrompt with a
  Keystore key that demands user authentication). A stolen unlocked-screen
  phone still cannot use the app after the lock timeout.
- The secret is excluded from device backups and cloud backup.
- "Unpair this phone" wipes the secret locally.

### 6.1 The audit log

`~/.config/lifeos/audit/YYYY-MM.jsonl` (folder mode 700, files mode 600, one
JSON object per line, append-only, local only). Every record has `ts` (UTC),
`session_id`, `tool` (the kind of record), `detail` and `decision`. What it
holds:

| `tool` | When | `detail` |
|---|---|---|
| a tool name (`Bash`, `Edit`, …) | every tool call of a capable run, written by the gate **before** the call runs | command or file path, and the category of a refusal |
| `FileOffer` / `FileDownload` | the assistant offers a file, or the phone fetches it | name and size (plus `course_id` for a course file) |
| `CourseFileAddress` | the bridge gives the phone a direct download address of a course file | `course_id`, file name, size |
| `RelayRequest` | every request a paired phone sends through the relay, after it was answered | device id, method, route template (for example `/notes/{note_id}`, or `(refused)` for a path outside the list), status |
| `DevicePaired` / `DeviceRevoked` | a phone is paired or revoked | device id |
| `AllowedPathAdded` / `AllowedPathRemoved` | the list of allowed folders changes | the folder |
| `MoodleSignIn` | a new Moodle token is stored | nothing but the fact |
| `ModelRouting` | the owner changes a job's model, effort or advisor from the app (`PUT /models/routing`) | the job, its choice before and after |

Never recorded: file contents, note or task text, prompts and answers,
request bodies and query values, the raw request path (it may carry ids or
search words), course download addresses, the Moodle token or any other
secret. Records the bridge writes on its own have an empty `session_id` (or
`course-<id>`), so the capable-mode checks that read the log per session
never confuse them with tool calls.

Writing the record is a condition for: offering or sending a file, handing
out a course download address, changing the allowed folders, and changing
the model routing; when the
record cannot be written these answer `503 audit_unavailable` and nothing
happens. Relay requests, pairing, revocation and sign-ins are recorded best
effort: they already happened (a revocation must never be blocked).

## 7. Threat model

What each party can see and do, with this design in place.

| Party / event | Can see | Can do | Cannot do |
|---|---|---|---|
| Relay operator, or attacker inside the relay | Metadata (section 5); ciphertext while stored | Delay, drop, reorder messages; deny service; replay old ciphertext (rejected) | Read prompts or answers; forge a prompt; make the bridge run anything |
| Inspecting firewall on a managed network | That the computer talks HTTPS to the relay; sizes and timing; after TLS inspection the same ciphertext as the relay | Block or cut the connection | Read or forge content |
| Other network attacker | At most what TLS exposes | Block | Read or forge |
| Stranger who finds the relay address | Nothing without relay access data | Attempt to disturb; bounded by rate limits | Read, forge |
| Someone with relay access data but no device secret | Metadata, ciphertext | Disturb, fill the mailbox up to its limits | Read, forge |
| Push service | That a push was sent, to which phone, when, its size | Delay or drop pushes; send useless wake-ups | Read content; make the app show or do anything |
| Someone who steals the bridge's push key (service-account file) | Whatever that Firebase project holds: if the mailbox is in the same project, its ciphertext and metadata | Send useless wake-ups; disturb or empty the mailbox | Read or forge content |
| Thief of a **locked** phone | Nothing | Nothing, while the Keystore protection holds | Use the app |
| Thief of an **unlocked** phone, or malware with the app's rights | Everything the app shows | Act as the owner until revoked: send prompts, within the permission policy | Reach beyond the permission policy; survive `devices revoke` |
| Someone who photographs the pairing QR code in its two minutes | The device secret | Read that device's traffic if they also obtain it from the relay; send prompts as that device | Stay unnoticed if they complete pairing (comparison step); survive revocation |
| Attacker with the owner's user account on the computer | Everything | Everything | — (the computer is the root of trust) |

### Residual risks

1. **Metadata** is visible to the relay provider and the network.
2. **No forward secrecy** (section 3.4).
3. **Availability** is not protected: the relay, the push service or a
   firewall can stop the service. Nothing unsafe happens when they do.
4. **The pairing moment** is the most sensitive step (QR code on screen).
5. **Phone compromise equals owner access** until revocation; what that
   access can do is bounded by the permission policy (read-only by default;
   capable mode with its guardrails; approval-required actions).
6. **Clock dependence:** a wrong clock on the phone makes fresh messages look
   stale; they are refused, not accepted.
7. **Library and platform bugs** (libsodium bindings, Android Keystore) are
   outside what this project can verify; versions are pinned and updated.
8. **Prompt content is still seen by the model provider**, as in any Claude
   Code use. End-to-end here means phone to bridge.

## 8. Comparison with the previous VPN design (dropped)

| | VPN (previous decision, dropped) | Relay with end-to-end layer (this design) |
|---|---|---|
| Works on a network that blocks VPNs | No | Yes, if ordinary HTTPS to the relay works |
| Bridge reachable from outside | Only inside the private network, on a listening port | No listening port at all |
| Who could read content | Nobody on the path (VPN encryption); the VPN vendor coordinates keys | Nobody on the path (own end-to-end layer); relay sees ciphertext |
| Who learns metadata | VPN vendor (device list, connection times) | Relay provider, push provider |
| Authentication of the phone | VPN membership + bearer token | Per-device secret, counters, timestamps |
| Third-party accounts needed | VPN account | Account for the relay/push provider |
| Cryptography maintained by | VPN vendor (large, audited product, forward secrecy included) | This project's use of libsodium (small, standard constructions, **no forward secrecy**) |
| Revoking a phone | In the VPN admin console | One command on the computer |
| Own code that must be right | Little | Pairing, message checks, counters: more code, therefore the tests in section 10 |
| Push to the phone | Not solved by the VPN | Same push service either way |

Honest summary: the VPN design delegates more to a mature product and gives
forward secrecy; this design works where the VPN cannot, exposes no port,
and trusts no intermediary, at the cost of more security-relevant code of
our own and static keys.

## 9. Decided points

The owner chose the simple design below as it stands (2026-11-20): the whole
key comes from the QR code; no key agreement and no forward secrecy for now.
The variant remains possible later without changing the relay.

1. **Pairing variant (not adopted).** As designed, no key material ever crosses a network
   (requirement met literally), and a photographed QR code is a full
   compromise of that pairing. Variant: the phone creates its own key pair
   and sends only its *public* key, encrypted with the QR secret, through the
   relay; a photographed QR code alone is then not enough to read later
   traffic. This needs a key-agreement step, which means adopting an audited
   secure-channel protocol implementation rather than the simple
   constructions above. It is a trade-off between "nothing key-like on the
   network" and resistance to a leaked QR code.
2. **Forward secrecy** (section 3.4, not adopted): same prerequisite as
   point 1.

## 10. Tests the implementation must pass

- A message with any changed byte (ciphertext, device id, direction,
  counter, kind) is rejected.
- A repeated message is rejected, also after a bridge restart.
- A message with an old timestamp is rejected; a message in the wrong
  direction (reflected) is rejected.
- A revoked device is rejected immediately.
- Messages from an unknown device id cause no work and no distinguishable
  answer.
- With a relay that records everything, the recording contains no plaintext
  of prompts, answers, titles or paths.
- Pairing expires after two minutes and works once.
- Device secrets never appear in logs, issue text, notifications or the
  repository.
