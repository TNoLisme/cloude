"""Disposable M4 read-only HTTP checks against the local synthetic QA stack."""
import importlib.util
import os
from pathlib import Path

smoke_path = Path(__file__).resolve().parents[5] / "digitalbanking-backend" / "scripts" / "qa-api-smoke.py"
spec = importlib.util.spec_from_file_location("banking_qa_smoke", smoke_path)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

sender, _ = smoke.login("qa-sender", "0900000011")
ops, _ = smoke.login("qa-maker", "0900000021")
admin, _ = smoke.login("qa-admin", "0900000022")
auditor, _ = smoke.login("qa-auditor", "0900000024")
profile, _ = smoke.request(sender, "/customers/me", 200)
customer_id = profile["customerId"]

for actor in (ops, admin):
    smoke.request(actor, f"/admin/customers/{customer_id}", 200)
for actor in (sender, auditor):
    smoke.request(actor, f"/admin/customers/{customer_id}", 403)
for actor in (ops, admin, auditor):
    smoke.request(actor, f"/admin/customers/{customer_id}/accounts", 200)
smoke.request(sender, f"/admin/customers/{customer_id}/accounts", 403)

for actor in (ops, admin):
    smoke.request(actor, "/admin/approvals", 200)
    smoke.request(actor, "/admin/approvals/999999999", 404)
for actor in (sender, auditor):
    smoke.request(actor, "/admin/approvals", 403)

for path in ("/fraud-flags", "/audit"):
    for actor in (ops, admin, auditor):
        smoke.request(actor, path, 200)
    smoke.request(sender, path, 403)
for actor in (ops, admin, auditor):
    smoke.request(actor, "/audit/999999999", 404)

print("PASS M4 HTTP: staff read roles, customer/auditor denial, approval/audit 404")
