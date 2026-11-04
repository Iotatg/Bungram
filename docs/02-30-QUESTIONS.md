# 36 questions — how it works, and how Bungram does it

The questions you asked, answered against the three reference codebases and mapped to concrete
Bungram code. Short answers first, then the implementation pointer.

---

### Foundations

**1. How does a message actually travel from one phone to another?**
Telegram: client → MTProto encrypted container → DC → recipient's socket, with `pts` counters so
a client can detect and repair gaps. Signal: client encrypts (Double Ratchet) → REST POST →
server stores ciphertext → push wakes recipient → recipient fetches by queue. Element: client
sends a Matrix event → homeserver fans out to room members → `/sync` delivers.
**Bungram:** `POST /v1/messages` with `clientId` (idempotency) → server appends → WS `message`
event to online members → offline members get it from `GET /v1/sync?cursor=`. See
`data/remote/BungramApi.kt`, `server/routes/Routes.kt`.

**2. Why do the references avoid "just use Firebase Realtime DB"?**
Because they need per-device encryption state, ordering guarantees, gap detection, and the
ability to run their own infrastructure. A chat product's hard problems are ordering, dedupe,
and key state — not transport.
**Bungram:** same reasoning; REST+WS with explicit cursor and `clientId` dedupe.

**3. How is message ordering guaranteed when the network reorders packets?**
Telegram uses per-dialog `pts` and refuses to apply an update with a gap until it fetches the
missing range. Signal uses per-conversation envelope counters plus a server GUID. Bungram uses a
monotonic server timestamp + client `clientId`; the UI sorts by `(createdAtEpochMs, id)` and the
sync cursor is the last applied timestamp.

**4. How does a client know it missed messages while offline?**
It stores a cursor. Telegram: `pts`. Matrix: `since` token. Bungram: `nextCursor` returned by
`/v1/sync`, persisted in DataStore, replayed on reconnect (`sync/SyncEngine.kt`).

**5. How do you avoid duplicate messages when a send is retried?**
Every send carries `clientId` (UUID). The server keeps `(senderId, clientId)` unique; a retry
returns the existing message instead of inserting a second one.

---

### Encryption

**6. What is X3DH and why is it needed?**
Extended Triple Diffie-Hellman: lets Alice start an encrypted session with Bob **while Bob is
offline**, using his published pre-keys. It combines 3–4 DH outputs into one shared secret.
Implemented in `crypto/Crypto.kt` (`X3dh.initiate` / `X3dh.respond`).

**7. What is the Double Ratchet and what does it buy us?**
After X3DH you have one shared key. The ratchet derives a new key per message (forward secrecy)
and performs a DH exchange whenever the peer sends a new ratchet key (post-compromise security).
`crypto/DoubleRatchet.kt`.

**8. Why do groups not use pairwise encryption for every member?**
An N-member group would need N ciphertexts per message. Signal solved it with **Sender Keys**:
each sender has one chain, distributed once over pairwise sessions; receivers derive the same
message keys. `SenderKey` in `DoubleRatchet.kt`. Telegram simply doesn't E2EE cloud groups.

**9. How do you handle a message that arrives out of order?**
Store skipped message keys (bounded). Bungram keeps up to 1000 (`MAX_SKIP`) keyed by
`ratchetKey:counter`, so a late packet still decrypts and a replay does not.

**10. Where do private keys live on the device?**
Signal uses the Android Keystore to wrap the database key; keys never leave the device.
Bungram: `TokenStore` uses `EncryptedSharedPreferences` (AES-256-GCM, hardware-backed master
key). Phase 2 moves identity keys into a Keystore-backed `KeyStore` entry.

**11. How do users verify they're talking to the right person?**
Safety numbers: hash both identity keys, render as digits, compare in person or by QR.
`IdentityKeyPair.safetyNumber()`.

**12. Can the server read messages?**
In Bungram, no: it stores ciphertext and routing metadata only. Telegram cloud chats are the
exception (server-readable by design); only Secret Chats are E2EE.

---

### Groups, channels, social

**13. How do large groups (10k+) stay performant?**
Telegram: server-side fan-out with per-user `pts`, member chunks, lazy history. Signal: cap
group size and use sender keys. Bungram: sender keys + server fan-out to online members only;
history is paginated (`before` + `limit`).

**14. What is the difference between a group and a channel?**
Group: everyone can post, membership visible. Channel: only admins post, unlimited subscribers,
read receipts hidden. `ChatKind.GROUP` vs `ChatKind.CHANNEL`.

**15. How do stories/status expire?**
TTL column + periodic sweeper; clients also hide expired items locally so a stale cache never
shows an old story. `Story.expiresAtEpochMs`, `Store.feedsFor()` filters by `now`.

**16. How are story viewers tracked?**
One row per (storyId, viewerId); the owner sees an aggregated count and list. Bungram:
`viewedStories` map → `POST /v1/status/{id}/view`.

**17. How do reactions stay consistent across devices?**
Store reactions as rows `(messageId, userId, emoji)` and aggregate on read; never store a
counter you can't recompute. `Reaction(emoji, count, mine)`.

---

### Media & calls

**18. How does a 2 GB video upload survive a subway tunnel?**
Resumable upload: chunked PUT with an upload session id, server keeps received ranges, client
retries only missing chunks. Telegram does this across DCs; Bungram plan in
`docs/03-ARCHITECTURE.md` §6.

**19. How are images made to load instantly in the list?**
Three layers: blurhash placeholder stored in the message row, thumbnail generated on upload,
full image on demand. Coil handles memory/disk cache in `AsyncImage`.

**20. How do calls work without a media server?**
WebRTC P2P with STUN; TURN relay only when NAT traversal fails. Signalling (SDP offer/answer,
ICE candidates) rides the existing WebSocket as `type:"call"`. `CallService`, `POST
/v1/calls/{chatId}/signal`.

**21. When do you need an SFU?**
As soon as a call has more than ~4 participants or needs server-side recording. Element uses
LiveKit; Signal uses RingRTC's SFU. Bungram phase 4.

**22. Why is the call service a foreground service?**
Android kills background work; an ongoing call must keep the mic/camera and show a persistent
notification. `service/CallService.kt` + `foregroundServiceType="microphone|camera"`.

---

### Sync, storage, performance

**23. Why cache-first instead of network-first?**
A chat list must render in <100 ms, offline, every time. UI observes Room; the network only
writes into Room. Bungram's repository exposes `StateFlow`, so screens never await IO.

**24. How do you paginate a chat with 200k messages?**
Keyset pagination (`WHERE createdAt < :before ORDER BY createdAt DESC LIMIT 50`), never OFFSET.
`GET /v1/chats/{id}/messages?before=&limit=`.

**25. How does search work when messages are encrypted?**
The server can't search ciphertext, so search is local: decrypt on ingest, index plaintext in
FTS4, query locally. Telegram can search server-side because cloud chats aren't E2EE.

**26. How do you keep the list smooth while images decode?**
Fixed item heights, `key` on every list item, `contentType` in LazyColumn, and Coil's
downsampling. Avoid `fillMaxSize` images inside scrolling rows without a size constraint.

**27. What causes the classic "message jumps" bug?**
Re-sorting by a local timestamp that differs from the server's, or regenerating keys on every
recomposition. Bungram uses server `createdAtEpochMs` and stable `id` keys.

---

### Notifications, lifecycle, platform

**28. Why do chat apps send an empty push?**
So Google/Apple never sees message content. The push only wakes the app; the client then syncs
ciphertext over its own connection. This is Signal's model and Bungram's plan.

**29. How do you avoid notification spam in a busy group?**
Group by chat, replace the summary notification, respect per-chat mute, and only alert for
mentions when muted. `Notifications.message()` + a summary notification in phase 3.

**30. What happens when the process is killed mid-upload?**
WorkManager persists the job; on restart the upload session id is reused and only missing
chunks are sent.

**31. How do you handle Android 13+ notification permission and Android 14 FGS types?**
Request `POST_NOTIFICATIONS` at first launch (done in `MainActivity`), and declare
`foregroundServiceType` for every FGS (done in the manifest).

**32. How do you support tablets and foldables?**
Window size classes: single pane <600dp, list-detail ≥600dp. The screens are already
state-hoisted, so a two-pane host is a layout change, not a rewrite.

---

### Product, trust, operations

**33. How do you stop spam and scams?**
Rate limits per account/IP (`RateLimiter`), report/block flow, server-side heuristics on message
rate and link patterns, and (later) a reputation score. Never rely on client-side checks.

**34. How do you do contact discovery without uploading the whole address book in plaintext?**
Hash each normalised phone with HMAC and upload only a truncated prefix; the server returns
matches, not the book. Signal goes further with private set intersection.

**35. How do you migrate a schema without breaking old clients?**
Versioned endpoints (`/v1`), additive DB migrations, and clients that ignore unknown JSON keys
(`ignoreUnknownKeys = true` in `Wire.json`).

**36. What is the minimum viable backend for 100k users?**
Postgres (messages, chats, users), Redis (presence, rate limits, fan-out queues), object storage
(media), one stateless Ktor service behind a load balancer, WS nodes with a Redis pub/sub bridge.
Bungram's `Store` is a drop-in seam for exactly this — see `docs/03-ARCHITECTURE.md` §8.

---

## Bonus: five questions worth asking before writing more code

1. Do we ship E2EE by default (Signal model) or opt-in (Telegram model)? It changes the entire
   server design — decide before the schema.
2. Is multi-device a v1 feature? Retrofitting per-device sessions is the most expensive change
   in any messenger.
3. Who owns media encryption keys, and can we rotate them without re-uploading?
4. What is our message retention policy, and does it satisfy local law?
5. Which single platform gets the first 1,000 users? Optimise that path, not the matrix.
