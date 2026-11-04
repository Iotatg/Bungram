[![CI](https://github.com/Iotatg/Bungram/actions/workflows/ci.yml/badge.svg)](https://github.com/Iotatg/Bungram/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/Iotatg/Bungram)](https://github.com/Iotatg/Bungram/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

# Bungram

Native Android messenger in **Kotlin + Jetpack Compose**, rebuilt from the reference UI
(Message / Chat / Status screens), plus a **Kotlin/Ktor backend** and a Signal-style **E2EE core**.

Everything here is written from scratch — no code copied from Telegram, Signal or Element.
`docs/01-FEATURE-MAP.md` explains how those three implement each subsystem and where Bungram
implements its own version.

---

## What is in this repo

```
Bungram/
├── android/                 Android app (Kotlin, Compose, Material 3)
│   └── app/src/main/java/app/bungram/
│       ├── core/design/     tokens, Inter type scale, shared components, bottom bar
│       ├── core/nav/        NavHost + routes
│       ├── feature/         messages · chat · status · calls · profile · home
│       ├── data/            models · SQLite cache · outbox · REST client · secure token store
│       ├── data/media/      uploader · voice recorder · audio player · URL resolver
│       ├── crypto/          X25519 · X3DH · Double Ratchet · Sender Keys · AES-GCM · E2ee engine
│       ├── sync/            WebSocket + cursor sync + backoff
│       └── service/         SyncService (foreground) · MessageNotifications (reply/read) · CallService
├── server/                  Ktor 3 backend (auth, chats, messages, sync, media, WS)
│   └── src/main/kotlin/app/bungram/server/
│       ├── media/           content-addressed blob store · image tools · URL signer
│       ├── routes/          REST + WebSocket + media routes
│       └── store/           in-memory store (Postgres in phase 8)
├── artifacts/               bungram-0.4.0-debug.apk (built here)
├── tools/                   e2e_two_devices.py (7) · e2e_media.py (25) · e2e_groups.py (21)
├── preview/index.html       1:1 interactive UI reference (open in a browser)
└── docs/                    feature map · 36 Q&A · architecture · REA workflow · deep dive
                             · 10-phase roadmap · per-phase build reports (Hindi)
```

## Verified in this environment

| Check | Result |
|---|---|
| `android: gradle :app:assembleDebug` | ✅ BUILD SUCCESSFUL — `artifacts/bungram-0.4.0-debug.apk` (28.8 MB, `app.bungram.debug` 0.4.0, minSdk 26, targetSdk 35) |
| `android: gradle :app:testDebugUnitTest` | ✅ **18/18** — X3DH, ratchet, out-of-order, replay, tamper, storage reload, safety numbers |
| `server: gradle build` | ✅ BUILD SUCCESSFUL (Kotlin 2.0.21 / Ktor 3.0.1 / JDK 17 target) |
| Server boots on :8080 | ✅ `{"ok":true,"data":{"status":"ok",...}}` |
| OTP → JWT login | ✅ token issued, profile synced |
| `tools/e2e_two_devices.py` | ✅ **7/7 checks** — login, lookup, chat, WS delivery, receipt, typing, idempotent retry, cursor |
| `tools/e2e_media.py` | ✅ **25/25 checks** — upload, sniff, thumbnail, dedupe, quota, range, ETag, ACL, share link, delete |
| `tools/e2e_groups.py` | ✅ **21/21 checks** — groups, channels, roles, invites, stickers, devices, one-time pre-key pool |
| App ↔ server wiring (P1) | ✅ login gate, local SQLite cache, durable outbox, delta sync, read receipts |
| Media pipeline (P2) | ✅ pick → upload → message, image/video/file/voice bubbles, fullscreen viewer |
| Groups & channels (P3) | ✅ create, roles, add/remove, invites, system messages, 180 stickers + emoji panel |
| E2EE (P4) | ✅ X3DH + Double Ratchet, encrypted prefs, safety number, lock badge, "not available" placeholder |
| Push (P5) | ✅ device registry, FCM/ntfy dispatcher, MessagingStyle + inline reply + mark-as-read |
| REA analysis of the 3 reference APKs | ✅ Telegram 13.1.1 · Signal 8.33.2 · Element X 26.10.1 |

## Run the backend

```bash
cd server
gradle installDist                 # or: gradle build
BUNGRAM_DEV=1 ./build/install/bungram-server/bin/bungram-server
# health: curl localhost:8080/v1/healthz
```

`BUNGRAM_DEV=1` returns the OTP in the response (`devCode`) so you can log in without an SMS
provider. Unset it in production.

## Run the app

1. Open `android/` in Android Studio (Ladybug or newer).
2. Let Gradle sync (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01).
3. Run on a device/emulator (API 26+). The app opens on the **login screen** and talks to the
   real server: emulator uses `http://10.0.2.2:8080` by default; on a real phone tap
   `Server · …` and enter `http://<your-lan-ip>:8080` (debug builds allow cleartext).
4. Log in with any number — the dev build shows the OTP as a tappable chip. A new account is
   provisioned with one `Bungram` service chat; use **New chat → phone number** to reach a
   second device.

## Preview the UI without Android Studio

Open `preview/index.html`. It is the same design rendered in HTML/CSS: tap the tabs, open the
attachment sheet, send a message, open a story. Useful for design review and for comparing
against the original screenshots.

## Design tokens (single source of truth)

| Token | Value | Used for |
|---|---|---|
| `BungramBlue` | `#5667F5` | outgoing bubbles, FAB, active tab, badges |
| `SurfaceMuted` | `#F4F5F9` | search field, icon buttons, attachment chips |
| `TextPrimary` | `#11131C` | titles, names |
| `TextSecondary` | `#9AA1B2` | previews, timestamps |
| `TextTertiary` | `#BFC5D2` | placeholders, inactive tabs |
| Radius | 14 / 18 / 22 / 28 | fields · bubbles · composer · bottom sheet |
| Type | Inter 400/500/600/700 | bundled in `res/font` (SIL OFL) |

## Screens

- **Message** — header with QR + compose, search with filter, "New Group / Archive (3)", story
  rail, chat rows with unread badges and read ticks.
- **Chat** — patterned canvas, date pill, incoming/outgoing bubbles with per-message timestamps
  and delivery ticks, **image / video / voice / file bubbles with upload progress**, fullscreen
  media viewer, inline attachment menu (Camera / Gallery / Document), composer with + / mic / send,
  live "typing…" in the header.
- **Status** — grid header, search, "My status" with add badge, people rail with story rings,
  staggered 2-column story grid with play affordance, full-screen viewer.
- **Call / Profile** — recent calls with direction icons; profile with editable name / username /
  about, linked devices, sound toggle and encryption footer.
- **Groups & channels** — create screen with member chips, group info with roles and invite links,
  system messages, sender names in bubbles.
- **Encryption** — lock in the chat header, 60-digit safety number sheet, honest placeholder when a
  payload cannot be opened on this device.

## Docs

| File | Contents |
|---|---|
| `docs/01-FEATURE-MAP.md` | 28 subsystems: Telegram vs Signal vs Element vs Bungram, with file pointers |
| `docs/02-30-QUESTIONS.md` | 36 concrete questions answered + 5 decisions to make before coding |
| `docs/03-ARCHITECTURE.md` | module rules, REST/WS protocol, Postgres schema, media pipeline, scaling, roadmap |
| `docs/04-REA-WORKFLOW.md` | how REA was installed and used, with the exact commands and results |
| `docs/05-DEEP-DIVE.md` | 30 subsystems reverse-engineered, with pinned commits, file:line anchors and APK evidence |
| `docs/06-CAPABILITY-INVENTORY.md` | 60-row feature parity matrix, Element X's 46 modules, Signal/Telegram package sizes |
| `docs/07-REA-FULL-ANALYSIS.md` | round 2: full class inventories, component/permission surfaces, class-member + method-body + reference-graph evidence, 33 slash commands, P1–P6 port plan |
| `docs/analysis/index.html` | visual dashboard of the round-2 findings (open in a browser) |
| `docs/08-REA-COMMAND-COVERAGE.md` | all 153 REA commands classified: which run on an APK, which ran, which need Ghidra/adb/CDP |
| `docs/09-HINDI-P1-BUILD.md` | **P1 in Hindi** — kya banaya, message ka poora safar, 4 bug fixes, 6/6 test, kaise chalayein, kya baaki hai |
| `docs/10-ROADMAP-10-PHASES.md` | **10-phase plan to Telegram scale** — scope, deliverables, verification and risks per phase |
| `docs/11-HINDI-P2-BUILD.md` | **P2 in Hindi** — media pipeline (upload → blob → thumb → signed URL), 8 bug fixes, 25/25 + 7/7 tests |
| `docs/12-HINDI-P3-P5-BUILD.md` | **P3+P4+P5 in Hindi** — groups/channels, E2EE (X3DH + ratchet + tests), push, login rewrite, profile, assets, 12 bug fixes |

## Research setup (reproducible)

```bash
# references, pinned
git clone --depth 1 https://github.com/DrKLO/Telegram.git            research/repos/telegram    # 3de37c8
git clone --depth 1 https://github.com/signalapp/Signal-Android.git  research/repos/signal      # 515db93
git clone --depth 1 https://github.com/element-hq/element-x-android.git research/repos/elementx # 9aef471f
git clone --depth 1 https://github.com/morluto/rea.git               research/repos/rea         # 007e181

# REA 6.7.0 + JADX bridge 0.7.1 (Node >= 22.15 required)
nvm install 22.20.0 && nvm use 22.20.0 && npm i -g rea-agents
export REA_JADX_MCP_JAR=/opt/rea-engines/jadx-headless-mcp-0.7.1-all.jar
rea doctor
rea inspect-android-package research/apk/telegram.apk --format json > rea_telegram.json
```

## Licence hygiene

Telegram Android is GPL-2.0, Signal-Android GPL-3.0, element-x-android AGPL-3.0. Bungram is a
clean-room implementation: behaviour and public protocol specs only, no copied source.
