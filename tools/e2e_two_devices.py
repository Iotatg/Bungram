#!/usr/bin/env python3
"""
Bungram end-to-end test: two devices, one server, no emulator required.

It exercises the exact contract the Android client speaks:

  1. two accounts log in with OTP
  2. A resolves B's phone number and opens a chat (idempotent: twice -> one thread)
  3. B pushes a message, A receives it over the websocket
  4. A reads it, B receives the read receipt over its own websocket
  5. the same clientId sent twice creates exactly one message (idempotency)
  6. the sync cursor is monotonic and returns nothing when there is nothing new

Run:  python3 tools/e2e_two_devices.py [base_url]
"""
import asyncio
import json
import os
import random
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080").rstrip("/")
# Every run gets its own accounts, otherwise the server's idempotency index (correctly) answers
# a repeated clientId with the message from the previous run and nothing is broadcast.
RUN = os.environ.get("E2E_RUN") or str(random.randint(100000, 999999))


def call(method, path, body=None, token=None, expect=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        payload = e.read().decode()
        if expect and e.code == expect:
            return {"http": e.code, "body": payload}
        raise SystemExit(f"FAIL {method} {path} -> {e.code} {payload}")


def login(phone):
    challenge = call("POST", "/v1/auth/otp", {"phone": phone, "deviceName": "e2e", "deviceId": phone})["data"]
    session = call(
        "POST",
        "/v1/auth/verify",
        {
            "challengeId": challenge["challengeId"],
            "code": challenge["devCode"],
            "identityKey": "",
            "signedPreKey": "",
            "oneTimePreKeys": [],
        },
    )["data"]
    return session


async def main():
    ok = 0
    print("== 1. login ==")
    a = login(f"+62 811 9{RUN}")
    b = login(f"+62 822 9{RUN}")
    print(f"   A={a['user']['displayName']} ({a['userId']})")
    print(f"   B={b['user']['displayName']} ({b['userId']})")
    assert a["userId"] != b["userId"]
    ok += 1

    print("== 2. lookup + open chat ==")
    peer = call("GET", f"/v1/users/lookup?phone={urllib.parse.quote('+62 822 9' + RUN)}", token=a["accessToken"])
    assert peer["data"]["id"] == b["userId"], peer
    chat1 = call("POST", "/v1/chats", {"peerId": b["userId"]}, a["accessToken"])["data"]
    chat2 = call("POST", "/v1/chats", {"peerId": b["userId"]}, a["accessToken"])["data"]
    assert chat1["id"] == chat2["id"], "duplicate chat created"
    print(f"   chat={chat1['id']} (idempotent)")
    ok += 1

    CLIENT_ID = f"e2e-{RUN}"
    print("== 3. websocket delivery ==")
    import websockets

    def ws_url(token):
        return BASE.replace("http://", "ws://").replace("https://", "wss://") + f"/ws?token={token}"

    async def wait_for(ws, kind, timeout=12.0):
        """Reads frames until one of `kind` arrives. Ignores presence/pong noise."""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout
        while loop.time() < deadline:
            raw = await asyncio.wait_for(ws.recv(), timeout=max(0.5, deadline - loop.time()))
            evt = json.loads(raw)
            if evt.get("type") == kind:
                return evt
        return None

    a_ws = await websockets.connect(ws_url(a["accessToken"]))
    b_ws = await websockets.connect(ws_url(b["accessToken"]))
    await asyncio.sleep(0.3)  # let the server register both connections

    sent = call(
        "POST",
        "/v1/messages",
        {"chatId": chat1["id"], "clientId": CLIENT_ID, "ciphertext": "Namaste Bungram"},
        b["accessToken"],
    )["data"]
    print(f"   B sent {sent['id']} seq={sent['seq']}")

    evt = await wait_for(a_ws, "message")
    assert evt and evt["chatId"] == chat1["id"], evt
    print(f"   A received ws event: {evt}")
    ok += 1

    print("== 4. sync + read receipt ==")
    delta = call("GET", "/v1/sync?cursor=0", token=a["accessToken"])["data"]
    names = {u["id"]: u["displayName"] for u in delta["users"]}
    assert b["userId"] in names, "peer profile missing from sync"
    print(f"   sync carries {len(delta['users'])} profiles (peer: {names[b['userId']]})")
    mine = [m for m in delta["messages"] if m["chatId"] == chat1["id"]]
    assert any(m["id"] == sent["id"] for m in mine), "message missing from sync"
    cursor = delta["nextCursor"]
    print(f"   A sees {len(mine)} message(s) in this chat, cursor={cursor}")

    call("POST", f"/v1/chats/{chat1['id']}/read", {}, a["accessToken"])
    receipt = await wait_for(b_ws, "receipt")
    assert receipt and receipt["messageId"] == sent["id"], receipt
    print(f"   B received receipt: {receipt}")
    ok += 1
    await a_ws.close()
    await b_ws.close()

    print("== 5. idempotent retry ==")
    again = call(
        "POST",
        "/v1/messages",
        {"chatId": chat1["id"], "clientId": CLIENT_ID, "ciphertext": "Namaste Bungram"},
        b["accessToken"],
    )["data"]
    assert again["id"] == sent["id"], f"duplicate created: {sent['id']} vs {again['id']}"
    after = call("GET", "/v1/sync?cursor=0", token=a["accessToken"])["data"]
    same = [m for m in after["messages"] if m.get("clientId") == CLIENT_ID]
    assert len(same) == 1, f"expected 1 message, got {len(same)}"
    print(f"   retry returned the same id, still 1 message")
    ok += 1

    print("== 6. cursor monotonic ==")
    d2 = call("GET", f"/v1/sync?cursor={cursor}", token=a["accessToken"])["data"]
    assert not [m for m in d2["messages"] if m["chatId"] == chat1["id"]], "cursor replayed old messages"
    assert int(d2["nextCursor"]) >= int(cursor), "cursor went backwards"
    print(f"   nothing new, cursor {cursor} -> {d2['nextCursor']}")
    ok += 1

    print(f"\nALL {ok}/6 CHECKS PASSED")


if __name__ == "__main__":
    import urllib.parse  # noqa: E402  (used inside main)

    asyncio.run(main())
