# Cloud Verification và Acceptance

> **Mã:** BE-CLOUD-14  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed

## 1. Verification levels

| Level | Evidence |
|---|---|
| Infrastructure | Provider resource, region, firewall, DNS |
| Runtime | Docker, Nginx, Backend, DB health |
| Contract | OpenAPI, HTTP status/header/body |
| Security | TLS, cookie, CSRF, RBAC, no secret/PII leak |
| Data | Flyway, ledger, audit, backup/restore |
| Browser | FE journey, deep-link, responsive, session |
| Performance | D5 metrics và financial invariants |

## 2. DVT mapping

`DVT-001`–`DVT-010` giữ nguyên ý nghĩa chuẩn tại `docs-common/08. Deployment infrastructure.md` §11. Cloud-specific checks nằm trong procedure/evidence, không đổi nghĩa mã gốc.

| ID chuẩn | Cloud procedure | Expected |
|---|---|---|
| DVT-001 | Review profile, required variables, environment boundary and secret rules | Configuration contract complete |
| DVT-002 | Review Browser/Nginx/Frontend/Backend/DB/storage/monitoring topology, responsibility and failure behavior | Every logical component has defined responsibility/failure behavior |
| DVT-003 | Health and critical smoke | Health `UP`, no secret leak |
| DVT-004 | Review Flyway forward-only policy and clean install/upgrade | Migration policy and compatibility evidence complete |
| DVT-005 | Backup/restore exercise with RTO/RPO measurement | Restore completes within target or variance recorded |
| DVT-006 | Failure injection/recovery exercise | No false success, duplicate debit or ledger mismatch |
| DVT-007 | Observability review | Structured logs, metrics, alerts and runbook links complete |
| DVT-008 | Security configuration review | Secret, encryption, cookie/CSRF and masking controls pass |
| DVT-009 | Release/rollback review | Artifact, migration, rollback and evidence gates pass |
| DVT-010 | Cost/scale review | Workload assumptions, scale triggers and measurement method recorded |

Additional checks such as artifact digest, firewall, DNS and provider resource are evidence inputs to the DVT rows above, not new meanings for DVT IDs.
## 3. Cloud smoke sequence

1. Resolve domain.
2. Open HTTPS.
3. Load Frontend assets.
4. Test SPA deep-link.
5. Login/challenge.
6. Reload and verify session.
7. Read account with masked data.
8. Transfer synthetic amount.
9. Trigger `428 STEP_UP_REQUIRED` if fixture supports it.
10. Retry same key/body.
11. Read transaction history.
12. Operations reads/reviews fraud flag.
13. Auditor reads audit.
14. Logout.
15. Unauthorized direct URL/API expects `403`.
16. Check logs/metrics.

## 4. D1–D5 mapping

- **D1:** Concurrency; balances, ledger pairs, audit rows, no negative balance.
- **D2:** Idempotency; same key/body one financial effect; conflict no second effect.
- **D3:** Recovery; timeout/crash/reconcile matches contract.
- **D4:** Security/audit/fraud; RBAC, ownership, masking, append-only, review.
- **D5:** `20 RPS × 10 phút`; p95, p99, error rate, EC2 CPU/memory/disk, RDS connections/storage, application DB pool và financial invariants. k6 execution environment, region, script path, authentication fixture, seed version và output file phải ghi trong evidence.

D5 metric source:

- k6 JSON: latency, request count, error rate và thresholds.
- CloudWatch: EC2 CPU/network/disk, RDS health/connections/storage và logs.
- Actuator/Micrometer: application/DB pool metrics.

Nếu load generator chạy ngoài Cloud region, ghi rõ kết quả bao gồm Internet latency và không dùng kết quả đó để suy ra backend-only capacity mà không có caveat.
Real PostgreSQL/provider resources bắt buộc cho claims về locking, persistence, backup, restore. Mock evidence gắn nhãn `Mock` và không close deployment gate.

## 5. Status

- `Pass`: expected/actual match, evidence attached.
- `Fail`: executed và mismatch.
- `Blocked`: dependency/environment missing.
- `Deferred`: owner-approved scope deferral có reopen trigger.

Không đổi `Blocked` thành `Pass` bằng cách bỏ qua test.
