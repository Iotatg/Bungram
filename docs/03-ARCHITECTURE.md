# Bungram — architecture

## 1. Shape of the system

```
┌───────────────────────────── Android (Kotlin, Compose) ─────────────────────────────┐
│  feature/            messages · chat · status · calls · profile                      │
│      │  StateFlow<UiState>                                                           │
│  data/repo           ChatRepository (cache-first, optimistic writes)                 │
│      ├── data/local  Room + SQLCipher  (phase 2)                                     │
│      ├── data/remote BungramApi (Ktor client)  ·  DTO contract                       │
│      └── data/demo   DemoData seed (what ships today)                                │
│  sync/SyncEngine     WebSocket + cursor + outbox + backoff                           │
│  crypto/             X25519 · X3DH · Double Ratchet · Sender Keys                    │
│  service/            SyncService (FGS dataSync) · CallService · BootReceiver         │
└──────────────────────────────────────────────────────────────────────────────────────┘
                                   │ HTTPS / WSS
┌──────────────────────────────────▼───────────────────────────────────────────────────┐
│  server/  Ktor 3 + Netty                                                             │
│   routes/      auth · chats · messages · sync · status · calls · ws                  │
│   store/       Store (in-memory, 1:1 with the Postgres schema below)                 │
│   realtime/    Hub (per-socket bounded outbox, trySend fan-out)                      │
│   security/    JwtService · OtpService · RateLimiter                                 │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

## 2. Client layers and rules

1. **UI never touches the network.** Screens collect `StateFlow<UiState>`; ViewModels call the
   repository; the repository owns cache + network.
2. **Optimistic writes.** `send()` inserts a `QUEUED` message immediately, then the sync layer
   advances `SENT → DELIVERED → READ`. The bubble never waits for a round-trip.
3. **Single source of truth.** In production, Room is the truth and the network writes into Room.
   The demo repository implements the same interface, so swapping is a one-line change in
   `AppGraph`.
4. **No business logic in composables.** `ChatScreen` is a pure function of `ChatUiState`.

## 3. Wire protocol

### REST

| Method | Path | Purpose |
|---|---|---|
| POST | `/v1/auth/otp` | request SMS code (rate-limited 5 / 10 min / phone) |
| POST | `/v1/auth/verify` | verify code, register device pre-keys, issue JWT |
| POST | `/v1/auth/refresh` | rotate access token |
| GET | `/v1/me` | current profile |
| GET | `/v1/chats` | conversation list |
| POST | `/v1/chats` | create direct chat |
| GET | `/v1/chats/{id}/messages?before=&limit=` | keyset pagination |
| POST | `/v1/messages` | send (idempotent on `clientId`) |
| POST | `/v1/chats/{id}/read` | clear unread + read receipts |
| POST | `/v1/chats/{id}/typing` | typing indicator |
| GET | `/v1/sync?cursor=` | delta sync |
| GET/POST | `/v1/status` | story feeds / publish |
| POST | `/v1/status/{id}/view` | mark viewed |
| POST | `/v1/calls/{chatId}/signal` | WebRTC SDP/ICE relay |
| GET | `/v1/healthz` | liveness |

All responses are `Envelope<T>`: `{ ok, data, error, serverTimeMs }`.

### WebSocket `/ws?token=<jwt>`

Client → server: `hello`, `typing`, `receipt`, `ping`.
Server → client: `sync`, `message`, `receipt`, `typing`, `presence`, `call`.

Backpressure: every socket has a `Channel(128)`; fan-out uses `trySend`, so a slow client can
never block a sender. Dropped events are recovered by the next cursor sync.

## 4. Data model (Postgres)

```sql
create table users (
  id            text primary key,
  phone         text unique not null,
  username      text unique,
  display_name  text not null,
  about         text default '',
  avatar_url    text,
  created_at    timestamptz default now()
);

create table devices (
  id            text primary key,
  user_id       text references users(id) on delete cascade,
  name          text,
  identity_key  bytea not null,
  signed_prekey bytea not null,
  prekey_sig    bytea not null,
  last_seen     timestamptz
);

create table one_time_prekeys (
  device_id     text references devices(id) on delete cascade,
  key_id        int,
  public_key    bytea not null,
  consumed      boolean default false,
  primary key (device_id, key_id)
);

create table chats (
  id            text primary key,
  kind          text not null,           -- DIRECT | GROUP | CHANNEL
  title         text,
  avatar_url    text,
  created_at    timestamptz default now()
);

create table chat_members (
  chat_id       text references chats(id) on delete cascade,
  user_id       text references users(id) on delete cascade,
  role          text default 'MEMBER',
  joined_at     timestamptz default now(),
  muted         boolean default false,
  primary key (chat_id, user_id)
);

create table messages (
  id            text primary key,
  chat_id       text references chats(id) on delete cascade,
  sender_id     text references users(id),
  sender_device text,
  kind          text not null default 'TEXT',
  ciphertext    bytea not null,          -- server never sees plaintext
  header        bytea,                   -- ratchet header / sender-key header
  attachment_id text,
  reply_to_id   text,
  created_at    timestamptz not null,
  edited_at     timestamptz,
  deleted       boolean default false,
  client_id     text,                    -- idempotency
  unique (sender_id, client_id)
);
create index on messages (chat_id, created_at desc);

create table receipts (
  message_id    text references messages(id) on delete cascade,
  user_id       text references users(id) on delete cascade,
  state         text not null,           -- DELIVERED | READ
  at            timestamptz default now(),
  primary key (message_id, user_id)
);

create table stories (
  id            text primary key,
  owner_id      text references users(id) on delete cascade,
  media_url     text,
  caption       text,
  created_at    timestamptz default now(),
  expires_at    timestamptz not null
);

create table story_views (
  story_id      text references stories(id) on delete cascade,
  viewer_id     text references users(id) on delete cascade,
  at            timestamptz default now(),
  primary key (story_id, viewer_id)
);
```

Partition `messages` by month once it passes ~50M rows; keep `(chat_id, created_at desc)` as the
clustered index so pagination stays a single index range scan.

## 5. Local cache (phase 2)

Room entities mirror the tables above minus ciphertext handling: messages are stored **already
decrypted** in a SQLCipher database, keyed by a Keystore-wrapped 256-bit key. FTS4 virtual table
on `messages(text)` for local search. The `SyncEngine` writes into Room inside a transaction and
emits a single invalidation per batch.

## 6. Media pipeline

```
pick → copy to app cache → probe (MediaMetadataRetriever)
     → compress (image: 1600px max, WebP q80; video: 720p H.264)
     → thumbnail (256px) + blurhash
     → AES-256-GCM encrypt with per-file key
     → resumable PUT (chunk 512 KB, uploadId persisted)
     → send message with attachmentId + wrapped mediaKey
```

Receiving: download range → decrypt → cache → decode. Media keys are wrapped per recipient
device, never uploaded in the clear.

## 7. Push

FCM carries **no content**: `{type:"wake", chatId}` only. The client wakes, opens the socket,
syncs from cursor, decrypts, then posts a local notification. This keeps Google out of the
message path and works identically for APNs.

## 8. Scaling path

| Users | Change |
|---|---|
| 0–10k | one Ktor node, Postgres, S3-compatible storage |
| 10k–100k | 2+ stateless API nodes, Redis for presence/rate-limit, WS nodes behind sticky LB, Redis pub/sub to bridge nodes |
| 100k–1M | partition `messages`, read replicas, fan-out workers via Kafka, CDN for media |
| 1M+ | per-region clusters, cell-based routing (Telegram's DC model), separate media service |

The `Store` class is the seam: replace it with repository interfaces backed by Exposed/Jooq and
nothing above it changes.

## 9. Security checklist

- TLS everywhere; cleartext allowed only for `10.0.2.2` in debug.
- JWT: short-lived access (1 h) + rotating refresh (30 d), audience + issuer checked.
- OTP: 5 attempts, 5 min TTL, per-phone rate limit, constant-time comparison.
- Media: encrypted at rest, signed URLs with short TTL.
- Logs: never log message bodies, tokens, or phone numbers in full.
- Backup: disabled (`allowBackup="false"`) until encrypted backup ships.
- Dependency hygiene: pin versions, run `gradle dependencyUpdates` monthly.

## 10. Roadmap

| Phase | Scope | Exit criteria |
|---|---|---|
| 1 (done) | UI 1:1, demo data, server skeleton, crypto core | app runs, server builds & serves auth/chats/messages |
| 2 | Room + SQLCipher, real sync, media upload, push | two devices exchange real messages offline-first |
| 3 | Calls (WebRTC), notifications, read receipts end-to-end | 1:1 voice/video call between two phones |
| 4 | Group calls (SFU), backup, key verification UI | 8-person call, restore from backup |
| 5 | Bots, channels, threads, moderation tools | public channel with 1k subscribers |
