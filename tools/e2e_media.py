#!/usr/bin/env python3
"""
Bungram — media pipeline end-to-end test (phase 2).

Runs against a live server, no emulator and no Android SDK required:

    BUNGRAM_DEV=1 ./gradlew run          # in server/
    python3 tools/e2e_media.py

Covers the whole contract: upload → server-side probing → de-duplication → auth/ACL →
range requests → thumbnails → attachment embedding → sync → share links → delete.
"""
import io
import os
import random
import sys

import requests
from PIL import Image

BASE = os.environ.get("BUNGRAM_BASE", "http://127.0.0.1:8080").rstrip("/")
RUN = random.randint(100_000, 999_999)

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS  " if ok else "FAIL  ") + name + (f"  [{detail}]" if detail else ""))
    return ok


def login(phone):
    otp = requests.post(
        f"{BASE}/v1/auth/otp",
        json={"phone": phone, "deviceName": "e2e-media", "deviceId": f"dev-{RUN}"},
        timeout=15,
    )
    otp.raise_for_status()
    data = otp.json()["data"]
    assert data.get("devCode"), "server did not return a dev OTP — start it with BUNGRAM_DEV=1"
    verify = requests.post(
        f"{BASE}/v1/auth/verify",
        json={"challengeId": data["challengeId"], "code": data["devCode"]},
        timeout=15,
    )
    verify.raise_for_status()
    session = verify.json()["data"]
    return session["accessToken"], session["userId"]


def auth(token):
    return {"Authorization": f"Bearer {token}"}


def png(width=800, height=600, colour=(30, 120, 200)):
    buf = io.BytesIO()
    Image.new("RGB", (width, height), colour).save(buf, "PNG")
    return buf.getvalue()


def upload(token, payload, filename="shot.png", mime="image/png", extra=None):
    files = {"file": (filename, payload, mime)}
    data = extra or {}
    return requests.post(f"{BASE}/v1/media", headers=auth(token), files=files, data=data, timeout=60)


def main():
    token_a, uid_a = login(f"+91 90{RUN} 11111")
    token_b, uid_b = login(f"+91 90{RUN} 22222")
    check("login: two independent accounts", bool(token_a) and bool(token_b))

    image = png()
    r = upload(token_a, image, extra={"width": 800, "height": 600})
    check("upload: 201/200 with MediaDto", r.status_code == 200, f"HTTP {r.status_code}")
    if r.status_code != 200:
        print(r.text[:400])
        return finish()
    media = r.json()["data"]
    mid = media["id"]

    check("upload: real dimensions probed by the server",
          media["width"] == 800 and media["height"] == 600, f'{media["width"]}x{media["height"]}')
    check("upload: sha256 computed", len(media.get("sha256", "")) == 64)
    check("upload: thumbnail generated", bool(media.get("thumbUrl")))
    check("upload: colour hint for the placeholder",
          bool(media.get("colorHint")) and media["colorHint"].startswith("#"), media.get("colorHint"))

    # --- download -------------------------------------------------------------------------
    dl = requests.get(f"{BASE}{media['url']}", headers=auth(token_a), timeout=30)
    check("download: owner can read the blob", dl.status_code == 200 and dl.content == image,
          f"HTTP {dl.status_code}, {len(dl.content)}B")
    check("download: content-type is image/png",
          dl.headers.get("Content-Type", "").startswith("image/png"), dl.headers.get("Content-Type"))
    check("download: ETag present", bool(dl.headers.get("ETag")))

    rng = requests.get(f"{BASE}{media['url']}", headers={**auth(token_a), "Range": "bytes=0-99"}, timeout=30)
    check("download: range request returns 206 + 100 bytes",
          rng.status_code == 206 and len(rng.content) == 100, f"HTTP {rng.status_code}, {len(rng.content)}B")

    thumb = requests.get(f"{BASE}{media['thumbUrl']}", headers=auth(token_a), timeout=30)
    check("thumbnail: served as JPEG", thumb.status_code == 200 and thumb.content[:2] == b"\xff\xd8",
          f"HTTP {thumb.status_code}")

    anon = requests.get(f"{BASE}{media['url']}", timeout=15)
    check("ACL: anonymous request rejected", anon.status_code in (401, 403), f"HTTP {anon.status_code}")

    other = requests.get(f"{BASE}{media['url']}", headers=auth(token_b), timeout=15)
    check("ACL: stranger without a shared chat is forbidden", other.status_code == 403, f"HTTP {other.status_code}")

    # --- de-duplication -------------------------------------------------------------------
    again = upload(token_a, image, extra={"width": 800, "height": 600})
    check("dedupe: identical upload returns the same media id",
          again.status_code == 200 and again.json()["data"]["id"] == mid,
          again.json()["data"]["id"] if again.status_code == 200 else f"HTTP {again.status_code}")

    # --- quota ----------------------------------------------------------------------------
    quota = requests.get(f"{BASE}/v1/media/quota", headers=auth(token_a), timeout=15)
    q = quota.json()["data"] if quota.status_code == 200 else {}
    check("quota: usage accounted", q.get("usedBytes", 0) >= len(image), f'{q.get("usedBytes")}B used')
    check("quota: limit advertised", q.get("limitBytes", 0) > 0 and q.get("maxFileBytes", 0) > 0)

    # --- attachment in a message ----------------------------------------------------------
    chats = requests.get(f"{BASE}/v1/chats", headers=auth(token_a), timeout=15).json()["data"]
    chat_id = chats[0]["id"]
    send = requests.post(
        f"{BASE}/v1/messages",
        headers=auth(token_a),
        json={"chatId": chat_id, "kind": "IMAGE", "ciphertext": "look at this", "attachmentId": mid,
              "clientId": f"e2e-{RUN}"},
        timeout=20,
    )
    check("message: send with attachment", send.status_code == 200, f"HTTP {send.status_code}")
    msg = send.json()["data"] if send.status_code == 200 else {}
    att = msg.get("attachment") or {}
    check("message: attachment carries server-measured metadata",
          att.get("mediaId") == mid and att.get("width") == 800 and att.get("sizeBytes", 0) > 0,
          f'{att.get("width")}x{att.get("height")}, {att.get("sizeBytes")}B')
    check("message: attachment has a thumbnail url", bool(att.get("thumbUrl")))

    sync = requests.get(f"{BASE}/v1/sync", headers=auth(token_a), params={"cursor": 0}, timeout=20)
    synced = [m for m in sync.json()["data"]["messages"] if m["id"] == msg.get("id")]
    check("sync: message with attachment round-trips", len(synced) == 1)

    # --- share link -----------------------------------------------------------------------
    link = requests.get(f"{BASE}/v1/media/{mid}/link", headers=auth(token_a), timeout=15)
    signed = link.json()["data"]["url"] if link.status_code == 200 else ""
    if signed:
        shared = requests.get(f"{BASE}{signed}", timeout=20)
        check("share link: signed url works without a token",
              shared.status_code == 200 and shared.content == image, f"HTTP {shared.status_code}")
        tampered = requests.get(f"{BASE}{signed[:-4]}AAAA", timeout=15)
        check("share link: tampered signature rejected", tampered.status_code in (401, 403),
              f"HTTP {tampered.status_code}")
    else:
        check("share link: endpoint available", False, f"HTTP {link.status_code}")

    # --- rejection paths ------------------------------------------------------------------
    empty = upload(token_a, b"", filename="empty.bin", mime="application/octet-stream")
    check("upload: empty file rejected", empty.status_code == 400, f"HTTP {empty.status_code}")

    # --- delete ---------------------------------------------------------------------------
    dele = requests.delete(f"{BASE}/v1/media/{mid}", headers=auth(token_a), timeout=15)
    check("delete: owner can delete", dele.status_code == 200)
    gone = requests.get(f"{BASE}{media['url']}", headers=auth(token_a), timeout=15)
    check("delete: blob is gone afterwards", gone.status_code == 404, f"HTTP {gone.status_code}")

    return finish()


def finish():
    passed = sum(1 for _, ok in results if ok)
    total = len(results)
    print(f"\n{passed}/{total} checks passed")
    return 0 if passed == total else 1


if __name__ == "__main__":
    sys.exit(main())
