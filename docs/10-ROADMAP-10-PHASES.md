# Bungram — 10-Phase Roadmap (Telegram-scale)

> Hindi (Roman) me likha hai, kyunki tumne Hindi me baat karne ko kaha.
> Har phase ka **scope**, **kya banega**, aur **kaise verify hoga** — teeno likha hai.
> Phase 1 aur 2 **done** hain. Baaki 8 isi document ke hisaab se chalenge.

---

## 0. Poora naksha ek nazar me

| # | Phase | Bada sawaal | Status |
|---|---|---|---|
| 1 | Foundation | App asli server se baat kare, message na khoye | ✅ done (v0.2.0) |
| 2 | Media | Photo / video / file / voice — bhejo, dikhao, seek karo | ✅ done (v0.3.0) |
| 3 | Groups + Channels | 200 member group, broadcast channel, admin rights | ⏳ next |
| 4 | E2EE | Secret chat — server ciphertext bhi na padh sake | ⏳ |
| 5 | Push | App band ho to bhi notification aaye | ⏳ |
| 6 | Calls | 1:1 voice/video, group call | ⏳ |
| 7 | Stories + Reactions | Status, reply, forward, pin, star, search | ⏳ |
| 8 | Scale | Postgres, sharding, object storage, multi-node WS | ⏳ |
| 9 | Multi-device | 4 device, session list, per-device key, device revoke | ⏳ |
| 10 | Trust & Ops | Moderation, spam, rate limits, backup, restore, metrics | ⏳ |

---

## 1. Phase 1 — Foundation ✅ (v0.2.0)

**Scope.** Login se lekar message delivery tak ka poora rasta.

- OTP → JWT auth, `deviceId` stable, token secure store me
- Chats, messages, delta sync (`seq` cursor), read receipts, typing, presence
- Client: apna SQLite layer (Room nahi), durable outbox, optimistic bubble, idempotency key
- WebSocket fan-out, reconnect backoff, heartbeat

**Verify.** `tools/e2e_two_devices.py` — 7/7 checks (login, lookup, chat, WS delivery,
sync + receipt, typing, idempotent retry, cursor monotonic).

**Kya seekha (REA se).** Telegram server-authoritative hai (server seq = truth), Signal
local-first hai (197 job classes), Element X replicated DAG hai. Bungram ne Telegram ka
model chuna kyunki mobile par battery aur simplicity jeetati hai.

---

## 2. Phase 2 — Media ✅ (v0.3.0)

**Scope.** Text ke baad sab kuch jo bytes hai.

- **Upload**: streaming multipart, 90 MB video heap me nahi aata, 100 MB per-file cap
- **Store**: content-addressed blob (`sha256` = filename), per-owner dedupe, 2 GB quota
- **Trust**: server khud MIME sniff karta hai aur image dimensions nikalta hai — client ke
  bheje number kabhi trust nahi
- **Thumbnail**: 320 px JPEG, headless (server par `java.awt.headless=true`), plus average
  colour hint taaki placeholder bubble pehle se sahi rang ka ho
- **Delivery**: HTTP Range (video seek), ETag, immutable cache, 24 h signed share link
- **Access**: owner ya shared-chat ka member — warna 403
- **Client**: picker (gallery / document / camera), compress + progress, image / video /
  file / voice bubble, fullscreen viewer, voice recorder + waveform playback

**Verify.** `tools/e2e_media.py` — 25/25 checks (upload, sniff, thumb, quota, attachment
round-trip, share link, tampered signature, empty file, delete + blob gone).

**Kya seekha (REA se).** Telegram `FileLoader` (1,900+ lines) 4 states me kaam karta hai:
`FileLoadOperation` → queue → `FileUploadOperation` → cache. Signal ka `AttachmentUploadJob`
197-job pipeline ka ek hissa hai aur blob ko `BlobProvider` se abstract karta hai. Element X
ka `MediaUpload` interface compress → upload → send ko teen alag step me todta hai. Bungram
ne teeno ka sabse simple hissa liya: **upload pehle, message baad me** — isse retry sasta
hota hai, kyunki bytes ek hi baar jaate hain aur message sirf id reference karta hai.

---

## 3. Phase 3 — Groups + Channels (agla)

**Kya banega**

- `ChatType`: DIRECT / GROUP / CHANNEL
- Group: up to 200 members, admin rights bitmask (add, remove, pin, delete, invite)
- Channel: one-to-many, subscriber count, silent broadcast, "view as member"
- Invite link (`t.me`-jaisa apna `bungram.app/+hash`), revoke, expiry
- Per-chat member list + join/leave system messages
- Server: `members` table (chatId, userId, role, joinedAt, mutedUntil)
- Client: group chat screen, member sheet, admin toggles, system message bubble

**Verify.** e2e: 5 users ek group me, admin ne 1 ko nikala, nikale hue ko message na jaye;
channel par 50 subscribers, 1 post, sabko WS event.

**Risk.** Permission matrix galat hone par silent data leak. Isliye permission check
**ek hi function** me hoga (`canDo(userId, chatId, action)`), aur test usi function ko
table-driven cover karega.

---

## 4. Phase 4 — E2EE (Secret Chat)

**Kya banega**

- X3DH key agreement + Double Ratchet (per-message key, forward secrecy)
- Native crypto: `libsignal-client` (ya `boringssl` wrapper) — **pure Kotlin nahi chalega**,
  constant-time guarantee chahiye
- Media ke liye per-file AES-256-GCM key, key ko message ke andar wrap karke bhejo
- Safety number (60 digit) verify screen, key change warning
- Server kabhi plaintext na dekhe: `ciphertext` field already hai, sirf client-side encrypt
  add karna hai

**Verify.** e2e: A → B 100 messages, har message ka key alag; server DB dump me koi
plaintext na ho; ratchet step ke baad purani key se decrypt fail ho.

**Risk.** Sabse bada risk. Ye phase akele 1 hafta maangta hai. Isliye Phase 3 ke baad
turant nahi, Phase 7 ke baad karenge — pehle woh features bana lein jo E2EE ke bina bhi
kaam karte hain, phir encryption layer upar se chadha denge.

---

## 5. Phase 5 — Push

**Kya banega**

- FCM integration (ya self-hosted ntfy/UnifiedPush fallback)
- Device token registry: `POST /v1/devices`, token rotation, dead token cleanup
- Push payload: chatId + senderId + preview text **nahi** (E2EE ke baad), sirf "new message"
- Client: foreground service se notification, per-chat channel, reply-from-notification,
  mark-as-read on dismiss
- Doze / battery optimisation handling

**Verify.** App force-stop, doosre device se message, notification aaye; tap par sahi chat
khule; reply karne par message send ho.

---

## 6. Phase 6 — Calls

**Kya banega**

- WebRTC (libwebrtc) + apna signaling over existing WebSocket
- 1:1 voice/video, ICE/STUN/TURN, mute, camera flip, speaker, duration timer
- Group call: SFU (medooze ya ion-sfu) — Telegram-style grid
- Call log sync (already model me hai), missed call push

**Verify.** Do device, 60 s call, audio dono taraf, network switch (WiFi → 4G) par call
na tute.

**Note.** Element X ka rasta yaad rakhna: unke paas Element Call ek **React SPA** hai jo
WebView me chalta hai (assets me poora bundle mila). Agar native WebRTC time kha raha ho,
wahi shortcut legitimate hai — SPA + `postMessage` bridge.

---

## 7. Phase 7 — Stories, Reactions, Chat Utilities

**Kya banega**

- Stories: 24 h TTL, viewer list, privacy (contacts / close friends / selected)
- Reactions: emoji picker, aggregate count, WS reaction event
- Reply / forward / pin / star / delete-for-everyone / edit window
- Search: local FTS5 (client) + server `LIKE` fallback, message jump-to
- Drafts, archive, mute, block

**Verify.** Har feature ka e2e + UI screenshot diff.

---

## 8. Phase 8 — Scale

**Kya banega**

- Postgres + Flyway migrations (in-memory `Store` ko replace)
- Redis pub/sub for multi-node WebSocket fan-out
- S3/MinIO object storage (local disk provider ke peeche same interface)
- Read replicas, message pagination (keyset, not offset)
- Load test: 10k concurrent WS, 1k msg/s

**Verify.** k6 load test report repo me commit ho, p99 latency number ke saath.

---

## 9. Phase 9 — Multi-device

**Kya banega**

- Ek account, 4 device, per-device session
- Device list screen: naam, last active, "terminate"
- Per-device message key wrapping (E2EE ke saath)
- Sync gap recovery: naya device 30 din ka history pull kare

**Verify.** 3 device login, teeno par same message, ek device revoke → turant 401.

---

## 10. Phase 10 — Trust & Ops

**Kya banega**

- Rate limits per route (already `RateLimiter` hai, ab policy table)
- Spam heuristics, report/block flow, abuse queue
- Backup: nightly `pg_dump` + media manifest, restore drill
- Metrics: Prometheus `/metrics`, Grafana dashboard JSON repo me
- Crash reporting, structured logs, request IDs

**Verify.** Restore drill: backup se clean DB, 100% messages wapas.

---

## 11. Har phase ka definition of done

Koi phase "done" tabhi maana jayega jab ye 5 cheez maujood hon:

1. **Code** — server + client dono side
2. **Test** — `tools/e2e_*.py` me naya script, green
3. **Doc** — `docs/` me us phase ka build report (Hindi), numbers ke saath
4. **APK** — `artifacts/` me versioned build, `aapt2 dump badging` se verify
5. **Release** — GitHub tag + release notes + APK asset

---

## 12. Abhi ka agla kadam

Phase 3 (Groups + Channels). Uske liye pehle `Store` ko `members` table chahiye — abhi
membership `Map<String, MutableSet<String>>` hai, jo process restart par kho jata hai.
Isliye Phase 3 ka pehla kaam persistence hai, feature nahi.
