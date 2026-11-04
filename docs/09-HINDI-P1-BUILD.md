# Bungram P1 — App ab asli server se baat karta hai

> Ye document Hindi (Roman) me hai kyunki tumne Hindi me baat karne ko kaha.
> Har number isi machine par chala kar likha gaya hai — koi andaaza nahi.

---

## 1. P1 ka matlab kya tha

Pehle app **demo data** padh rahi thi: `DemoChatRepository` memory me fake chats rakhta tha,
message bhejne par `Thread.sleep()` se ticks badalte the. Server alag se chalta tha, dono ka
koi rishta nahi tha.

P1 ka goal ek hi line me: **ek device se bheja message doosre device par pahunche, aur app
band karke kholne par bhi na khoye.**

Ab wo ho gaya hai. Neeche poora hisaab.

---

## 2. Ab architecture kaisa hai

```
┌────────────────────────── Android app (Kotlin + Compose) ──────────────────────────┐
│                                                                                    │
│  UI (screens)                                                                      │
│    │  sirf StateFlow collect karti hai, network ko jaanti hi nahi                  │
│    ▼                                                                               │
│  RemoteChatRepository  ──────────────►  BungramDb (apna SQLite layer)              │
│    │   • chats / messages / users ka StateFlow      • users, chats, messages       │
│    │   • optimistic bubble + outbox                 • outbox (durable queue)       │
│    │   • delta apply                                • meta (sync cursor)           │
│    ▼                                                                               │
│  BungramApi (Ktor client, ek hi HttpClient)                                        │
│    │                                                                               │
│  SyncEngine (WebSocket + backoff + heartbeat)                                      │
└────┼───────────────────────────────────────────────────────────────────────────────┘
     │  HTTPS REST  +  WSS
     ▼
┌────────────────────────── Bungram server (Ktor, Kotlin) ───────────────────────────┐
│  /v1/auth/otp · /v1/auth/verify        OTP → JWT                                    │
│  /v1/sync?cursor=<seq>                 delta sync (chats + users + messages)        │
│  /v1/messages  {clientId}              idempotent send                              │
│  /v1/chats/{id}/read                   read + receipt broadcast                     │
│  /v1/users/lookup?phone=               number se account dhoondo                     │
│  /ws?token=<jwt>                       message / receipt / typing / presence        │
└────────────────────────────────────────────────────────────────────────────────────┘
```

**Rule jo poore project me follow hota hai:** SQLite local sach hai, network sirf delta bhejta
hai. Isliye offline me bhi app poora khulta hai, purane message dikhte hain, naya message
queue me baith jaata hai.

---

## 3. Naya code — kaun si file kya karti hai

| File | Kaam | Lines |
|---|---|---|
| `data/local/BungramDb.kt` | **apna SQLite engine**, 5 table, koi ORM nahi | ~380 |
| `data/repo/RemoteChatRepository.kt` | cache + outbox + optimistic send + delta apply | ~330 |
| `sync/SyncEngine.kt` | socket, reconnect backoff, heartbeat, event routing | ~170 |
| `data/remote/ServerSettings.kt` | server address (emulator / LAN / prod) | ~40 |
| `feature/auth/AuthViewModel.kt` | OTP login ka logic | ~150 |
| `feature/auth/LoginScreen.kt` | login UI + dev code chip | ~300 |
| `feature/newchat/NewChatScreen.kt` | number se naya chat | ~250 |
| `BungramApp.kt` | service locator + session state | ~120 |

Purani `DemoChatRepository` file ab **kahin use nahi hoti** — sirf screenshot fixtures ke liye
padi hai. UI ka koi bhi rasta demo data nahi padhta (quick-people row bhi ab asli chats se
banti hai).

---

## 4. Message bhejne ka poora safar (yahi P1 ka dil hai)

```
1. User "Send" dabata hai
      ↓
2. Repository ek local row banata hai:  id = "l_<uuid>",  delivery = QUEUED
   • message turant list me dikhta hai (0 ms, network ka wait nahi)
      ↓
3. Wahi row outbox table me likhi jaati hai (SQLite, disk par)
   • app crash / phone restart → ye row zinda rahegi
      ↓
4. flushOutbox() FIFO order me POST /v1/messages {clientId} bhejta hai
      ↓
5. Server:
   • "senderId:clientId" index check karta hai
   • naya hai → message store karo, seq assign karo, peers ko WS par bhejo
   • pehle se hai → wahi purana message wapas do (duplicate nahi banta)
      ↓
6. Client local row "l_..." delete karke server wali row (id "m_...", SENT) daal deta hai
      ↓
7. Peer ka device WS par {"type":"message"} pakadta hai → /v1/sync maangta hai
      ↓
8. Peer chat kholta hai → POST /read → server sender ko {"type":"receipt"} bhejta hai
      ↓
9. Sender ke bubble par do tick (READ) ho jaate hain
```

Har step ke baad crash ho jaye to bhi result sahi rehta hai — kyunki outbox disk par hai aur
server `clientId` yaad rakhta hai. **Yahi cheez Signal/Telegram jaisi apps me "message kabhi
gum nahi hota" banati hai.**

---

## 5. Server me 5 asli bug fix (sirf feature nahi, bug)

### 5.1 Sync cursor client ke clock par tha — galat

Pehle: `nextCursor = System.currentTimeMillis()` aur filter `createdAt > cursor`.
Do problem:

* phone ka clock 3 minute peeche ho → naya message kabhi sync hi nahi hoga
* phone ka clock aage ho → cursor future me chala jayega, beech ke message hamesha miss

Ab: server har message par **monotonic `seq`** lagata hai, cursor wahi seq hai.
`createdAt` bhi ab server ka time hai, client ka nahi.

### 5.2 Duplicate message

Pehle: network timeout par client retry karta → do bubble. Ab: `(senderId, clientId)` index,
retry par wahi message laut ke aata hai. Test me proven (check #5).

### 5.3 Read receipt kabhi nahi jaata tha

Pehle: REST `/read` sirf DB update karta tha, sender ko kuch nahi bhejta tha — do tick kabhi
nahi aate the. Ab: `markReadReturning()` sirf wahi message lautata hai jo actually badle, aur
unka receipt peers ko broadcast hota hai (receipt storm nahi).

### 5.4 Same number se do chat

Pehle: `POST /v1/chats` har baar naya thread banata tha. Ab: direct chat pehle se hai to wahi
lautta hai.

### 5.5 Naye account ka welcome message kabhi sync hi nahi hota tha

Ye bug test ke dauraan mila. `provisionWelcomeChat()` message ko seedha list me daal raha tha,
`appendMessage()` se nahi — isliye uska `seq` 0 reh gaya, aur `seq > 0` filter use bahar kar deta
tha. Naya user login karta, "Bungram" chat dikhti, par andar koi message nahi. Ab wo bhi
`appendMessage()` se jaata hai. Verify: naya account → `messages: [('Bungram', 'Welcome to Bungram 👋…')]`.

---

## 6. Test — 6/6 pass (yahi machine par)

`tools/e2e_two_devices.py` do device simulate karta hai (do account, do WebSocket, ek server):

```
== 1. login ==
   A=+62 811 9794077 (u_1017)
   B=+62 822 9794077 (u_1019)
== 2. lookup + open chat ==
   chat=c_1021 (idempotent)
== 3. websocket delivery ==
   B sent m_ffa144d7 seq=1012
   A received ws event: {'type': 'message', 'chatId': 'c_1021', 'messageId': 'm_ffa144d7', ...}
== 4. sync + read receipt ==
   sync carries 3 profiles (peer: +62 822 9794077)
   A sees 1 message(s) in this chat, cursor=1012
   B received receipt: {'type': 'receipt', 'messageId': 'm_ffa144d7', 'state': 'READ'}
== 5. idempotent retry ==
   retry returned the same id, still 1 message
== 6. cursor monotonic ==
   nothing new, cursor 1012 -> 1012

ALL 6/6 CHECKS PASSED
```

Teen baar lagataar chalaya — teeno baar 6/6. Ek baat ka dhyan rakhna: test har run me **naye
phone number** banata hai. Wahi number dobara use kiya to idempotency (bilkul sahi) purana
message lauta degi aur naya broadcast nahi hoga — isi wajah se pehle mere hi test me confusion
hua tha. Ab har run random number leta hai.

Chalane ka tarika:

```bash
cd Bungram/server && BUNGRAM_DEV=1 PORT=8080 ./build/install/bungram-server/bin/bungram-server
cd Bungram && python3 tools/e2e_two_devices.py
```

---

## 7. App kaise chalayein

**Server:**

```bash
cd Bungram/server
BUNGRAM_DEV=1 PORT=8080 ./build/install/bungram-server/bin/bungram-server
```

`BUNGRAM_DEV=1` zaroori hai — isi se OTP response me `devCode` aata hai aur login screen par
"Dev code · tap to fill" chip dikhta hai.

**APK:** `artifacts/bungram-0.2.0-p1-debug.apk` (28 MB, minSdk 26)

* Emulator: default `http://10.0.2.2:8080` — kuch nahi badalna
* Asli phone: login screen par neeche "Server · ..." par tap karo → apne laptop ka LAN IP dalo
  (`http://192.168.x.x:8080`). Debug build me cleartext allowed hai.

**Do device test:**

1. Phone A: `+62 811 000 001` se login (dev code tap karo)
2. Phone B: `+62 822 000 002` se login
3. A par "New chat" → B ka number → "Message"
4. Message bhejo — B par turant aayega, B kholte hi A par do tick

---

## 8. Kya khud likha, kya library hai (imaandaar hisaab)

| Cheez | Faisla | Kyun |
|---|---|---|
| SQLite layer | **khud likha** (`BungramDb`) | Room = KSP + codegen; 5 table ke liye zaroorat nahi |
| Outbox / retry | **khud likha** | Ye business logic hai, library nahi |
| Sync cursor protocol | **khud likha** | Yahi Bungram ka apna contract hai |
| UI | **khud likha** (Compose) | Compose ek UI toolkit hai, design apna |
| HTTP client | Ktor | socket/TLS/timeout khud likhna bewakoofi hai |
| JSON | kotlinx.serialization | — |
| Token storage | EncryptedSharedPreferences | Keystore ka kaam khud karne ka matlab nahi |

Matlab: **product ka dimaag apna hai, plumbing industry-standard.** Encryption (P4) me bhi
yahi rule rahega — Double Ratchet khud implement karna security bug hai, `libsignal` use karna
sahi hai.

---

## 9. Jo abhi baaki hai (saaf saaf)

| Cheez | Status |
|---|---|
| Text message: send / receive / offline queue / retry / receipt | ✅ P1 me ho gaya |
| Naya chat (number se), profile sync, read receipt | ✅ |
| Media (photo/video/file upload) | ❌ P2 — abhi sirf attachment ka naam jaata hai |
| E2EE (X3DH + Double Ratchet) | ❌ P4 — `crypto/` me skeleton pada hai, wire se juda nahi |
| Group / channel, sender keys | ❌ P3 |
| Voice / video call | ❌ P6 |
| Push notification (FCM) | ❌ P5 |
| Typing indicator UI | ⚠️ server + engine ready, screen par dikhta nahi |

### Ek limit jo main chhupa nahi raha

Is sandbox me `/dev/kvm` nahi hai, isliye **emulator chal nahi sakta** — maine app ka UI
runtime par test nahi kiya. Jo verify hua wo ye hai:

* `assembleDebug` → BUILD SUCCESSFUL
* APK badging → `app.bungram.debug`, minSdk 26, target 35
* Poora wire protocol do device ke saath 6/6 pass
* Server `installDist` se chalta hai, health/OTP/sync/send/read sab live

UI ka pehla asli test tumhare phone par hi hoga. Agar crash aaye to log bhej dena — main
turant fix karunga.

---

## 10. Agla kadam

**P2 — media pipeline.** Iske bina chat adhoora hai. Plan:

1. `POST /v1/media` (multipart) → server file rakhta hai, `mediaId` deta hai
2. Client pehle **local path** se bubble dikhata hai, background me upload karta hai
3. Upload progress bubble ke andar, fail par retry (wahi outbox pattern)
4. Download: thumbnail pehle, full baad me, `mediaKey` se decrypt (P4 ke baad)

Bolo to P2 shuru karta hoon.
