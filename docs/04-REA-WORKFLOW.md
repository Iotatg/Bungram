# Using REA to reverse-engineer the three references

`morluto/rea` ("Reverse Engineer Anything") is an MCP server + CLI that lets an agent inspect a
target app — APK, native binary, JavaScript/Electron, .NET, website — and return **findings with
evidence** (artifact digest, class, method, call path) instead of guesses. That is exactly the
loop needed to answer "how does feature X work in Telegram/Signal/Element, and how do I build it
in Bungram?".

This document records what was actually installed and run in this workspace, so the same analysis
can be reproduced.

---

## 1. Install (Linux, verified here)

```bash
# REA needs Node >= 22.15 for zlib.zstd* — 22.13 crashes at import time.
nvm install 22.20.0 && nvm use 22.20.0
npm install -g rea-agents
rea --version            # 6.7.0
```

Android analysis is delegated to JADX, which REA does **not** bundle. Download the audited
release and pin it by hash:

```bash
curl -L -o /opt/rea-engines/jadx-headless-mcp-0.7.1-all.jar \
  https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/jadx-headless-mcp-0.7.1-all.jar
sha256sum /opt/rea-engines/jadx-headless-mcp-0.7.1-all.jar
# 6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75  (matches src/android/JadxRelease.ts)

export REA_JADX_MCP_JAR=/opt/rea-engines/jadx-headless-mcp-0.7.1-all.jar
export REA_JADX_HEAP_MIB=10240        # 100 MB APKs need headroom
rea doctor                            # expect: jadx: healthy
```

Native engines (Hopper / Ghidra / IDA / Kuna) are optional and are **not** required for Java,
Kotlin or APK work. `rea doctor` will still report them as missing; that is expected here.

Register the MCP server with an agent (optional, for interactive work):

```bash
npx rea-agents@latest setup     # writes MCP config + workflow skill for Claude Code / Codex / Cursor
```

---

## 2. Commands that were actually used

```bash
# APK: manifest, package identity, permission inventory, class/resource counts
rea inspect-android-package /workspace/research/apk/telegram.apk --format json > rea_telegram.json
rea inspect-android-package /workspace/research/apk/signal.apk   --format json > rea_signal.json

# One class's members (needs the APK loaded per call)
rea inspect-android-class --apk signal.apk org.thoughtcrime.securesms.crypto.KeyStore

# One method, decompiled, with overload selection
rea inspect-android-method --apk telegram.apk \
  org.telegram.messenger.MessagesController getDifference

# Resources only (strings, layouts, manifest) via apktool
rea decode-android-resources telegram.apk --format json

# Non-Android lanes, for comparison
rea analyze-javascript-application /path/to/app --json
rea inspect-artifact /path/to/libsignal.so
```

Each run writes an evidence envelope: `evidence_id`, `subject.sha256`, `provider`, `parameters`,
`normalized_result`, `limitations`. Keep them; they are the difference between "I think Signal
does X" and "Signal does X, from artifact `5e2efd0b…`".

---

## 3. Results from this workspace

| Artifact | Identity | Classes | Resources | Permissions | Evidence |
|---|---|---|---|---|---|
| `telegram.apk` | `org.telegram.messenger.web` 13.1.1 (72379) | 16,432 | 7,531 | 74 | `ev_4303e3f0…cd595` |
| `signal.apk` | `org.thoughtcrime.securesms` 8.33.2 (177201) | 22,008 | 4,140 | 77 | `ev_0e8dce82…63a0` |
| `elementx.apk` | `io.element.android.x` 26.10.1 | — | — | — | see `research/rea_elementx.json` |

What the permission inventories already tell you, before reading a single line of code:

- Telegram declares **12 different launcher badge permissions** (Samsung, HTC, Sony, Huawei, OPPO,
  `me.everything.badger`, …). Unread badges on Android are still a per-OEM hack in 2026.
- Telegram declares `MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE_PHONE_CALL` and `READ_CALL_LOG` —
  it integrates with the system dialer through `ConnectionService`, it is not a VoIP island.
- Signal declares 77 permissions, including `USE_FULL_SCREEN_INTENT` and the full
  `FOREGROUND_SERVICE_*` family: calls and message fetch both run as foreground services.
- Neither app requests `READ_SMS` for login in the current builds — OTP autofill uses the SMS
  Retriever API instead. Copy that, not the old `READ_SMS` pattern.

---

## 4. The loop that actually works

1. **Pick one behaviour**, not a whole app. "How does Signal schedule a retry after a socket
   drop?" beats "how does Signal work?".
2. **Ask for evidence.** Require artifact digest, class names and call chains.
3. **Ask what is unknown.** A good answer states its limits; the limits tell you where to look next.
4. **Write the Bungram version from the behaviour**, not from the decompiled code.

Prompt template:

```
Use REA to inspect research/apk/signal.apk (JADX). I need to know how the client decides a
message needs a retry after a WebSocket drop: which class owns the retry queue, what backoff it
uses, and where the "sent" state flips. Return the call path with class references, and list what
you could not determine.
```

Then, with the behaviour in hand:

```
Implement the same behaviour in Bungram: a bounded outbox in sync/SyncEngine.kt with jittered
exponential backoff (1s → 30s) and QUEUED → SENT on ack. Do not copy their code; match the
behaviour and document the difference.
```

---

## 5. Per-feature recipes

| Feature | Target | What to ask REA for |
|---|---|---|
| Gap repair | Telegram (`MessagesController.getDifference`) | where `pts` is compared, what happens on a gap, which request fetches the difference |
| Pre-key lifecycle | Signal (`keyvalue/SignalStore.kt`, libsignal) | where one-time pre-keys are consumed/replenished, the low-water threshold |
| Group sender keys | Signal (`groups/`) | distribution message shape, epoch rotation triggers, member-removal handling |
| Sliding sync | Element X (Rust SDK `.so`) | which timeline limits are requested, how state diffs reach the UI |
| Attachment encryption | Signal (`attachments/`) | key derivation, digest check, verification before decrypt |
| Call signalling | Telegram (`voip/VoIPService`) | offer/answer/ICE shapes, timeout before "missed call" |
| Push payload | any release APK | which fields the push actually carries (proves content-free push) |
| Local schema | all three | table names + indices for the message table |
| Stories TTL | Telegram (`ui/Stories/`) | where expiry is enforced: server, client, or both |

---

## 6. Rules of engagement

- **Clean-room.** Read behaviour, not code. Never paste decompiled sources into Bungram.
- **Licences.** Telegram Android GPL-2.0, Signal-Android GPL-3.0, element-x-android AGPL-3.0.
  Copying any of them into a closed product is a licence violation. Bungram reimplements
  behaviour from scratch; `docs/05-DEEP-DIVE.md` records the behavioural findings only.
- **Static ≠ runtime.** JADX never executes the APK. A declared service may never start; a
  permission may never be used. Treat every claim as a hypothesis until a device test confirms it.
- **No traffic interception of other people's accounts.** Analyse your own installs and public
  artifacts only.
- **Record what you learned**, with the evidence id, in `docs/`.

---

## 7. What REA is not

It will not hand you a working feature. It shortens the "understand" phase from days to hours and
tells you where the risk is; the design and the code are still yours.
