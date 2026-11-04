#!/usr/bin/env python3
"""Phase 3 protocol test: groups, channels, membership, invites, stickers, devices, pre-keys.

Run against a live dev server:  python3 tools/e2e_groups.py
Exits non-zero on the first failed check.
"""
import json
import os
import random
import sys
import urllib.error
import urllib.request

BASE = os.environ.get("BUNGRAM_BASE", "http://127.0.0.1:8080")
RUN = random.randint(100000, 999999)
checks = 0
failures = []


def call(method, path, body=None, token=None, expect=200):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            status, payload = r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        status, payload = e.code, e.read().decode()
    if status != expect:
        raise AssertionError(f"{method} {path} -> {status} (want {expect}): {payload[:200]}")
    return json.loads(payload) if payload else {}


def check(name, fn):
    global checks
    try:
        fn()
        checks += 1
        print(f"PASS  {name}")
    except AssertionError as e:
        failures.append(f"{name}: {e}")
        print(f"FAIL  {name}  <- {e}")


def login(suffix):
    phone = f"+62812{RUN}{suffix}"
    ch = call("POST", "/v1/auth/otp", {"phone": phone, "deviceId": f"dev-{RUN}-{suffix}"})["data"]
    s = call("POST", "/v1/auth/verify", {"challengeId": ch["challengeId"], "code": ch["devCode"]})["data"]
    return {"token": s["accessToken"], "id": s["userId"], "phone": phone, "name": s["user"]["displayName"]}


def main():
    a = login("1")
    b = login("2")
    c = login("3")
    print(f"users: A={a['id'][:8]} B={b['id'][:8]} C={c['id'][:8]}")

    group = {}

    def create_group():
        d = call("POST", "/v1/chats/create", {
            "title": f"Design team {RUN}", "description": "Phase 3 smoke", "memberIds": [b["id"]],
        }, a["token"])["data"]
        assert d["kind"] == "GROUP", d
        assert d["myRole"] == "OWNER", d
        assert d["membersCount"] == 2, d["membersCount"]
        group.update(d)

    check("group: create with one member", create_group)

    def group_visible_to_member():
        chats = call("GET", "/v1/chats", token=b["token"])["data"]
        ids = [x["id"] for x in chats]
        assert group["id"] in ids, "group missing from B's chat list"
        mine = next(x for x in chats if x["id"] == group["id"])
        assert mine["myRole"] == "MEMBER", mine["myRole"]
        assert mine["canPost"] is True, "members must be able to post in a group"

    check("group: appears for the other member with MEMBER role", group_visible_to_member)

    def member_can_post():
        m = call("POST", "/v1/messages", {
            "chatId": group["id"], "clientId": f"g-{RUN}-1", "ciphertext": "hello team",
        }, b["token"])["data"]
        assert m["chatId"] == group["id"]

    check("group: member can post", member_can_post)

    def member_cannot_manage():
        call("POST", f"/v1/chats/{group['id']}/members", {"userIds": [c["id"]]}, b["token"], expect=403)

    check("group: plain member cannot add people", member_cannot_manage)

    def admin_adds_member():
        members = call("POST", f"/v1/chats/{group['id']}/members", {"userIds": [c["id"]]}, a["token"])["data"]
        assert any(m["user"]["id"] == c["id"] for m in members), "C not in member list"
        assert len(members) == 3, len(members)

    check("group: owner adds a member", admin_adds_member)

    def system_message_created():
        msgs = call("GET", f"/v1/chats/{group['id']}/messages", token=c["token"])["data"]
        assert any(m["kind"] == "SYSTEM" and "joined" in m["text"] for m in msgs), [m["kind"] for m in msgs]

    check("group: join produces a system message", system_message_created)

    def promote_and_demote():
        call("POST", f"/v1/chats/{group['id']}/members/{c['id']}/role?role=ADMIN", {}, a["token"])
        detail = call("GET", f"/v1/chats/{group['id']}", token=a["token"])["data"]
        role = next(m["role"] for m in detail["members"] if m["user"]["id"] == c["id"])
        assert role == "ADMIN", role
        call("POST", f"/v1/chats/{group['id']}/members/{c['id']}/role?role=MEMBER", {}, a["token"])

    check("group: owner promotes and demotes an admin", promote_and_demote)

    def owner_immutable():
        call("DELETE", f"/v1/chats/{group['id']}/members/{a['id']}", token=c["token"], expect=403)

    check("group: admin cannot remove the owner", owner_immutable)

    def kick():
        call("DELETE", f"/v1/chats/{group['id']}/members/{c['id']}", token=a["token"])
        chats = [x["id"] for x in call("GET", "/v1/chats", token=c["token"])["data"]]
        assert group["id"] not in chats, "removed member still sees the chat"
        call("POST", "/v1/messages", {"chatId": group["id"], "ciphertext": "still here?"}, c["token"], expect=403)

    check("group: removed member loses access immediately", kick)

    invite = {}

    def mint_invite():
        d = call("POST", f"/v1/chats/{group['id']}/invite", {}, a["token"])["data"]
        assert len(d["slug"]) >= 12, d
        assert d["url"].endswith(d["slug"]), d
        invite.update(d)

    check("invite: admin mints a slug", mint_invite)

    def preview():
        p = call("GET", f"/v1/invites/{invite['slug']}", token=c["token"])["data"]
        assert p["title"] == group["title"], p
        assert p["alreadyMember"] is False, p
        assert p["membersCount"] == 2, p["membersCount"]

    check("invite: preview shows the chat before joining", preview)

    def join():
        chat = call("POST", f"/v1/invites/{invite['slug']}/join", {}, c["token"])["data"]
        assert chat["id"] == group["id"]
        assert chat["myRole"] == "MEMBER"
        again = call("POST", f"/v1/invites/{invite['slug']}/join", {}, c["token"])["data"]
        assert again["id"] == chat["id"], "re-join must be idempotent"

    check("invite: join works and is idempotent", join)

    def revoke():
        call("DELETE", f"/v1/chats/{group['id']}/invite", token=a["token"])
        call("GET", f"/v1/invites/{invite['slug']}", token=b["token"], expect=404)

    check("invite: revoke kills the link", revoke)

    channel = {}

    def create_channel():
        d = call("POST", "/v1/chats/create", {
            "title": f"Bungram News {RUN}", "kind": "CHANNEL", "description": "Announcements",
        }, a["token"])["data"]
        assert d["kind"] == "CHANNEL", d
        assert d["myRole"] == "OWNER"
        channel.update(d)

    check("channel: create", create_channel)

    def channel_readonly():
        call("POST", f"/v1/chats/{channel['id']}/members", {"userIds": [b["id"]]}, a["token"])
        call("POST", "/v1/messages", {
            "chatId": channel["id"], "clientId": f"ch-{RUN}-1", "ciphertext": "release notes",
        }, a["token"])
        call("POST", "/v1/messages", {"chatId": channel["id"], "ciphertext": "me too"}, b["token"], expect=403)
        msgs = call("GET", f"/v1/chats/{channel['id']}/messages", token=b["token"])["data"]
        assert any(m["text"] == "release notes" for m in msgs), "subscriber cannot read the post"

    check("channel: only admins post, subscribers read", channel_readonly)

    def rename():
        d = call("PATCH", f"/v1/chats/{channel['id']}", {"title": f"Bungram Daily {RUN}"}, a["token"])["data"]
        assert d["title"] == f"Bungram Daily {RUN}", d["title"]
        call("PATCH", f"/v1/chats/{channel['id']}", {"title": "nope"}, b["token"], expect=403)

    check("channel: rename is admin-only", rename)

    def leave():
        call("DELETE", f"/v1/chats/{channel['id']}/members/{b['id']}", token=b["token"])
        chats = [x["id"] for x in call("GET", "/v1/chats", token=b["token"])["data"]]
        assert channel["id"] not in chats

    check("channel: subscriber can leave", leave)

    def delete_group():
        call("DELETE", f"/v1/chats/{group['id']}", token=c["token"], expect=403)
        call("DELETE", f"/v1/chats/{group['id']}", token=a["token"])
        call("GET", f"/v1/chats/{group['id']}", token=a["token"], expect=404)

    check("group: only the owner deletes, and it disappears", delete_group)

    def stickers():
        d = call("GET", "/v1/stickers", token=a["token"])["data"]
        assert len(d["packs"]) == 6, len(d["packs"])
        total = sum(len(p["stickers"]) for p in d["packs"])
        assert total == 180, total

    check("stickers: catalog serves 6 packs / 180 stickers", stickers)

    def devices():
        call("POST", "/v1/devices", {"deviceId": "dev-A", "provider": "ntfy", "pushToken": "topicA"}, a["token"])
        d = call("GET", "/v1/devices?current=dev-A", token=a["token"])["data"]
        assert any(x["id"] == "dev-A" and x["current"] for x in d), d
        call("DELETE", "/v1/devices/dev-A", token=a["token"])
        d2 = call("GET", "/v1/devices", token=a["token"])["data"]
        assert not any(x["id"] == "dev-A" for x in d2), d2

    check("devices: register, list, revoke", devices)

    def prekeys():
        bundle = {
            "userId": a["id"], "deviceId": "dev-A", "registrationId": 1,
            "identityKey": "aWRlbnRpdHk=", "identitySignKey": "c2lnbmluZw==", "signedPreKeyId": 1,
            "signedPreKey": "c2lnbmVk", "signedPreKeySignature": "c2ln",
        }
        otks = [{"id": i, "key": f"b3Rw{i:04d}"} for i in range(1, 21)]
        ack = call("POST", "/v1/keys", {"bundle": bundle, "oneTimePreKeys": otks}, a["token"])["data"]
        assert ack["count"] == 20, ack

        got = call("GET", f"/v1/keys/{a['id']}", token=b["token"])["data"]
        assert got["identityKey"] == bundle["identityKey"], got
        assert got["identitySignKey"] == bundle["identitySignKey"], got
        call("POST", "/v1/keys", {"bundle": dict(bundle, userId=b["id"])}, a["token"], expect=403)

        # Each fetch must burn a different one-time key, or two senders would share a secret.
        first = call("GET", f"/v1/keys/{a['id']}", token=b["token"])["data"]
        second = call("GET", f"/v1/keys/{a['id']}", token=c["token"])["data"]
        assert first["oneTimePreKeyId"] != second["oneTimePreKeyId"], (first, second)
        left = call("GET", "/v1/keys/status?deviceId=dev-A", token=a["token"])["data"]["count"]
        assert left == 17, left

        # A pool that runs dry must still answer, just without a one-time key (last resort).
        for _ in range(17):
            call("GET", f"/v1/keys/{a['id']}", token=b["token"])
        dry = call("GET", f"/v1/keys/{a['id']}", token=b["token"])["data"]
        assert dry.get("oneTimePreKey") is None, dry

        call("GET", "/v1/keys/nobody-here", token=b["token"], expect=404)

    check("pre-keys: publish pool, one-time keys are consumed once", prekeys)

    print()
    if failures:
        print(f"{checks} passed, {len(failures)} FAILED")
        for f in failures:
            print("  -", f)
        sys.exit(1)
    print(f"ALL {checks} CHECKS PASSED")


if __name__ == "__main__":
    main()
