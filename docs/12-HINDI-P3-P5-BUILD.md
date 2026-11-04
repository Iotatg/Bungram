# Bungram 0.4.0 — Phase 3 + 4 + 5 build report

Yeh report Hindi (Roman script) me hai, jaise aapne kaha tha. Teen phase ek saath: **groups/channels**,
**end-to-end encryption**, aur **push/notifications** — plus login ka poora Telegram-jaisa rewrite,
profile page, stickers, icons aur sounds.

---

## 1. Phase 3 — Groups, channels, invites, stickers

### Server (Ktor)

| Kaam | File | Detail |
|---|---|---|
| Chat CRUD | `routes/ChatRoutes.kt` | `POST /v1/chats/create`, `GET/PATCH/DELETE /v1/chats/{id}` |
| Members | wahi | `POST/DELETE /v1/chats/{id}/members`, `POST .../members/{uid}/role` |
| Invite links | wahi | `POST/DELETE /v1/chats/{id}/invite`, `GET /v1/invites/{slug}`, `POST /v1/invites/{slug}/join` |
| Stickers | wahi | `GET /v1/stickers` → 6 packs × 30 emoji = **180 stickers** |
| Roles | `store/Store.kt` | OWNER → ADMIN → MEMBER, `canPost()` channel me sirf admins |

**Sabse important design decision:** `myRole`, `canPost`, `membersCount` **store nahi** kiye jaate.
Har read par `project(chat, userId)` se bante hain. Isliye ek hi chat row har member ko sahi dikhti hai —
admin ko "post" button, subscriber ko nahi. Agar role chat row me store hota, to 50 members ke liye
50 copies rakhni padti aur ek promote karte hi sab stale ho jaate.

Invite slug: 16 characters, ambiguity-free alphabet (`23456789ABCDEFGHJKLMNPQRSTUVWXYZ` — `0/O`, `1/I/L`
hata diye, kyunki log link phone se type karte hain). Expiry + revoke dono supported.

System messages (`MessageKind.SYSTEM`) create / rename / join / remove par automatically banti hain —
Telegram ki tarah, chat ke beech me centered pill.

### Client

- **Create group / channel** — ek hi screen, `CreateMode { GROUP, CHANNEL }` segmented switch. Channel
  par "public" toggle extra.
- **Group info** — avatar, member list (Owner/Admin badges), invite row (`bungram.app/+slug`, tap = copy),
  add-by-phone, remove, promote/demote, rename, leave, delete. Delete/leave `AlertDialog` ke peeche.
- **Stickers + emoji panel** — 180 stickers, 8-column emoji grid (~210 emoji), tab pills, slide-up animation.
- **Typing dots** — teen 6dp dots, `tween(420, delay = i*120)`, `RepeatMode.Reverse`. 2.5s idle par
  "typing…" khud band ho jata hai, kyunki "stopped typing" frame kabhi kho sakta hai.

### Test

`tools/e2e_groups.py` — **21/21 PASS**. Isme ye bhi check hota hai ki removed member ka access
turant khatam ho, admin owner ko remove na kar sake, aur subscriber channel me post na kar sake.

---

## 2. Phase 4 — End-to-end encryption

### Crypto stack (BouncyCastle, single stack)

`crypto/Crypto.kt` + `crypto/DoubleRatchet.kt` + `crypto/E2ee.kt`.

| Layer | Kya |
|---|---|
| X25519 | Diffie–Hellman |
| Ed25519 | identity signatures (signed pre-key) |
| X3DH | asynchronous session setup (DH1..DH4) |
| Double Ratchet | root key, chain keys, DH ratchet, skipped keys |
| AES-256-GCM | payload, 12-byte nonce, header = AAD |

**Ek bug jo mila aur fix hua:** pehle version me Alice `RatchetInitAlice` ke bajaye poora do-step
ratchet kar rahi thi. Isse root key do baar advance ho jati thi aur Bob ki chain kabhi match nahi karti.
Spec kehta hai: initiator **ek** step kare (`RK, CKs = KDF_RK(SK, DH(DHs, DHr))`). Fix ke baad 18/18
unit test pass.

**Dusra bug:** skipped message keys `contentHashCode()` se key kar rahe the — collision possible.
Ab `base64(ratchetPub):counter` use hota hai.

**Teesra bug:** responder ko peer ki identity **signing** key nahi milti thi, isliye safety number
sirf ek taraf banta tha. Ab pre-key header me `identitySignPublic` (32 bytes) bhi jata hai.

### Wire format

```
"b1." + base64url(
   version(1) | type(1)
   type=1 (PREKEY): identityDh(32) | identitySign(32) | ephemeral(32) | spkId(4) | otkId(4) | header | ct
   type=2 (RATCHET): header | ct
)
```

Type byte AAD chain ka hissa hai, isliye koi pre-key message ko ratchet message me downgrade nahi kar sakta.

### Key storage

`SecretStorage` interface → `PrefsSecretStorage` (EncryptedSharedPreferences, Android Keystore) ya
`MemorySecretStorage` (tests). Har message ke baad ratchet state **turant** persist hoti hai — ye
optimisation nahi, security model hai.

### One-time pre-keys (server side)

Pehle server ek hi bundle rakhta tha aur `take` consume nahi karta tha — matlab same OTK baar baar
milta. Ab `PreKeyRecord` pool hai: fetch par ek key **atomically pop** hoti hai, pool khatam ho to
bundle bina OTK ke milta hai ("last resort", Signal jaisa). Client sirf **nayi** keys upload karta hai,
poora pool nahi — warna already-spent keys resurrect ho jaati.

### UI

- Chat header me **lock icon** (sirf jab real session ho).
- Lock tap → **safety number sheet**: 60 digits, 6 rows × 10, dono taraf same.
- Decrypt fail → "Message not available on this device" (blank bubble ya base64 nahi).

### Test

`app/src/test/java/app/bungram/crypto/E2eeTest.kt` — **18/18 PASS**, JVM par (no emulator):

1. pre-key message decrypt hota hai
2. dono taraf session establish
3. reply ratchet se readable
4. 5 turns tak conversation chalti hai
5. safety numbers dono taraf same
6. har message ka ciphertext different
7. consumed message replay → reject
8. tampered ciphertext → auth fail
9. stranger ka message → reject
10. out-of-order (1,4,2,3,5) → sab decrypt
11. ratchet turn ke baad purane messages → decrypt
12. process death ke baad storage se session resume
13. OTK exactly ek baar consume
14. bina OTK ke handshake
15. bad signature → reject
16. no pre-keys → typed exception
17. garbage wire → null, crash nahi
18. identity stable across restarts

**Scope, saaf-saaf:** 1:1 chats E2EE hain. Groups abhi server-side storage par hain — Sender Key
(`SenderKey` class maujood hai) agle phase me wire hoga. Isko lock icon se chhupaya nahi gaya:
group me lock dikhta hi nahi.

---

## 3. Phase 5 — Push aur notifications

### Server

`push/PushSender.kt` — `PushDispatcher` FCM aur ntfy dono support karta hai
(`BUNGRAM_FCM_TOKEN` / `BUNGRAM_NTFY_BASE`). **Sirf un members ko** push jata hai jinka live socket
nahi hai. Payload me message text kabhi nahi jata — sirf chatId aur sender ka naam.

`POST/GET/DELETE /v1/devices` — device registry, `current` flag ke saath.

### Client

- Login ke baad `publishPreKeys()` + `registerDevice()` — socket khulne se **pehle**, warna peer ka
  handshake hum tak pahunch hi nahi sakta.
- **MessagingStyle** notification, per-chat, `Person` ke saath.
- **Inline reply** — app khole bina jawab. `ReplyReceiver` + `RemoteInput`, `goAsync()` se.
- **Mark as read** action, aur **swipe dismiss bhi read maana jata hai** (delete intent).
- Group summary notification, `setGroupConversation(true)`.
- Apni hi khuli chat ke liye notification suppress — `AppGraph.isInForeground()` +
  `repository.activeChat()`.
- `POST_NOTIFICATIONS` runtime permission (Android 13+).

---

## 4. Login page — Telegram jaisa

`feature/auth/LoginScreen.kt` (~600 lines) + `Countries.kt` (240 countries).

- **Country auto-detect**: SIM → network → device locale → US fallback.
- **All-countries picker**: `ModalBottomSheet`, name/ISO/dial se search, "Frequently used" + "All
  countries", locale ke hisaab se sort.
- **Flag emoji** regional indicator se derive hote hain — koi image asset nahi, isliye 240 countries
  ke liye 240 PNG nahi chahiye.
- **Number formatting** live: `XXX XXX XXXX`, E.164 conversion.
- **OTP step**: 6 boxes, ek hidden `BasicTextField` (paste aur backspace dono kaam karte hain),
  active box me blinking caret, 6 digit par auto-submit, 60s resend countdown, galat code par
  **shake** animation (16 / -14 / 10 / -6 / 0 at 55ms).
- **Step transition**: `AnimatedContent` slide + fade, direction target step par depend.
- Dev code chip sirf `BUNGRAM_DEV=1` par.

---

## 5. Profile page

`feature/profile/ProfileViewModel.kt` + rewritten `ProfileScreen.kt`.

- Naam / username / about — teeno editable, `PATCH /v1/me` se. Client-side validation server ke rules
  se match karti hai (username 3–32, `[A-Za-z0-9_]`), isliye round-trip se pehle hi feedback milta hai.
- **Linked devices** sheet — server se list, "Sign out" se revoke (revoke pre-keys bhi delete karta hai).
- **Message sounds** toggle — persist hota hai, `Sounds.enabled` ko turant update karta hai.
- Footer: `Bungram 0.4.0 · end-to-end encrypted` — BuildConfig se, hardcoded nahi.

---

## 6. Assets — PNG, SVG/vector, audio

| Type | File | Note |
|---|---|---|
| PNG launcher | `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | 48→192px, PIL se generate, gradient + "B" |
| PNG round | `.../ic_launcher_round.png` | circular mask |
| PNG adaptive fg | `.../ic_launcher_foreground.png` | 33% safe zone |
| Vector | `mipmap-anydpi-v26/ic_launcher.xml` | adaptive + monochrome (Android 13 themed icon) |
| Vector | `drawable/ic_notification.xml`, `ic_send.xml`, `ic_done.xml` | status bar + actions |
| Audio | `raw/send.wav` (8.8 KB) | 740 Hz upward blip, 100 ms |
| Audio | `raw/receive.wav` (20.8 KB) | 988→740 Hz two-note |
| Audio | `raw/login.wav` (52 KB) | C-E-G chime |

Audio `SoundPool` se bajta hai (MediaPlayer nahi — 100ms effect ke liye codec allocation waste hai).
Ringer silent ho to kuch nahi bajta, aur settings toggle bhi respect hota hai.

---

## 7. Animations — sab jagah

| Kahan | Kya |
|---|---|
| Splash | spring scale 0.85 → 1.0 |
| Login steps | `AnimatedContent` slide + fade, direction-aware |
| OTP error | shake (5 keyframes, 55ms) |
| Home tabs | horizontal slide + crossfade, travel direction ke hisaab se |
| Sticker panel | slide-up 240ms `FastOutSlowInEasing` |
| Send button | press par 0.88 scale, medium-bouncy spring |
| Typing dots | staggered 3-dot bounce |
| Attachment menu | slide + fade |
| Error bar | `AnimatedVisibility` |
| Safety sheet / devices sheet | `ModalBottomSheet` default spring |

---

## 8. Bug fixes (is phase me)

1. **Ratchet init** — initiator do-step ratchet kar raha tha → Bob ki chain kabhi match nahi. Fix.
2. **Skipped key collision** — `contentHashCode()` → `base64(pub):counter`.
3. **Safety number ek taraf hi** — pre-key header me signing key add ki.
4. **Safety number locale** — `"%05d".format()` Arabic-Indic digits de sakta hai → `Locale.ROOT`.
5. **OTK reuse** — server pool + consume; client sirf nayi keys publish karta hai.
6. **Ciphertext preview** — server `lastMessagePreview` me base64 daal raha tha → "Encrypted message".
7. **Outgoing echo** — server ciphertext lautata tha, UI me dikh jata → local plaintext rakha jata hai.
8. **Divider vs Hairline** — `Divider` ek `Color` token hai, composable nahi (compile error).
9. **`ChatRole` vs `String`** — `chat.myRole` enum hai.
10. **`combine` 6 flows** — type inference fail → `AuxState` me fold kiya.
11. **CI Android job** — `setup-android@v3` `Failed to find package 'tools'` → runner ka preinstalled SDK.
12. **CI server job** — background `gradle run` step ke end par mar jata tha → `installDist` + nohup.

---

## 9. Verification (is machine par)

| Suite | Result |
|---|---|
| `E2eeTest` (JVM) | **18/18 PASS** |
| `tools/e2e_groups.py` | **21/21 PASS** |
| `tools/e2e_media.py` | **25/25 PASS** |
| `tools/e2e_two_devices.py` | **7/7 PASS** |
| `:app:assembleDebug` | BUILD SUCCESSFUL — `bungram-0.4.0-debug.apk` |

---

## 10. Aage kya (Phase 6+)

1. **Group E2EE** — Sender Key distribution pairwise sessions ke upar (class ready hai).
2. **Multi-device** — ek user ke multiple devices ko fan-out, device-scoped ratchets.
3. **Postgres** — abhi store in-memory hai, restart par data chala jata hai.
4. **Media E2EE** — attachment bytes bhi seal karna (abhi sirf text).
5. **Backup** — encrypted key backup, warna reinstall = history gone.
