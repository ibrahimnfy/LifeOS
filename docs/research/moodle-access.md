# Moodle access: obtaining a web-service token on an SSO site

How the bridge can get a web-service token for the university Moodle when
the owner's account signs in through single sign-on (SSO), so that the
password endpoint `/login/token.php` cannot be used.

- **Researched:** 2026-11-20, from primary sources only (Moodle source,
  official app source, official docs). No Moodle site was contacted for sections 1 and 3–7;
  section 2 is one credential-less query of the university Moodle.
- **Code read:** `moodle/moodle` branch `main` (6.0dev, build 20261117;
  web files live under `public/`), spot-checked against
  `MOODLE_405_STABLE` (4.5.16+) and `MOODLE_401_STABLE` (4.1.22+, no
  `public/` prefix); `moodlehq/moodleapp` branch `main` (5.3.0).
- **Tags:** [S-x] = read in source/doc `x` (see Sources); **inferred** =
  follows from the code but is not documented or tested; **not verified**
  = could not be confirmed.
- **Placeholders:** `<site>` = base URL of the university Moodle.

## 1. The public site-configuration call

`tool_mobile_get_public_config` is declared `ajax => true`,
`loginrequired => false`, no parameters [S-mobsvc]. The official app calls
it without any credential [S-appws][S-appsite]:

```
POST <site>/lib/ajax/service-nologin.php?info=tool_mobile_get_public_config
Content-Type: application/json

[{"index":0,"methodname":"tool_mobile_get_public_config","args":{}}]
```

- `info=` is for debugging only [S-appws]. GET fallback: the same JSON,
  URL-encoded, as `&args=...`; `service-nologin.php` sets
  `NO_MOODLE_COOKIES` and `ALLOW_GET_PARAMETERS`, then includes
  `service.php` [S-nologin][S-ajax].
- Response: a JSON array, one element per request:
  `[{"error":false,"data":{...}}]`, or `"error":true` plus an `exception`
  object [S-extapi]. Calling `/lib/ajax/service.php` directly should work
  too for this function (**inferred**; the app uses `-nologin`).

Fields that matter [S-mobapi][S-mobext]:

| Field | Meaning |
|---|---|
| `wwwroot` | Canonical site URL. Needed verbatim for the check in 3. |
| `enablewebservices` | Web services on at all. If 0 nothing below works. |
| `enablemobilewebservice` | "Enable web services for mobile devices". |
| `typeoflogin` | 1 = via the app (password form), 2 = via a browser window, 3 = via an embedded browser; 2 and 3 are "for SSO plugins" [S-moblang][S-appsite]. |
| `launchurl` | `<site>/admin/tool/mobile/launch.php` (section 3). |
| `identityproviders` | Optional list (name, `url`, `iconurl`) of SSO buttons from the enabled auth plugins. |
| `maintenanceenabled` | Site in maintenance mode; token calls are refused [S-wslib]. |
| `tool_mobile_qrcodetype` | 0 = disabled, 1 = QR with site URL only, 2 = QR with automatic login. |
| `showloginform` | 0 = manual login form hidden (present in 4.5 and main, absent in 4.1). |

`release` / `version` are **not** in this response. They are returned by
`core_webservice_get_site_info`, which needs a token [S-wsext].

## 2. What the university Moodle advertises

Queried once on 2026-11-20 with the call of section 1, without any
credential. Only non-identifying values are recorded here.

| Field | Value | Meaning |
|---|---|---|
| `enablewebservices` | 1 | web services are on |
| `enablemobilewebservice` | 1 | the mobile service is on |
| `maintenanceenabled` | 0 | — |
| `typeoflogin` | 1 | "via the app": the app shows its own login form |
| `launchurl` | present | the standard `launch.php` address |
| `identityproviders` | 4 | sign-in buttons: 3 of the core OAuth 2 plugin, 1 of an OpenID Connect plugin that is not part of core |
| `tool_mobile_qrcodetype` | 2 | QR login with automatic sign-in is advertised (app only, section 5c) |
| `showloginform` | absent | suggests a version before 4.5 (not conclusive) |

What follows from it:

- **Tokens are possible in principle:** both switches are on.
- **The password endpoint is the advertised way** (`typeoflogin` = 1), which
  is why the site does not send the app to a browser by default. It fails
  for the owner because the account has no local password (SSO).
- **A cold launch of `launch.php` is probably refused** (section 3, step 3):
  `typeoflogin` is 1, and the OAuth 2 exception exists only from 4.5.
- **The `oauthsso` route may work:** if the owner's account signs in through
  one of the three OAuth 2 buttons, `launch.php?...&oauthsso=<issuer id>`
  starts at that provider and returns to `launch.php` right after login,
  which is the path the official app takes for such buttons. Not tested.
- **If the owner's sign-in is the OpenID Connect button,** the sources read
  here do not cover it; whether that plugin supports the mobile launch is
  **not verified**.
- **The owner's test with the official mobile app decides:** if the app can
  sign in with the same button, the same flow is available to the bridge;
  if it cannot, only the alternatives in section 5 remain.


## 3. The official app's browser SSO flow

Request (built by the app in `prepareForSSOLogin`) [S-applogin]:

```
<site>/admin/tool/mobile/launch.php?service=moodle_mobile_app
      &passport=<random>&urlscheme=moodlemobile
```

What `launch.php` does, in order [S-launch]:

1. Validates `urlscheme` (letters, digits, `.+-`); requires web services
   enabled and the named service to exist and be enabled.
2. Sets a 15-minute cookie `tool_mobile_launch` with the parameters; after
   login a hook uses it to send the browser back to `launch.php`
   [S-mobhook]. (Main and 4.5; 4.1 has no such cookie.)
3. Refuses with `pluginnotenabledorconfigured` unless `typeoflogin` is 2
   or 3, or OAuth 2 auth is enabled (not an exception in 4.1), or the
   session carries the just-logged-in flag. This runs **before** step 4.
4. `require_login()` (this is where the SSO redirect happens), then
   requires an active, non-guest user.
5. Gets or creates the token with
   `core_external\util::generate_token_for_current_user()`.
6. Builds the answer and redirects:

```
siteid   = md5(wwwroot . passport)
payload  = siteid ":::" token [":::" privatetoken]
location = <urlscheme>://token=<base64(payload)>
```

- The app checks `md5(siteUrl + passport)` against the first part (trying
  both http and https) and deletes the passport after one use
  [S-applogin]. The app's passport is `Math.random() * 1000`; the server
  accepts any raw string.
- **`forcedurlscheme`:** if set, it replaces the requested `urlscheme`.
  Its default value is `moodlemobile`; empty means "allow any app"
  [S-launch][S-mobset][S-moblang].
- **User agent:** in main, 4.5 and 4.1 `launch.php` never refuses a
  client by user agent. It tests for iOS: on iOS (or `confirmed=1`) it
  prints a page whose link is clicked by script; otherwise it sends a
  plain HTTP redirect, no click needed. 4.1/4.5 also add cookie
  attributes when the agent is the Moodle app [S-launch].
- **`oauthsso=<issuer id>`** (optional): start directly at that OAuth 2
  issuer, then return to `launch.php`; the app takes the id from an
  `identityproviders` URL [S-launch][S-applogin].
- **`privatetoken`:** added only over HTTPS, for non-admins, and only when
  the user has just logged in or the token is new. The app uses it for
  `tool_mobile_get_autologin_key` (open the site in a browser already
  signed in); that function rejects clients whose user agent is not the
  Moodle app [S-launch][S-mobext]. The bridge has no use for it.
- **Token creation and lifetime** [S-extutil][S-secset][S-access]:
  an existing valid token for the same user and service is reused,
  otherwise a new one is created if the user has
  `moodle/webservice:createmobiletoken` (default: allowed for the
  authenticated-user archetype). `validuntil = now + tokenduration`;
  the setting "User created token duration" defaults to 12 weeks. Expired
  tokens are deleted on the next request, so the flow must be repeated
  about every 12 weeks (or whatever the site sets).
- Because tokens are reused, the bridge and the official phone app would
  hold the **same** token (**inferred**).

## 4. Can a desktop client complete the flow?

**Verified from source (main, 4.5, 4.1):** nothing in `launch.php` ties
the flow to the official app: no user-agent gate, no client secret, and
the passport is chosen by the client. The owner signs in on the institution's own SSO
page; the bridge never sees the password.

**Inferred, not tested (no site was contacted):**

- The owner opens the launch URL in a normal desktop browser and signs in.
  The last response is a redirect to `moodlemobile://token=...`. A desktop
  browser has no handler for it, so the value must be captured by either:
  - **copy-paste:** open the browser's developer tools (Network tab)
    before starting, and copy the `Location` header of the final
    `launch.php` response; or
  - **local helper:** register a per-user handler for the URL scheme on
    the desktop (no root needed) that passes the URL to a small script.
- The scheme to capture is whatever the site forces (default
  `moodlemobile`); a custom scheme only works if the admins emptied
  `forcedurlscheme`.
- The helper base64-decodes the value, splits on `:::`, checks part 1
  against `md5(wwwroot . passport)`, stores part 2, discards part 3.
- **Precondition:** `typeoflogin` must be 2 or 3 (or, from 4.5, OAuth 2
  auth enabled). With `typeoflogin` = 1 a cold launch is refused at step
  3, signed in or not. The just-logged-in flag is set at login and
  cleared when the next normal page is rendered [S-moodlelib][S-render];
  reaching `launch.php` inside that window is **not verified** and should
  not be relied on.

**Policy:**

- Official developer docs describe passing a token to the app by URL as
  "really useful for external apps and systems" and warn that "anyone with
  the token will be able to authenticate as the user the token belongs to"
  [S-deeplink]. No official text was found that forbids or endorses
  non-official clients (**not verified** either way).
- Site admins control this: they can disable mobile web services, restrict
  the URL scheme, or remove the capability [S-mobset][S-access].
- The institution's own acceptable-use rules were not examined. Whether a
  personal client is acceptable there is **the owner's decision**.
- Do not fake the app's user agent to pass app-only checks.

## 5. Alternatives

- **(a) Security keys page.** Owner: Preferences → Security keys, copy or
  reset the key. Admins: grant `moodle/webservice:createtoken`. The page
  shows web-service keys only to a non-admin user holding that
  capability (default: manager archetype only) with web services on
  [S-managetoken][S-access]; docs: users need "the
  moodle/webservice:createtoken capability" [S-dockeys]. In 4.5 and main
  the table shows the token **name** only and the value once, right
  after creation or "Reset"; in 4.1 the table shows the value
  [S-wsrender]. A reset would also sign out the phone app (**inferred**).
- **(b) Custom service/token.** Owner: ask the admins. Admins: add a
  custom service (optionally "Authorised users only") and create a token
  under Manage tokens, with optional IP restriction and "Valid until"
  [S-docws]. Cleanest result (read-only function list, long validity),
  but depends on the admins agreeing.
- **(c) QR login.** Admins: Premium app plan with automatic-login QR
  [S-moblang][S-docadmin]. The profile QR encodes
  `<scheme>://<wwwroot>?qrlogin=<key>&userid=<id>`; the key is
  single-use, valid 10 minutes by default (`qrkeyttl`) and bound to the
  generating IP when `qrsameipcheck` is on. It is exchanged through
  `tool_mobile_get_tokens_for_qr_login`, which requires HTTPS, a
  non-admin user and a Moodle-app user agent (`apprequired`)
  [S-mobapi][S-mobext]. Not usable by the bridge.
- **(d) Calendar export URL.** Owner: Calendar → Export → get the URL.
  `<site>/calendar/export_execute.php?userid=<id>&authtoken=<t>` plus
  `preset_what` (`all`, `user`, `groups`, `courses`, `categories`) and
  `preset_time` (`weeknow`, `weeknext`, `monthnow`, `monthnext`,
  `recentupcoming` = last 5 / next 60 days, `custom`) returns iCal
  without a session, if `enablecalendarexport` is on. `authtoken =
  sha1(userid . stored password hash . site salt)`: no expiry, but it
  changes if that hash changes [S-calexp][S-callib][S-doccal]. Calendar
  events (deadlines) only; no files or course content.
- **(e) Cookie scraping: not recommended.** Reusing the browser session
  cookie to scrape HTML breaks with every theme or version change and
  session timeout, means handling the full-power login session instead
  of a scoped token, and bypasses the interface the site offers clients.

## 6. Web-service functions we need

Call: `POST <site>/webservice/rest/server.php` with `wstoken`,
`wsfunction`, `moodlewsrestformat=json` (default is XML) [S-rest]. All
names below are defined with `services => [moodle_mobile_app]`
[S-coresvc][S-assignsvc]:

| Purpose | Function |
|---|---|
| Who am I, site release, allowed functions | `core_webservice_get_site_info` |
| Enrolled courses | `core_enrol_get_users_courses` |
| Sections, activities, file URLs | `core_course_get_contents` |
| Assignments and due dates | `mod_assign_get_assignments` |
| Submission state | `mod_assign_get_submission_status` |
| Upcoming deadlines (timeline) | `core_calendar_get_action_events_by_timesort` |
| Same, per course | `core_calendar_get_action_events_by_courses` |
| Token for option (d) | `core_calendar_get_calendar_export_token` |

Files: `GET <site>/webservice/pluginfile.php/<path>?token=<token>`; the
service must allow file download, which the mobile service definition
does (`downloadfiles => 1`) [S-pluginfile][S-coresvc][S-docfiles]. Plain
`/pluginfile.php` needs a login session [S-docfiles]. The exact URL form
returned by `core_course_get_contents` is **not verified**. A site can
disable individual app features; the real function list must be read from
`core_webservice_get_site_info` after the first login.

## 7. Recommendation

1. **Try first: browser sign-in with copy-paste (sections 3–4).** On this
   site (section 2) only through the `oauthsso` route, and only if the
   owner's test shows that the official app can sign in; otherwise go to
   step 2. The owner
   opens one link the bridge prints, signs in on the university's normal
   SSO page, and copies one line (the `moodlemobile://token=...` address)
   back into a local prompt. Repeat when the token expires (default 12
   weeks). Needs the owner's go-ahead on the policy point in section 4.
2. **Second: ask the Moodle administrators (5b or 5a).** The owner sends
   a short request: "please issue me a read-only web-service token" or
   "please allow students to create a security key". Slow, but explicitly
   approved and the longest-lived.
3. **Third: calendar export URL (5d).** The owner clicks Calendar →
   Export, chooses all events / next 60 days, copies the URL into a local
   file. Gives deadlines only; courses and files stay out of reach.

Not options: QR login (app only), cookie scraping, user-agent spoofing.

Security notes:

- The token acts as the owner's account for every API call it allows,
  including write functions and file upload. Store it only in
  `~/.config/lifeos/` (file mode 600); never in the repo, `~/brain`,
  issues or logs.
- Tokens travel in the request body or the query string (`?token=`,
  `authtoken=`): never log URLs, request bodies or the redirect value.
- Do not store the `privatetoken`; verify the md5 check before accepting
  a pasted value; HTTPS only. Revoke via Security keys → Reset (if
  visible) or the admins.

## 8. Not verified

Besides the items tagged inline: desktop-browser behaviour on the
custom-scheme redirect and both capture methods (untested); any official
statement for or against non-official clients, and the institution's own
policy; whether students hold `webservice/rest:use`; the default of
`enablecalendarexport`; versions other than main, 4.5 and 4.1.
docs.moodle.org pages were read through a summarising fetch tool, so only
the quoted phrases are relied on.

## Sources

`M` = `https://github.com/moodle/moodle/blob/main/public`,
`A` = `https://github.com/moodlehq/moodleapp/blob/main`. Stable-branch
checks used the same files under
`https://github.com/moodle/moodle/blob/MOODLE_405_STABLE/` and
`.../MOODLE_401_STABLE/` (no `public/` prefix).

| Key | URL | Key | URL |
|---|---|---|---|
| S-launch | `M/admin/tool/mobile/launch.php` | S-wsrender | `M/webservice/renderer.php` |
| S-mobapi | `M/admin/tool/mobile/classes/api.php` | S-rest | `M/webservice/rest/locallib.php` |
| S-mobext | `M/admin/tool/mobile/classes/external.php` | S-pluginfile | `M/webservice/pluginfile.php` |
| S-mobsvc | `M/admin/tool/mobile/db/services.php` | S-managetoken | `M/user/managetoken.php` |
| S-mobset | `M/admin/tool/mobile/settings.php` | S-access | `M/lib/db/access.php` |
| S-moblang | `M/admin/tool/mobile/lang/en/tool_mobile.php` | S-secset | `M/admin/settings/security.php` |
| S-mobhook | `M/admin/tool/mobile/classes/hook_callbacks.php` | S-coresvc | `M/lib/db/services.php` |
| S-ajax | `M/lib/ajax/service.php` | S-assignsvc | `M/mod/assign/db/services.php` |
| S-nologin | `M/lib/ajax/service-nologin.php` | S-calexp | `M/calendar/export_execute.php` |
| S-extapi | `M/lib/external/classes/external_api.php` | S-callib | `M/calendar/lib.php` |
| S-extutil | `M/lib/external/classes/util.php` | S-applogin | `A/src/core/features/login/services/login-helper.ts` |
| S-wslib | `M/webservice/lib.php` | S-appws | `A/src/core/services/ws.ts` |
| S-wsext | `M/webservice/externallib.php` | S-appsite | `A/src/core/classes/sites/unauthenticated-site.ts` |
| S-moodlelib | `M/lib/moodlelib.php` | S-render | `M/lib/classes/output/core_renderer.php` |

| Key | URL |
|---|---|
| S-deeplink | https://moodledev.io/general/app/development/link-handling/deep-linking |
| S-docfiles | https://moodledev.io/docs/5.1/apis/subsystems/external/files |
| S-docadmin | https://docs.moodle.org/en/Moodle_app_guide_for_admins |
| S-dockeys | https://docs.moodle.org/en/Security_keys |
| S-docws | https://docs.moodle.org/en/Using_web_services |
| S-doccal | https://docs.moodle.org/en/Calendar_export |
