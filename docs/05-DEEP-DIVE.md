# Bungram — Reverse-Engineering Dossier

> How Telegram, Signal and Element X actually build each subsystem, what Bungram takes from each,
> and what it must build itself. Every claim below is anchored to a cloned repository at a pinned
> commit or to a REA evidence record produced from a real APK.

---

## 0. What was actually done

| Step | Result |
|---|---|
| Clone `DrKLO/Telegram` | `3de37c8` — *update to 13.1.1 (7237)* — 558 MB, 2,230 Java files |
| Clone `signalapp/Signal-Android` | `515db93` — *Bump version to 8.34.2* — 251 MB, 4,336 Kotlin/Java files |
| Clone `element-hq/element-x-android` | `9aef471f` — 164 MB, 4,377 Kotlin files |
| Clone `morluto/rea` | `007e181` — 122 MB |
| Install REA | `rea-agents@6.7.0` on Node 22.20 (needs ≥22.15 for `zlib.zstd*`) |
| Install JADX bridge | `jadx-headless-mcp-0.7.1-all.jar`, SHA-256 verified `6e5eacf5…ff75` |
| REA `doctor` | `jadx: healthy`; Hopper / Ghidra / IDA / Kuna absent (not needed for Java/APK work) |
| APK analysis — Telegram | 13.1.1 (72379), `org.telegram.messenger.web`, 16,432 classes, 7,531 resources, 74 permissions |
| APK analysis — Signal | 8.33.2 (177201), `org.thoughtcrime.securesms`, 22,008 classes, 4,140 resources, 77 permissions |

Evidence files live in `research/`: `rea_telegram.json`, `rea_signal.json`, `rea_elementx.json`.
Each is a signed-style evidence envelope with `evidence_id`, subject digest, provider identity and
explicit `limitations` (static analysis never proves runtime behaviour).

---

## 1. Three architectures in one page

| | **Telegram** | **Signal** | **Element X** | **Bungram (chosen)** |
|---|---|---|---|---|
| Protocol | MTProto 2.0, custom binary, own DCs | REST + WebSocket, protobuf, Signal Server | Matrix CS API + Sliding Sync | REST/JSON + WebSocket, protobuf later |
| Server holds history | **Yes** — server is the source of truth | **No** — server is a blind queue | Yes (federated DAG) | Yes, but E2EE payloads are opaque |
| Sync model | `pts / qts / seq` counters + gap repair | per-conversation cursor, full local DB | Sliding Sync windows + timeline diffs | monotonic `cursor` + gap repair |
| Crypto | MTProto built-in + secret chats (DH) | libsignal: X3DH + Double Ratchet + Sender Keys | Olm/Megolm (legacy) + MLS (Rust SDK) | own X3DH + Double Ratchet + Sender Keys |
| Client DB | SQLite (`messages_v2`, `dialogs`, FTS) | SQLCipher, ~80 tables | Rust SDK store (SQLite) | Room + SQLCipher |
| UI | 100 % Android Views, hand-drawn | Views + Compose (migrating) | 100 % Compose, modular | Compose, single `:app` + feature packages |
| Modules | 1 app module, 2,230 files | 63 Gradle modules | ~70 Gradle modules | 1 module first, split at ~15 k LOC |
| Multi-device | first-class (auth keys per device) | linked devices via provisioning | cross-signing + device keys | device list + per-device key bundles |
| Calls | own VoIP (WebRTC + custom signaling) | WebRTC + Signal calling | Element Call (LiveKit SFU) | WebRTC + Ktor signaling, LiveKit later |

**The single most important finding:** the three apps disagree about *where truth lives*. Telegram
keeps history on the server and mirrors it; Signal keeps it only on the device and treats the
server as a mailbox; Element keeps a replicated DAG. Bungram must pick one **before** writing the
sync engine, because every other subsystem (search, receipts, backup, multi-device) inherits that
choice. Bungram picks **server-authoritative + opaque E2EE payloads** (Telegram's model with
Signal's privacy), because it is the only model that gives new-device restore for free.

---

## 2. Subsystem map — 30 systems, three implementations, one decision each

### 2.1 Account creation & registration

- **Telegram**: phone number → `auth.sendCode` → SMS/Telegram code → `auth.signUp`. The `code`
  itself can arrive *inside Telegram* for another logged-in device (`auth.checkOtherLoginCode`).
  Implemented in `ui/LoginActivity.java` + `messenger/UserConfig.java`.
- **Signal**: phone → SMS/voice → PIN (Registration Lock) → profile. `registration/` package;
  the PIN also derives the Secure Value Recovery key.
- **Element X**: username/password or OIDC SSO, then UIA flows (`features/login`, `features/ftue`).
- **Bungram**: phone + OTP (`/auth/request-code`, `/auth/verify`), 6-digit code, 5 attempts,
  60 s resend, then device registration that uploads identity + signed pre-key + 100 one-time
  pre-keys. `server/.../routes/AuthRoutes.kt`, `feature/auth` (to build).

### 2.2 Sessions, devices, multi-account

- **Telegram**: `UserConfig` holds up to 4 accounts; each account has its own `AccountInstance`
  (connections, database, notifications). Sessions are `auth.authorizations` on the server.
- **Signal**: one account per install, linked devices via `linkdevice/` provisioning
  (QR → device transfer → 100-message sync).
- **Element X**: multi-account session store, each session has its own Rust client.
- **Bungram**: single account v1; `DeviceRegistry` table (`device_id`, `name`, `last_seen`,
  `push_token`) + `GET /devices`, `DELETE /devices/{id}`. Multi-account is a v2 item and requires
  refactoring `AppGraph` from singleton to `AccountScope` — do it before 10 k LOC, not after.

### 2.3 Transport: sockets, reconnect, backoff

- **Telegram**: `tgnet/ConnectionsManager.java` owns DC list, `ConnectionInfo` per DC, MTProto
  salts, and reconnects with exponential backoff. Requests are `TLObject`s with `ack` semantics;
  `QuickAckDelegate` gives sub-RTT confirmation.
- **Signal**: one WebSocket (`libsignal`/`SignalWebSocket`), authenticated by a password derived
  from the ACI; REST fallback; `KeepAliveSender` pings every 30 s.
- **Element X**: Rust SDK owns the sync loop; Kotlin only observes `SyncService` state.
- **Bungram**: `sync/SyncEngine.kt` — WS with `?since=cursor`, ping 25 s, backoff
  1→2→4→8→16→30 s with ±20 % jitter, resume by cursor, REST catch-up when the socket cannot
  connect. **Never** rely on the socket for correctness; the cursor is the contract.

### 2.4 Update model & gap repair (the hardest part)

- **Telegram**: every update carries `pts` (private chats), `qts` (secret chats) or `seq` (global).
  `MessagesController.getDifference()` is called when `pts` jumps; `messages.getDifference`
  returns `new_messages`, `other_updates`, `pts`. Holes are stored in `messages_holes` so the UI
  can render "loading" gaps instead of wrong order. This is ~2,000 lines of very sharp edges.
- **Signal**: no gaps to repair — each message is an independent envelope with a server timestamp;
  the client sorts by `(timestamp, sender, device)` and dedupes by `(sender, timestamp)`.
- **Element X**: Sliding Sync returns a `limited` timeline; when limited, the client refetches a
  window instead of patching.
- **Bungram**: monotonic `seq` per conversation + a global `cursor`. On `cursor` mismatch:
  `GET /sync?since=` returns `messages`, `receipts`, `presence`, `deleted`. If the server answers
  `"gap": true`, the client drops the conversation cache and refetches the last 50 — cheaper and
  far safer than hole-patching for a v1.

### 2.5 Local database

- **Telegram**: `MessagesStorage.java` (~20 k lines) creates `messages_v2`, `dialogs`, `chats`,
  `users`, `media_v2`, `messages_holes`, plus FTS4 tables for search. Schema version is bumped in
  `SQLite/SQLiteDatabase.java` with hand-written migrations.
- **Signal**: ~80 tables, `SignalDatabase` + `*Table.kt` per entity, versioned migrations in
  `database/migrations/`; SQLCipher with the key in the Android Keystore.
- **Element X**: the Rust SDK owns storage; Kotlin has no SQL at all.
- **Bungram**: Room, 9 tables (`users`, `chats`, `chat_members`, `messages`, `attachments`,
  `receipts`, `stories`, `devices`, `outbox`), FTS4 on `messages.text`, SQLCipher via
  `SupportOpenHelperFactory`. Keep the schema boring; every clever column costs a migration.

### 2.6 Send pipeline / outbox / retry

- **Telegram**: `SendMessagesHelper.performSendMessageRequest()` (line 8006) builds the TL request,
  stores a `DelayedMessage`, uploads media through `FileLoader`, then `messages.sendMessage`.
  Failure → `MessagesController` marks `send_state = 2` and retries on reconnect.
- **Signal**: every send is a `Job` (`jobs/`, `jobmanager/`). `AttachmentUploadJob` →
  `PushTextSendJob`/`PushGroupSendJob`. The job queue is persisted, so a kill mid-send resumes.
- **Element X**: Rust SDK `send_queue` with local echo and retry.
- **Bungram**: `outbox` table + `SyncEngine` worker. States `QUEUED → SENT → DELIVERED → READ`,
  `FAILED` after 5 attempts with exponential backoff, manual retry from the bubble menu. Local echo
  is inserted with `id = client_uuid`, reconciled by `client_uuid` on ack.

### 2.7 Receipts, read state, unread counts

- **Telegram**: `messages.readHistory` / `channels.readHistory`, `read_state` per message, unread
  counters maintained server-side and pushed as `updateReadHistoryInbox`.
- **Signal**: per-conversation `read` + per-message `read_receipts`; unread is a SQL count.
- **Element X**: Matrix receipts (`m.read`, `m.read.private`) with threaded receipt handling.
- **Bungram**: `receipts(chat_id, user_id, last_read_seq)` — one row per member, not per message.
  Unread = `count(seq > last_read_seq)`. This scales to groups without a fan-out write per message.

### 2.8 1:1 E2EE

- **Signal**: X3DH (identity key + signed pre-key + one-time pre-key) → Double Ratchet with
  header keys, skipped-message keys (max 1000), and a 30-day session expiry. libsignal 0.105.4
  (`gradle/libs.versions.toml:31`), Rust core, `keyvalue/SignalStore.kt` for key storage.
- **Telegram**: secret chats use a Diffie-Hellman handshake (`messages.requestEncryption`), 64-bit
  key fingerprint rendered as 4 emoji (`EncryptionKeyEmojifier`), re-key every 100 messages /
  1 week (`SecretChatHelper`). Normal chats are **not** E2EE (client–server encryption only).
- **Element X**: Olm for 1:1, Megolm for groups; the Rust SDK handles everything.
- **Bungram**: `crypto/Crypto.kt` + `crypto/DoubleRatchet.kt` — X25519 (BouncyCastle), HKDF-SHA256,
  AES-256-GCM, 1000 skipped keys, per-message header `(dh_pub, pn, n)`. Own implementation because
  libsignal is GPL-3.0 and cannot ship in a closed app.

### 2.9 Group E2EE

- **Signal**: Sender Keys — each member distributes a chain key + signing key via 1:1 sessions;
  rotation on member removal; `SenderKeyUtil`, `groups/`.
- **Telegram**: no group E2EE at all (groups are server-side encrypted).
- **Element X**: Megolm (rotation every 100 messages / 1 week) → migrating to MLS.
- **Bungram**: Sender Keys per `(chat_id, epoch)`, distribution message encrypted per device,
  epoch bump on membership change, signing key to prevent member forgery. `crypto/SenderKeys`
  (to build; the ratchet primitives already exist).

### 2.10 Key verification

- **Telegram**: 4-emoji fingerprint in secret chats.
- **Signal**: 60-digit safety number + QR; `SafetyNumberChangeDialog`.
- **Element X**: cross-signing (master/self-signing/user-signing keys) + emoji SAS.
- **Bungram**: SHA-256 over both identity keys → 12 groups of 5 digits + 8 emoji; QR scan
  (`zxing`) for in-person. Store `verified_at` per (user, device).

### 2.11 Media pipeline

- **Telegram**: `FileLoader` keeps two queues (`uploadOperationQueue`, `uploadSmallOperationQueue`)
  and a `ConcurrentHashMap` keyed by path; parallel part upload, DC switching on failure,
  progressive JPEG + `blurhash`-style placeholder, `AnimatedFileDrawable` for streaming video.
- **Signal**: `AttachmentCompressionJob` → `AttachmentUploadJob` (resumable, 3 retries) →
  `AttachmentDownloadJob`; AES-CBC + HMAC per attachment, digest verified before decrypt.
- **Element X**: Rust SDK uploads with a media store; thumbnails generated client-side.
- **Bungram**: pick → downscale to 1600 px / JPEG 82 → strip EXIF → encrypt (AES-GCM, key wrapped
  per device) → resumable PUT (8 MiB chunks) → store `media_key`, `sha256`, `blurHash`.
  `media/MediaPipeline.kt` (to build). Rule: **never** send an original; always send a thumb
  alongside so the bubble paints in <100 ms.

### 2.12 Voice messages

- Telegram: `MediaRecorder` + `AudioRecorder` with waveform sampling, `MediaPlayer` with
  `setAudioStreamType`, playback speed 1×/1.5×/2×.
- Signal: `audio/` + `AudioNotePlayer`, proximity sensor to switch to earpiece.
- Bungram: `MediaRecorder` AAC-LC 32 kbps mono, amplitude sampled every 50 ms → 40-bar waveform,
  `ExoPlayer` for playback, audio focus + `AudioAttributes.USAGE_MEDIA`.

### 2.13 1:1 calls

- Telegram: `VoIPService` (10 k lines) — own signaling over MTProto (`phone.requestCall`),
  WebRTC `PeerConnection` with `VideoCapturerDevice`, `TelegramConnectionService` for the system
  call UI, `CallNotificationSoundProvider` for ringtones.
- Signal: `WebRtcCallService` + `CallManager` + ringrtc; signaling over the Signal socket;
  `ConnectionService` integration for Android Auto / Bluetooth.
- Element X: Element Call (LiveKit) inside a WebView/Activity, plus native PiP.
- Bungram: `CallService` (foreground, `microphone|camera`) + `PeerConnectionFactory`, signaling
  messages `call.offer / answer / ice / end` over the same WS, `ConnectionService` for the system
  UI, `USE_FULL_SCREEN_INTENT` for incoming. STUN/TURN via `IceServers` from `GET /calls/ice`.

### 2.14 Group calls

- Telegram: SFU (`GroupCall`) with `ConferenceCall` for the local mix.
- Signal: SFU via ringrtc; `GroupCallRing`.
- Element X: LiveKit SFU, up to 100+ participants.
- Bungram: mesh for ≤4 participants (v1), LiveKit SFU behind a flag for larger rooms. Mesh dies
  at 5 participants on mobile uplink — plan the SFU from day one in the signaling schema.

### 2.15 Push

- Telegram: FCM data-only, then the client fetches the update by `pts`; also keeps a socket via
  foreground service.
- Signal: FCM **data-only**, payload contains only a sender id + timestamp; the client fetches and
  decrypts. `gcm/FcmFetchForegroundService.kt`.
- Element X: UnifiedPush / FCM with `EventIncoming` payload, then `/sync`.
- Bungram: data-only FCM `{type, chat_id, seq}` — never message text. On receive: wake
  `SyncService`, `GET /sync?since=cursor`, decrypt, notify. This is what makes "content-free push"
  auditable.

### 2.16 Notification UX

- Telegram: `NotificationCenter` groups by chat, `NotificationGroup`, inline reply, avatar-as-large-icon.
- Signal: `NotificationChannels`, `MessageNotifier`, bundled summaries, direct-reply actions.
- Bungram: 3 channels (messages, calls, stories), `Person`-based bubbles on API 31+,
  `RemoteInput` reply, `MessagingStyle`, badge count = sum of unread.

### 2.17 Presence & typing

- Telegram: `updateUserStatus`, typing via `messages.setTyping` with a 5 s TTL.
- Signal: typing messages are ephemeral, 4 s TTL, no server state.
- Bungram: WS `presence` + `typing` events with TTL; never persist typing; presence is
  coarse (online / last seen) and respects a privacy flag.

### 2.18 Stories / Status

- Telegram: `StoriesController` + `ui/Stories/*` — 24 h TTL, close friends, reactions, view counts,
  server-side expiry.
- Signal: `ExpiringStoriesManager`, stories are just messages with a 24 h lifetime.
- Element X: no stories (uses rooms).
- Bungram: `stories` table with `expires_at`, 2-column staggered grid (matches the reference UI),
  per-viewer `viewed` flag, 24 h sweep job. This is the screen the mockup shows — see
  `feature/status/StatusScreen.kt`.

### 2.19 Search

- Telegram: FTS4 tables + `messages.search` server-side, with `peer`, `from`, `date` filters.
- Signal: `msgs_fts` virtual table (FTS5) with triggers.
- Element X: Rust SDK search over the local store.
- Bungram: FTS4 on `messages(text)` + `LIKE` fallback; server `/search?q=` for history the device
  does not hold. Debounce 250 ms, cancel in-flight query on new keystroke.

### 2.20 Contacts discovery

- Telegram: `contacts.resolveUsername`, phone-number hashing for "find friends".
- Signal: SGX-based contact discovery (`/v1/cds`) — the server never sees raw numbers.
- Element X: user directory search, no contact upload.
- Bungram: v1 uploads SHA-256(number + pepper) and matches server-side; document the privacy
  trade-off explicitly. A real CDS (enclave) is a v3 item.

### 2.21 Groups, channels, permissions

- Telegram: `Chat` + `Channel` with admin rights bitmask, `ChatRights`, slow mode, join requests.
- Signal: `groups/v2` — GroupV2 protobuf with signed membership changes (each change is a signed
  message in the group log).
- Element X: room power levels (PL 0–100), roles & permissions module.
- Bungram: `chat_members(role, joined_at)` with roles OWNER/ADMIN/MEMBER; permission checks in one
  place (`Permissions.kt`) on both client and server. Do not scatter `if (isAdmin)`.

### 2.22 Disappearing messages

- Telegram: `ttl` per message + `messages.setTTL`, periodic sweep.
- Signal: `ExpiringMessageManager` + `messageprocessingalarm` — a scheduled alarm, not a timer.
- Bungram: `expires_at` column + WorkManager sweep every 15 min + on-open sweep. Use the alarm
  pattern: a timer dies with the process, a persisted expiry does not.

### 2.23 Backup & restore

- Telegram: cloud, nothing to back up.
- Signal: `backup/` — a streaming protobuf archive (`BackupFrameOutputStream`) with a 30-digit
  recovery key; local + remote.
- Element X: key backup (Megolm session export) + `securebackup` with recovery key.
- Bungram: server-side encrypted blob (client-side key from a 24-word recovery phrase), plus
  `GET /backup` for a new device. Because the server stores ciphertext, restore is a download,
  not a re-sync of plaintext.

### 2.24 Abuse, spam, rate limits

- Telegram: server-side spam checks, `ReportSpam`, anti-flood waits.
- Signal: sealed sender + `RateLimitUtil`, spam classifier on the server.
- Element X: moderation tools, report room, ignore list.
- Bungram: token-bucket per user/IP (`RateLimiter.kt`), block/report endpoints, per-chat send
  throttling (20 msg / 10 s), and a `reported` flag for review.

### 2.25 Performance

- Telegram: custom `RecyclerView` with `ChatMessageCell` pooling, `ImageLoader` with 3-level cache
  (memory/disk/network), `AnimatedFileDrawable` for streaming.
- Signal: `Epoxy`/`ConcatAdapter`, Glide, baseline profiles.
- Element X: Compose + `LazyColumn` with stable keys, Rust SDK off the main thread, baseline profiles.
- Bungram: stable `key = message.id`, `contentType` per bubble type, Coil with a 25 % memory cache,
  `derivedStateOf` for the "scroll to bottom" button, baseline profile for cold start. Target:
  <1.2 s cold start, 60 fps scroll on a 3-year-old mid-range device.

### 2.26 Design system & theming

- Telegram: hand-written `Theme` with 100+ color keys, per-chat wallpapers.
- Signal: `color/` + dynamic color support.
- Element X: Compound design system (`libraries/designsystem`), tokens, light/dark.
- Bungram: `core/design` — one token file (`Color.kt`), one type scale (`Type.kt`, Inter),
  one component library (`Components.kt`). The reference UI is light-only; dark is a token swap.

### 2.27 Accessibility & RTL

- All three support TalkBack labels, 48 dp targets, `supportsRtl`.
- Bungram: every icon has `contentDescription`, bubbles use `semantics { }` with sender + time,
  `LocalLayoutDirection` respected (no hard-coded `left/right` — use `start/end`).

### 2.28 Build & modularisation

- Telegram: one giant module, no Compose, Gradle + NDK.
- Signal: 63 modules, version catalog, convention plugins.
- Element X: ~70 modules, convention plugins, `build-logic/`, detekt, screenshots.
- Bungram: start with one module; split into `:core:design`, `:core:data`, `:feature:*` when the
  build exceeds ~90 s. Add a version catalog from day one (already `libs.versions.toml`-ready).

### 2.29 Testing

- Telegram: almost none (manual QA).
- Signal: unit + instrumentation, screenshot tests for critical screens.
- Element X: Maestro flows, screenshot tests (Paparazzi/Roborazzi), unit tests per presenter.
- Bungram: unit tests for crypto vectors and the sync reducer; Compose UI tests for the 3 screens;
  a `DemoData` fixture that renders the reference screens for screenshot diffing.

### 2.30 Telemetry & crash reporting

- All three: crash reporting + opt-in analytics, no message content.
- Bungram: crash-only by default, opt-in analytics, never log message bodies or keys; redact
  phone numbers in logs.

---

## 3. What to copy, and what would sink the project

| Temptation | Reality | Decision |
|---|---|---|
| Reimplement MTProto | Needs DC infrastructure, salt management, CDN, years of edge cases | **No.** REST/WS + protobuf later |
| Ship libsignal | GPL-3.0 — incompatible with a closed app | **No.** Own X3DH/ratchet (already written) |
| Copy Telegram UI code | 100 % Views, GPL-2.0, 2 k-file coupling | **No.** Rebuild in Compose from the mockup |
| Copy Element's module graph | ~70 modules before the first feature ships | **No.** One module, split later |
| Server-side plaintext | Kills the product's only real differentiator | **No.** Opaque payloads, keys on device |
| Per-message receipts | Write amplification in groups | **No.** Per-member `last_read_seq` |
| Mesh group calls only | Dies at 5 participants | **Plan SFU in the schema now** |

---

## 4. Bungram's stack, and why

| Layer | Choice | Why |
|---|---|---|
| UI | Jetpack Compose + Material 3 | Only way to hit the mockup's spacing/shape fidelity fast |
| State | ViewModel + `StateFlow` + immutable `UiState` | Testable, no event-bus spaghetti |
| DI | Manual `AppGraph` | Hilt costs build time; swap later behind an interface |
| Local | Room + SQLCipher | Boring, fast, migration-friendly |
| Remote | Ktor client (OkHttp engine) + kotlinx.serialization | Same stack as the server |
| Realtime | WebSocket + cursor sync | Simpler than MTProto, sufficient for v1 |
| Crypto | BouncyCastle X25519/Ed25519 + AES-GCM | Permissive licence, auditable |
| Server | Ktor 3 + JWT + in-memory store (Postgres adapter next) | Kotlin end-to-end |
| Push | FCM data-only | Content-free, auditable |
| Calls | WebRTC + Ktor signaling → LiveKit | Mesh now, SFU when needed |

---

## 5. Delivery plan

| Phase | Scope | Exit criteria |
|---|---|---|
| **P0 (done)** | Design tokens, 5 screens, bottom bar, attachment sheet, demo data | Screens render 1:1 with the mockup |
| **P1** | Auth, device registration, cursor sync, text messages, receipts | Two emulators exchange messages through the server |
| **P2** | Media pipeline, voice notes, notifications, search | Photo + voice note round-trip, notification reply works |
| **P3** | E2EE 1:1 (X3DH + ratchet), safety numbers, disappearing messages | Crypto test vectors pass; MITM test fails to decrypt |
| **P4** | Groups, sender keys, roles/permissions | 5-member group with epoch rotation on removal |
| **P5** | Calls (1:1 → SFU), stories/status, backup | Call connects on LTE; restore on a fresh install |
| **P6** | Hardening: rate limits, abuse, a11y, baseline profiles, screenshot tests | 60 fps scroll, <1.2 s cold start, a11y scan clean |

---

## 6. Evidence appendix

```
rea inspect-android-package telegram.apk
  package      org.telegram.messenger.web
  version      13.1.1 (72379)
  sdk          23 → 36
  classes      16,432
  resources    7,531
  permissions  74  (incl. MANAGE_OWN_CALLS, FOREGROUND_SERVICE_PHONE_CALL,
                     READ_CALL_LOG, USE_FULL_SCREEN_INTENT, 12 launcher badge perms)
  evidence_id  ev_4303e3f0b88cb04658942df5c16cf0cb68bfd9896ce8aca433d3f1960a9cd595

rea inspect-android-package signal.apk
  package      org.thoughtcrime.securesms
  version      8.33.2 (177201)
  sdk          23 → 36
  classes      22,008
  resources    4,140
  permissions  77
  evidence_id  ev_0e8dce820bd452516779bbd9bc223f48ad0e7642ad932d462a462a02838863a0

rea inspect-android-class telegram.apk org.telegram.messenger.SecretChatHelper
  methods      101
  fields       10
  highlights   performSendEncryptedRequest (3 overloads), processUpdateEncryption,
               requestNewSecretChatKey, acceptSecretChat
  evidence_id  ev_227b5b74a26611141812af81e677a29b72b61748ece7d4bb1664b33443890c3b
```

Source-level anchors used above:

| Claim | File |
|---|---|
| Telegram `pts` gap repair | `TMessagesProj/.../messenger/MessagesController.java:8222` |
| Telegram message table | `TMessagesProj/.../messenger/MessagesStorage.java:551` |
| Telegram send request | `TMessagesProj/.../messenger/SendMessagesHelper.java:8006` |
| Telegram upload queues | `TMessagesProj/.../messenger/FileLoader.java:209` |
| Telegram secret chats | `TMessagesProj/.../messenger/SecretChatHelper.java` |
| Telegram calls | `TMessagesProj/.../messenger/voip/VoIPService.java`, `TelegramConnectionService.java` |
| Telegram stories | `TMessagesProj/.../ui/Stories/` (32 files) |
| Signal crypto store | `app/.../keyvalue/SignalStore.kt` |
| Signal conversation repo | `app/.../conversation/v2/ConversationRepository.kt` |
| Signal call UI | `app/.../calls/new/NewCallActivity.kt` |
| Signal push | `app/.../gcm/FcmFetchForegroundService.kt` |
| Signal backup | `app/.../backup/BackupFrameOutputStream.java` |
| Signal libsignal version | `gradle/libs.versions.toml:31` → `0.105.4` |
| Element X timeline | `features/messages/impl/.../timeline/TimelinePresenter.kt` |
| Element X calls | `features/callnative/impl/.../ElementCallControllers.kt` |
| Element X Matrix SDK | `gradle/libs.versions.toml:185` → `sdk-android:26.11.01-nightly` |

**Limits of this analysis.** APK inspection is static: it proves what is declared and what code
exists, never what runs. Telegram's protocol layer lives largely in native code and in
`tgnet/tl/TLRPC.java` (generated), so behaviour claims about MTProto come from source reading, not
from execution. Element X's core is Rust (`matrix-rust-sdk`), so its sync behaviour is opaque to
JADX by design — the Kotlin layer only shows the presentation contract.
