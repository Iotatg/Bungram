# Bungram Phase 1 + 2 — Media pipeline aur polish

> Hindi (Roman). Har number isi machine par chala kar nikala gaya hai.

---

## 1. Is round me kya bana

Phase 1 foundation pehle se tha (login → message delivery). Is round me **Phase 2 (media)**
poora bana, aur Phase 1 ke teen adhoore hisse band kiye.

| Kaam | Pehle | Ab |
|---|---|---|
| Photo / video / file / voice bhejna | demo URI, server par kuch nahi jata tha | asli upload → server blob → message |
| Thumbnail | nahi | 320 px JPEG, server par bane |
| Video seek | nahi | HTTP Range |
| Share link | nahi | 24 h signed URL |
| Typing indicator | engine tha, UI nahi | header me "typing…" (blue), 2.5 s idle par clear |
| Profile | hardcoded "Fitria Rahmani" | asli logged-in account |
| Logout | button dead tha | `AppGraph.logout()` — token + DB wipe |

---

## 2. Server side — `server/media/`

```
MediaStore.kt   (≈300 lines)  content-addressed blob store
ImageTools.kt   (≈150 lines)  MIME sniff, dimension probe, thumbnail, average colour
MediaSigner.kt  ( ≈50 lines)  HMAC-SHA256 signed URL, 24 h expiry
routes/MediaRoutes.kt         upload / get / thumb / link / delete / quota
```

**Blob layout.** `media/<ownerId>/<sha256>` — naam hi content ka hash hai. Do fayde:

1. **Dedupe** — same file dobara bhejo, disk par ek hi copy.
2. **Integrity** — naam se content verify ho sakta hai, aur CDN ke liye cache key free milti hai.

**Upload flow.**

```
POST /v1/media  (multipart)
  ├─ streaming read → temp file (heap me kabhi poora file nahi aata)
  ├─ MIME sniff (magic bytes, client ka Content-Type ignore)
  ├─ image ho to dimension probe + 320 px thumb + average colour
  ├─ sha256 → already hai? quota count nahi hota, sirf reference badhta hai
  └─ record: { id, ownerId, sha256, mime, bytes, width, height, thumb, colour }
```

**Quota.** 2 GB per account, 100 MB per file. Quota exceed → `413`, empty file → `400`.

**Access control.** Teen me se koi ek:

- valid bearer token **aur** (owner ho **ya** kisi shared chat ka member ho)
- signed URL `?t=...` jo expire na hui ho
- warna `403`

Test ye bhi check karta hai ki **tampered signature** 403 deta hai.

---

## 3. Client side — `android/.../data/media/`

```
MediaUploader.kt  (≈290 lines)  pick → compress → stream upload → progress
VoiceRecorder.kt  (≈110 lines)  MediaRecorder AAC, max duration, cancel
AudioPlayer.kt    ( ≈90 lines)  ExoPlayer wrapper, progress StateFlow
MediaUrl.kt       ( ≈50 lines)  local file vs remote URL, thumb vs full
```

**Upload pehle, message baad me.** Ye design choice Phase 2 ka sabse important decision hai:

```
bytes ──► POST /v1/media ──► mediaId
                                │
text/kind ──► outbox ──► POST /v1/messages { attachmentId: mediaId }
```

Agar message fail ho, bytes dobara nahi jaate — sirf chhota JSON retry hota hai. Agar upload
fail ho, koi message bana hi nahi. Isse outbox simple rehta hai aur data waste nahi hota.

**Voice note.** Record → `file://` → wahi upload path → `kind=VOICE` → waveform bubble
(waveform hash se deterministic hai, isliye har device par same dikhta hai).

**Viewer.** Image: pinch zoom + double-tap. Video: ExoPlayer, Range ke saath seek. File:
system opener.

---

## 4. Bug jo is round me mile aur fix hue

| Bug | Asar | Fix |
|---|---|---|
| `java.awt` headless nahi tha | thumbnail banate hi server crash | `System.setProperty("java.awt.headless","true")` main se pehle |
| `Map<String,Any>` serialize nahi ho raha tha | `/quota` 500 | typed `MediaQuotaDto` |
| 403 vs 404 ka order galat | non-owner ko "exists" ka pata chal jata | ownership check pehle, phir existence |
| empty upload 200 deta tha | 0-byte blob DB me | `400 empty upload` |
| typing frame ka koi UI nahi tha | peer type kare to kuch na dikhe | `ChatUiState.isPeerTyping` + header accent |
| typing "stuck" ho sakta tha | socket drop par "typing…" hamesha | 5 s TTL timer har `true` par |
| profile hardcoded tha | login ke baad bhi fake naam | `AppGraph.me()` |
| logout dead tha | user account switch nahi kar sakta | `AppGraph.logout()` |

---

## 5. Verification (asli output)

```
tools/e2e_two_devices.py   →  ALL 7/7 CHECKS PASSED
tools/e2e_media.py         →  25/25 checks passed
server: ./gradlew build    →  BUILD SUCCESSFUL
android: assembleDebug     →  BUILD SUCCESSFUL
```

`e2e_media.py` jo check karta hai (script ke asli naam):

```
login: two independent accounts              upload: 201/200 with MediaDto
upload: real dimensions probed by server     upload: sha256 computed
upload: thumbnail generated                  upload: colour hint for placeholder
download: owner can read the blob            download: content-type is image/png
download: ETag present                       download: range request returns 206 + 100 bytes
thumbnail: served as JPEG                    ACL: anonymous request rejected
ACL: stranger without a shared chat forbidden  dedupe: identical upload returns same id
quota: usage accounted                       quota: limit advertised
message: send with attachment                message: attachment carries metadata
message: attachment has a thumbnail url      sync: message with attachment round-trips
share link: signed url works without token   share link: tampered signature rejected
share link: endpoint available               upload: empty file rejected
delete: owner can delete                     delete: blob is gone afterwards
```

APK:

```
package: name='app.bungram.debug' versionCode='3' versionName='0.3.0'
sdkVersion:'26'  targetSdkVersion:'35'
size: 28 MB
```

---

## 6. Kya abhi bhi nahi hai (Phase 3+)

- Group / channel — sirf 1:1
- E2EE — server abhi plaintext store karta hai (field ready hai: `ciphertext`)
- Push — app band ho to notification nahi
- Calls — screen hai, WebRTC nahi
- Postgres — sab memory me, restart par data chala jata hai (media blobs disk par rehte hain)

Agla kaam: **Phase 3 — Groups + Channels**. Poora plan `docs/10-ROADMAP-10-PHASES.md` me hai.
