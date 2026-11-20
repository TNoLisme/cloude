"""Run HTTP handoff checks against the disposable backend stack in compose.qa.yaml."""
import http.cookiejar
import json
import os
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get("QA_API_BASE", "http://localhost:18080/api/v1")
MAILBOX = os.environ.get("QA_SMS_BASE", "http://127.0.0.1:18081")
PASSWORD = os.environ["APP_QA_PASSWORD"]
MAILBOX_TOKEN = os.environ["QA_SMS_TOKEN"]


def request(opener, path, expected, method="GET", payload=None, headers=None):
    body = None if payload is None else json.dumps(payload).encode()
    request_headers = dict(headers or {})
    if payload is not None:
        request_headers["Content-Type"] = "application/json"
    req = urllib.request.Request(BASE + path, data=body, headers=request_headers, method=method)
    try:
        response = opener.open(req, timeout=15)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        data = response.read()
        content_type = response.headers.get("Content-Type", "")
        if data and content_type.startswith("application/x-ndjson"):
            result = [json.loads(line) for line in data.splitlines()]
        elif data and "json" in content_type:
            result = json.loads(data)
        else:
            result = data
        code = result.get("code", "") if isinstance(result, dict) else ""
        assert response.status == expected, f"{method} {path}: {response.status} {code}, expected {expected}"
        return result, response.headers


def login(username, phone):
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    challenge, _ = request(opener, "/auth/login", 202, "POST", {
        "username": username,
        "password": PASSWORD,
        "deviceFingerprint": str(uuid.uuid4()),
    })
    mailbox = urllib.request.Request(
        MAILBOX + "/messages?" + urllib.parse.urlencode({"destination": phone}),
        headers={"X-QA-SMS-Token": MAILBOX_TOKEN},
    )
    with urllib.request.urlopen(mailbox, timeout=10) as response:
        otp = next(message["otp"] for message in json.load(response) if message["type"] == "OTP")
    request(opener, "/auth/otp/verify", 200, "POST", {"challengeId": challenge["challenge"]["challengeId"], "otp": otp})
    session, _ = request(opener, "/auth/session", 200)
    return opener, {"X-CSRF-Token": session["session"]["csrfToken"]}


def main():
    sender, sender_headers = login("qa-sender", "0900000011")
    receiver, _ = login("qa-receiver", "0900000012")
    operations, ops_headers = login("qa-maker", "0900000021")
    auditor, _ = login("qa-auditor", "0900000024")
    profile, _ = request(sender, "/customers/me", 200)
    accounts, _ = request(sender, "/accounts", 200)
    account_id = accounts["items"][0]["accountId"]
    account, _ = request(sender, "/accounts/" + account_id, 200)
    assert account["owner"] == {"customerId": profile["customerId"], "status": "ACTIVE"}
    request(auditor, "/admin/customers/" + profile["customerId"], 403)
    receiver_profile, _ = request(receiver, "/customers/me", 200)
    denied, _ = request(sender, "/transactions?customerId=" + receiver_profile["customerId"], 403)
    denial_audit, _ = request(auditor, "/audit?correlationId=" + denied["correlationId"], 200)
    assert any(event["action"] == "SECURITY_ACCESS_DENIED" for event in denial_audit["items"])
    request(sender, "/limits?accountId=" + account_id, 200)

    payload = {
        "sourceAccountId": account_id,
        "destination": {"accountNumber": "000000000012"},
        "amount": "2000.00",
        "currency": "VND",
        "description": "QA handoff",
    }
    for _ in range(7):
        transfer_headers = {**sender_headers, "Idempotency-Key": str(uuid.uuid4())}
        receipt, _ = request(sender, "/transfers", 201, "POST", payload, transfer_headers)
        assert receipt["status"] == "COMPLETED"
    reference = receipt["reference"]
    replay, headers = request(sender, "/transfers", 201, "POST", payload, transfer_headers)
    assert replay == receipt and headers["Idempotent-Replayed"] == "true"
    readback, _ = request(receiver, "/transfers/" + reference, 200)
    assert readback["reference"] == reference and "fraudFlags" not in readback
    detail, _ = request(receiver, "/transactions/" + reference, 200)
    assert detail["direction"] == "CREDIT" and detail["status"] == "COMPLETED"
    history, _ = request(sender, f"/transactions?accountId={account_id}&direction=DEBIT", 200)
    assert any(item["reference"] == reference for item in history["items"])

    flags, _ = request(auditor, "/fraud-flags?reference=" + reference, 200)
    flag = next(flag for flag in flags["items"] if flag["rule"]["type"] == "HIGH_FREQUENCY")
    review_path = "/fraud-flags/" + flag["flagId"] + "/review"
    request(sender, review_path, 403, "POST", {"decision": "ALLOW"}, sender_headers)
    reviewed, _ = request(operations, review_path, 200, "POST", {"decision": "ALLOW"}, ops_headers)
    assert reviewed["status"] == "ALLOWED" and reviewed["reviewedBy"]["userId"]
    request(operations, review_path, 409, "POST", {"decision": "ALLOW"}, ops_headers)

    events, _ = request(auditor, "/audit?reference=" + reference, 200)
    completion = next(event for event in events["items"] if event["action"] == "TRANSFER_COMPLETED")
    audit, _ = request(auditor, "/audit/" + completion["auditId"], 200)
    assert len(audit["recordHash"]) == 64
    export, headers = request(auditor, "/audit/export?format=CSV&reference=" + reference, 200)
    assert headers["Content-Type"].startswith("text/csv") and reference.encode() in export
    jsonl, _ = request(auditor, "/audit/export?format=JSONL&reference=" + reference, 200)
    assert any(event["action"] == "TRANSFER_COMPLETED" for event in jsonl)
    request(auditor, "/audit/export?format=CSV&maxRows=1&reference=" + reference, 413)
    reviews, _ = request(auditor, "/audit?action=FRAUD_FLAG_REVIEWED&entityId=" + flag["flagId"], 200)
    assert len(reviews["items"]) == 1
    exports, _ = request(auditor, "/audit?action=AUDIT_EXPORTED", 200)
    assert exports["page"]["totalElements"] > 0
    request(sender, "/auth/logout", 403, "POST")
    request(sender, "/auth/logout", 204, "POST", headers=sender_headers)
    request(sender, "/auth/session", 401)
    print("PASS OTP, scope/CSRF, Transfer/replay, History, Fraud review, Audit export and logout")


if __name__ == "__main__":
    for endpoint in (BASE, MAILBOX):
        if urllib.parse.urlparse(endpoint).hostname not in {"localhost", "127.0.0.1", "::1"}:
            raise RuntimeError("QA smoke requires a disposable loopback backend and SMS mailbox")
    main()
