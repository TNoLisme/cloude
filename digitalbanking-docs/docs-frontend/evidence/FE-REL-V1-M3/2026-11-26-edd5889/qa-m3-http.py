"""Disposable M3 HTTP checks against the BE QA stack; synthetic users only."""
import importlib.util
from pathlib import Path

root = Path(__file__).resolve().parents[5]
spec = importlib.util.spec_from_file_location("qa_api_smoke", root / "digitalbanking-backend" / "scripts" / "qa-api-smoke.py")
assert spec and spec.loader
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
login, request = module.login, module.request

sender, _ = login("qa-sender", "0900000011")
receiver, _ = login("qa-receiver", "0900000012")
receiver_accounts, _ = request(receiver, "/accounts", 200)
foreign_account = receiver_accounts["items"][0]["accountId"]
request(sender, "/transactions?accountId=" + foreign_account, 403)
request(sender, "/limits?accountId=" + foreign_account, 403)
request(sender, "/transactions/TXN20261126AAAAAAAA", 404)
request(sender, "/transactions?direction=DEBIT", 400)
page, _ = request(sender, "/transactions?status=COMPLETED&page=0&size=1", 200)
assert page["page"]["number"] == 0 and page["page"]["size"] == 1
second, _ = request(sender, "/transactions?status=COMPLETED&page=1&size=1", 200)
assert second["page"]["number"] == 1 and second["items"]
empty, _ = request(sender, "/transactions?from=2099-01-01T00%3A00%3A00Z&to=2099-01-02T00%3A00%3A00Z", 200)
assert empty["items"] == []
limits, _ = request(sender, "/limits", 200)
assert limits["currency"] == "VND" and isinstance(limits["items"], list)
print("PASS M3 HTTP: list/filter/page/empty UTC range, limits, foreign scope 403, missing detail 404")
