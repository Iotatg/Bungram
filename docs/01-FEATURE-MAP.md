# Bungram — feature map

> **Companion documents.** This is the one-page comparison table. The evidence-backed deep dive
> (with pinned commits, APK analysis and file/line anchors) is `05-DEEP-DIVE.md`; the full
> capability inventory and 60-row parity matrix is `06-CAPABILITY-INVENTORY.md`; the REA
> reproduction steps are `04-REA-WORKFLOW.md`.

How the three reference codebases solve each subsystem, and where the equivalent lives in
Bungram. This is the engineering map behind the UI build: every row is a subsystem you will
eventually need, the reference implementation worth studying for it, and the concrete Bungram
module that owns it.

> **Licensing note.** Telegram Android is GPL-2.0, Signal-Android is GPL-3.0, element-x-android is
> AGPL-3.0. Bungram is a **clean-room** implementation: we study behaviour and protocol shape
> (with REA, see `04-REA-WORKFLOW.md`), never copy source. Protocol facts are public specs
> (MTProto, Signal Protocol, Matrix) and are safe to implement from.

---

## 1. Subsystem comparison

| # | Subsystem | Telegram (`DrKLO/Telegram`) | Signal (`signalapp/Signal-Android`) | Element X (`element-hq/element-x-android`) | Bungram |
|---|---|---|---|---|---|
| 1 | Wire protocol | MTProto 2.0 over TCP/HTTP, hand-rolled TL schema, `TdLib`-style state machine | Signal Protocol over a REST + WebSocket service | Matrix client-server API (JSON over HTTP) + `/sync` | REST + JSON envelopes, WebSocket deltas (`data/remote/Dto.kt`, `sync/SyncEngine.kt`) |
| 2 | Local database | `SQLite` via `MessagesStorage` + custom ORM, huge denormalised tables | `SQLCipher` (encrypted SQLite) + `SignalDatabase`, ~40 tables | `SQLite` via Room + Rust SDK store | Room + SQLCipher plan (`data/local`), cache-first repository |
| 3 | Auth | Phone + SMS code, then MTProto auth key (DH) per DC | Phone + SMS/voice, then pre-key registration, PIN for reg-lock | Matrix login (password/SSO), cross-signing | Phone + OTP → JWT access/refresh (`routes/Auth`), device keys registered on verify |
| 4 | E2EE 1:1 | Secret Chats only: MTProto DH + re-keying; cloud chats are server-readable | X3DH + Double Ratchet, per-device sessions | Olm (X3DH + Double Ratchet variant) | `crypto/Crypto.kt` (X25519, X3DH, HKDF) + `crypto/DoubleRatchet.kt` |
| 5 | E2EE groups | Not E2EE (cloud groups); voice chats not E2EE | Sender Keys (one chain per sender), fanned out over pairwise sessions | Megolm (group ratchet, per-sender chain) | `SenderKey` in `DoubleRatchet.kt` |
| 6 | Multi-device | First-class: any number of devices, server-side sync of everything | Linked devices (max 5), per-device sessions, sync messages | Matrix cross-signing + device verification | `devices` table + per-device pre-key bundles, sync cursor |
| 7 | Message ordering / gaps | `pts`, `qts`, `seq` counters; explicit gap recovery (`updates.getDifference`) | Per-conversation envelope queue, `serverGuid` dedupe | Matrix `since` token + room timeline gaps | `sync?cursor=` timestamp cursor + client `clientId` idempotency key |
| 8 | Delivery states | sent → received → read, per-chat read markers | Per-message `sent/delivered/read` + expiration timer | Matrix receipts (`m.read`, `m.read.private`) | `DeliveryState` enum, optimistic `QUEUED → SENT → DELIVERED → READ` |
| 9 | Typing / presence | `updateUserTyping`, `updateUserStatus`, online = active socket | Typing messages + presence over socket, "last seen" privacy switch | Ephemeral events (`m.typing`), presence | WS `typing` / `presence` events, throttled to 1/3s |
| 10 | Media | DC-based upload (resumable, parallel CDN), server-side thumbnails | Attachment v2: CDN upload, encrypted blobs, digest-based dedupe | Matrix content repository (`mxc://`), encrypted media | `Attachment` model + `media/` pipeline (compress → encrypt → resumable PUT) |
| 11 | Voice notes | Opus/OGG, waveform peaks computed on device | AAC/Opus, waveform + playback speed | Voice broadcast | `MessageKind.VOICE` + Media3 playback, waveform from amplitude sampling |
| 12 | 1:1 calls | WebRTC, custom signalling over MTProto, P2P then relay | WebRTC + RingRTC (custom fork), TURN fallback | Element Call: MatrixRTC + LiveKit SFU | `service/CallService` + WS `call` signalling, WebRTC + TURN |
| 13 | Group calls | Voice chats (SFU, up to 200k listeners) | Group calls (RingRTC SFU) | LiveKit SFU, up to large rooms | Phase 4: SFU (LiveKit) with the same WS signalling |
| 14 | Stories / Status | Stories with privacy scopes, 24h TTL | Stories (24h), viewer list | Not native (via widgets) | `feature/status` + `Story` model, 24h TTL, per-viewer receipts |
| 15 | Reactions | Arbitrary emoji, paid custom packs | Fixed emoji set, per-reaction receipts | Matrix `m.reaction` | `Reaction` list, aggregated counts, WS fan-out |
| 16 | Replies / threads | Reply + quote + forum topics | Quote reply, no threads | Threads (MSC3440) | `replyToId` + `replyPreview`; threads in phase 5 |
| 17 | Edit / delete | Edit window + delete for everyone | Edit + delete-for-everyone with tombstones | Matrix `m.replace` / redaction | `editedAtEpochMs`, `isDeleted` tombstone, WS `edit`/`delete` |
| 18 | Disappearing messages | TTL per chat + secret-chat timer | Per-chat timer + per-message expiration | `m.room.retention` / disappearing messages | `isEphemeral` + `expiresAtEpochMs`, sweeper job on client + server |
| 19 | Search | Server-side FTS over cloud chats + local FTS | Local FTS5 only (E2EE) | Local + server (encrypted search) | Room FTS4 index on decrypted text, debounced queries |
| 20 | Push | FCM/APNs with `pts`-aware payloads, wake-up only | FCM wake-up + fetch (never message content) | Push + sliding sync | FCM wake-up only, then `sync?cursor=` |
| 21 | Contact discovery | Phone-book upload (hashed) + server matching | Private set intersection (SGX) | Matrix user directory / HS search | Hashed phone prefixes (HMAC-SHA256, 3-byte buckets) |
| 22 | Key verification | Visual fingerprint + QR | Safety numbers + QR | Cross-signing + emoji SAS | `safetyNumber()` (60 digits) + QR in phase 4 |
| 23 | Spam / abuse | Server-side ML + report flow + `antispam` | Sealed sender + rate limits + spam scoring | Per-room moderation, report API | `RateLimiter` + report endpoint + server-side heuristics |
| 24 | Bots / integrations | Bot API (HTTP), inline bots | None | Widgets, bots via Matrix | Phase 5: bot API tokens scoped per chat |
| 25 | Theming | 10+ themes, wallpapers, bubble styles | Light/dark only, dynamic colour | Compound design system, dynamic colour | Token layer (`core/design`) — one file to add a theme |
| 26 | Accessibility | Content descriptions, TalkBack tuned | Strong: contrast, touch targets | Strong (Compound) | Semantics + 48dp targets; `contentDescription` on every icon |
| 27 | Offline | Full offline cache, outbox queue | Full offline + outbox | Full offline (Rust store) | Repository cache + `SyncEngine` outbox |
| 28 | Backup | Cloud backup optional | Local encrypted backup file | Server-side key backup (Megolm) | Phase 4: encrypted backup to object storage, key derived from PIN |

---

## 2. What was actually built in this repo

| Area | Status | Files |
|---|---|---|
| Design system (colours, Inter type scale, components) | ✅ done | `core/design/*` |
| Message list, chat, status, calls, profile screens | ✅ done | `feature/**` |
| Attachment sheet, composer, delivery ticks, unread badges | ✅ done | `feature/chat/ChatScreen.kt` |
| Navigation + bottom bar with floating compose button | ✅ done | `core/nav`, `core/design/Components.kt` |
| Repository + optimistic send + demo seed | ✅ done | `data/repo`, `data/demo` |
| Typed REST client + DTO contract | ✅ done | `data/remote` |
| Realtime sync engine (WS, backoff, cursor) | ✅ done | `sync/SyncEngine.kt` |
| X3DH + Double Ratchet + Sender Keys | ✅ done (unit-testable core) | `crypto/*` |
| Foreground sync service, call service shell, boot receiver | ✅ done | `service/*` |
| Ktor server: auth, chats, messages, status, calls, WS hub | ✅ done, builds & runs | `/server` |
| Media pipeline (compress/encrypt/resumable upload) | 🟡 designed, stub | `docs/03-ARCHITECTURE.md` §6 |
| WebRTC media, SFU group calls | 🟡 phase 4 | `service/CallService.kt` |
| Room cache + SQLCipher | 🟡 phase 2 | `docs/03-ARCHITECTURE.md` §5 |
| Push (FCM) | 🟡 phase 3 | `docs/03-ARCHITECTURE.md` §7 |

---

## 3. Reading order for the reference repos

If you want to learn the *system* rather than the code, read in this order:

1. **Signal-Android → `libsignal`**: the cleanest E2EE reference. Start at `SessionBuilder`,
   `SessionCipher`, `PreKeyBundle`, then `SenderKeyDistributionMessage`. Our `crypto/` package
   mirrors that vocabulary on purpose.
2. **Telegram → `TdLib`**: the best reference for *sync at scale*. Read `updates.getDifference`,
   `pts/qts/seq`, and the DC migration logic. Our `sync?cursor=` is the simplified version.
3. **Element X → `matrix-rust-sdk`**: the best reference for *multi-device state*. Read sliding
   sync, cross-signing, and how the Rust SDK exposes state to Compose through `FFI`.
4. **rea** on top of all three: when a behaviour is unclear from source (obfuscated release
   builds, native crypto, push payloads), use REA to trace it and get evidence.
