# Capability inventory — what the three apps actually contain

Generated from the pinned clones in `research/repos/` (Telegram `3de37c8`, Signal `515db93`,
Element X `9aef471f`) plus the APK evidence in `docs/05-DEEP-DIVE.md`. File counts are real
(`find … | wc -l`), not estimates.

---

## 1. Feature parity matrix (what Bungram must eventually cover)

Legend: **TG** Telegram · **SG** Signal · **EX** Element X · **Bungram** = current repo state
(✅ implemented · 🟡 scaffolded · ⬜ not started)

| # | Capability | TG | SG | EX | Bungram |
|---|---|---|----|----|---------|
| 1 | Phone OTP registration | ✅ | ✅ | ⬜ (password/OIDC) | 🟡 server route |
| 2 | Username / handle | ✅ | ✅ | ✅ | ⬜ |
| 3 | Profile (name, bio, avatar) | ✅ | ✅ | ✅ | 🟡 UI only |
| 4 | Multi-account | ✅ 4 slots | ⬜ | ✅ | ⬜ |
| 5 | Linked devices / QR login | ✅ | ✅ | ✅ | ⬜ |
| 6 | 1:1 text chat | ✅ | ✅ | ✅ | ✅ |
| 7 | Group chat | ✅ 200k | ✅ 1k | ✅ | ⬜ |
| 8 | Channels / broadcast | ✅ | ⬜ | ✅ (rooms) | ⬜ |
| 9 | Message edit / delete | ✅ | ✅ | ✅ | ⬜ |
| 10 | Reply / quote | ✅ | ✅ | ✅ | 🟡 model only |
| 11 | Forward | ✅ | ✅ | ✅ | ⬜ |
| 12 | Reactions | ✅ | ✅ | ✅ | 🟡 model only |
| 13 | Threads / topics | ✅ | ⬜ | ✅ | ⬜ |
| 14 | Pinned messages | ✅ | ✅ | ✅ | ⬜ |
| 15 | Scheduled messages | ✅ | ✅ | ⬜ | ⬜ |
| 16 | Disappearing messages | ✅ | ✅ | ✅ | 🟡 model only |
| 17 | Read receipts | ✅ | ✅ | ✅ | 🟡 UI ticks |
| 18 | Typing indicator | ✅ | ✅ | ✅ | 🟡 server event |
| 19 | Presence / last seen | ✅ | ✅ | ✅ | 🟡 server event |
| 20 | Photo / video send | ✅ | ✅ | ✅ | ⬜ |
| 21 | File send (any type) | ✅ | ✅ | ✅ | 🟡 UI card only |
| 22 | Voice notes + waveform | ✅ | ✅ | ✅ | ⬜ |
| 23 | Audio / video playback | ✅ | ✅ | ✅ | ⬜ |
| 24 | Stickers / GIF | ✅ | ✅ | ✅ | ⬜ |
| 25 | Emoji / custom emoji | ✅ | ✅ | ✅ | ⬜ |
| 26 | Location sharing (live) | ✅ | ✅ | ✅ | ⬜ |
| 27 | Contact card | ✅ | ✅ | ✅ | ⬜ |
| 28 | Polls | ✅ | ⬜ | ✅ | ⬜ |
| 29 | 1:1 voice call | ✅ | ✅ | ✅ | 🟡 service stub |
| 30 | 1:1 video call | ✅ | ✅ | ✅ | 🟡 service stub |
| 31 | Group call | ✅ | ✅ | ✅ | ⬜ |
| 32 | Screen sharing | ✅ | ⬜ | ✅ | ⬜ |
| 33 | Call links | ✅ | ✅ | ✅ | ⬜ |
| 34 | Stories / Status | ✅ | ✅ | ⬜ | ✅ UI, ⬜ backend |
| 35 | Story reactions / views | ✅ | ✅ | — | ⬜ |
| 36 | Search messages | ✅ | ✅ | ✅ | 🟡 server route |
| 37 | Global search | ✅ | ✅ | ✅ | ⬜ |
| 38 | In-chat media gallery | ✅ | ✅ | ✅ | ⬜ |
| 39 | Pinning / archiving chats | ✅ | ✅ | ✅ | 🟡 UI only |
| 40 | Mute / notification prefs | ✅ | ✅ | ✅ | ⬜ |
| 41 | Folders / chat filters | ✅ | ⬜ | ✅ (spaces) | ⬜ |
| 42 | Contact discovery | ✅ | ✅ (SGX) | ⬜ | ⬜ |
| 43 | Block / report | ✅ | ✅ | ✅ | 🟡 server route |
| 44 | Admin roles / permissions | ✅ | ✅ | ✅ | ⬜ |
| 45 | Invite links | ✅ | ✅ | ✅ | ⬜ |
| 46 | E2EE 1:1 | 🟡 secret chats | ✅ | ✅ | 🟡 primitives |
| 47 | E2EE groups | ⬜ | ✅ | ✅ | ⬜ |
| 48 | Safety numbers / verification | ✅ | ✅ | ✅ | ⬜ |
| 49 | Sealed sender | ⬜ | ✅ | ⬜ | ⬜ |
| 50 | Encrypted backup | ✅ (cloud) | ✅ | ✅ | ⬜ |
| 51 | Local DB encryption | ✅ | ✅ | ✅ | 🟡 Room, no SQLCipher |
| 52 | Push notifications | ✅ | ✅ | ✅ | 🟡 data-only design |
| 53 | Inline reply from notification | ✅ | ✅ | ✅ | ⬜ |
| 54 | Deep links | ✅ | ✅ | ✅ | 🟡 manifest only |
| 55 | Themes / dark mode | ✅ | ✅ | ✅ | ⬜ (light only) |
| 56 | Custom wallpapers | ✅ | ✅ | ⬜ | ⬜ |
| 57 | Accessibility (TalkBack) | 🟡 | ✅ | ✅ | 🟡 partial labels |
| 58 | RTL | ✅ | ✅ | ✅ | 🟡 `supportsRtl` |
| 59 | Translation | ✅ | ⬜ | 🟡 | ⬜ |
| 60 | Bots / integrations | ✅ | ⬜ | ✅ (widgets) | ⬜ |

**Reading of the matrix:** Bungram's UI is ahead of its backend, which is the correct order for a
design-driven build — but 6 of the 12 "✅" rows are presentation-only. The next real milestone is
rows 6, 17, 20, 22, 36 and 52 wired end-to-end through the server.

---

## 2. Element X — 46 feature modules

Element X is the only one of the three built as feature modules; the module list *is* the feature
list. Each module has `api/` (contract) and `impl/` (UI + presenter) — a pattern worth copying at
the package level.

| Module | Responsibility |
|---|---|
| `analytics` | opt-in analytics, event definitions |
| `announcement` | server-driven announcements |
| `cachecleaner` | cache size + purge UI |
| `call` / `callnative` | Element Call (LiveKit) UI, native WebRTC controllers |
| `contentscanner` | media safety scanning |
| `createroom` | create room flow |
| `deactivation` | account deactivation |
| `enterprise` | enterprise SSO / policy |
| `forward` | forward-to-room picker |
| `ftue` | first-time user experience |
| `home` | home shell + navigation |
| `invite` / `invitepeople` | invite by link / by user |
| `joinroom` / `knockrequests` | join, knock, approve |
| `leaveroom` | leave + cleanup |
| `licenses` | OSS licences |
| `linknewdevice` | QR device linking |
| `location` | static + live location |
| `lockscreen` | biometric/PIN app lock |
| `login` / `logout` / `signedout` | auth lifecycle |
| `messages` | timeline, composer, attachments, voice notes |
| `migration` | data migration between versions |
| `networkmonitor` | connectivity banner |
| `poll` | poll create + vote |
| `preferences` | settings |
| `rageshake` | shake-to-report logs |
| `reportroom` | abuse reporting |
| `rolesandpermissions` | power levels UI |
| `roomaliasresolver` | alias → room id |
| `roomcall` | call within a room |
| `roomdetails` / `roomdetailsedit` | room info + edit |
| `roomdirectory` | public room directory |
| `roommembermoderation` | kick/ban |
| `securebackup` | key backup + recovery |
| `securityandprivacy` | privacy settings |
| `share` | share sheet target |
| `space` | Spaces (room hierarchy) |
| `startchat` | new DM |
| `userprofile` | user profile sheet |
| `verifysession` | device verification (SAS) |
| `viewfolder` | saved views/filters |

Libraries (50): `designsystem` (Compound), `matrix` (Rust SDK wrapper), `eventformatter`,
`textcomposer`, `mediapicker`, `permissions`, `push`, `pushproviders`, `network`, `dateformatter`,
`rustsdk`, `featureflag`, `session-storage`, `encrypted-db`, …

---

## 3. Telegram — screen inventory

2,230 Java files, 1,992 of them under `TMessagesProj/src/main/java/org/telegram`. `ui/` alone has
235 entries — each top-level file is a screen or a major view.

| Package | Contents |
|---|---|
| `messenger/` | 171 classes: `MessagesController` (11 k lines), `MessagesStorage` (20 k), `SendMessagesHelper`, `FileLoader`, `MediaController`, `NotificationCenter`, `SecretChatHelper`, `ContactsController`, `MediaDataController` |
| `tgnet/` | MTProto: `ConnectionsManager`, `TLObject`, `TLRPC` (generated schema), `FileLoadOperation`, `Timer` |
| `ui/Stories/` | 32 files — story cells, viewers, reactions, close friends |
| `ui/Cells/` | 300+ chat cell types (message, poll, call, invoice, gift…) |
| `ui/Components/` | 400+ custom views (`AnimatedEmojiDrawable`, `RecyclerListView`, `AvatarDrawable`) |
| `ui/Business/`, `ui/Stars/`, `ui/Gifts/`, `ui/Wallet/`, `ui/TON/` | monetisation surfaces |
| `ui/Charts/` | statistics charts for channels |
| `ui/Adapters/` | 100+ adapters |
| `messenger/voip/` | `VoIPService`, `TelegramConnectionService`, `ConferenceCall`, `VideoCapturerDevice` |
| `SQLite/` | custom SQLite wrapper + FTS |

The takeaway: Telegram's "features" are not modules — they are screens wired directly into
`MessagesController` singletons. That architecture is why a fork cannot remove a feature cleanly.
Do not imitate it.

---

## 4. Signal — package sizes (top 20)

| Package | Files | What lives there |
|---|---|---|
| `components` | 719 | reusable UI: bubbles, dialogs, sheets, avatars |
| `database` | 329 | one `*Table.kt` per entity + migrations |
| `conversation` | 244 | conversation screen, input, media, reactions |
| `jobs` | 197 | every background operation as a persisted `Job` |
| `util` | 181 | extensions, concurrency, bitmaps |
| `stories` | 146 | stories: create, view, expire, distribute |
| `backup` | 113 | streaming protobuf backup format |
| `groups` | 113 | GroupV2, membership proofs, sender keys |
| `payments` | 112 | MobileCoin wallet |
| `migrations` | 90 | schema + data migrations |
| `service` | 86 | foreground services, expiring message manager |
| `contacts` | 65 | contact sync + discovery |
| `jobmanager` | 65 | job queue, constraints, retries |
| `mediasend` | 53 | pick → compress → review → send |
| `registration` | 52 | phone verification, PIN |
| `keyvalue` | 47 | `SignalStore` (identity, sessions, pre-keys) |
| `calls` | 38 | call UI + links + quality |
| `notifications` | 33 | channels, bundling, reply actions |
| `crypto` | 26 | `KeyStore`, `PreKeyUtil`, `SenderKeyUtil`, master cipher |
| `webrtc` | 17 | peer connection, audio routing |

Signal's `jobs/` + `jobmanager/` split (262 files) is the single best idea to steal: **every**
network side effect is a resumable, constrained job, never a coroutine tied to a screen.

---

## 5. What each app is actually good at

| | Strength worth studying | Weakness to avoid |
|---|---|---|
| **Telegram** | Media pipeline (`FileLoader` parallel parts, DC switching), story viewer, custom-drawn UI performance | God-object controllers, no module boundaries, GPL |
| **Signal** | Job-based reliability, backup format, group membership proofs, privacy defaults | 700-file `components` package, slow Compose migration |
| **Element X** | Module boundaries, Compose design system, Rust core, 22 permissions | Protocol complexity leaks into UX, heavy dependency graph |
| **Bungram** | Take: Signal's job discipline + Element's module discipline + the mockup's visual language | Avoid: MTProto, GPL code, 70-module upfront split |

---

## 6. Concrete numbers

| Metric | Telegram | Signal | Element X | Bungram (current) |
|---|---|---|---|---|
| Source files | 2,230 Java | 4,336 Kotlin/Java | 4,377 Kotlin | 40 Kotlin |
| APK classes | 16,432 | 22,008 | 16,431 | — |
| APK size | 68 MB | 110 MB | 137 MB | — |
| Permissions | 74 | 77 | 22 | 15 |
| minSdk | 23 | 23 | 24 | 26 |
| targetSdk | 36 | 36 | 37 | 35 |
| Gradle modules | 1 | 63 | ~96 (46 features + 50 libs) | 1 |
