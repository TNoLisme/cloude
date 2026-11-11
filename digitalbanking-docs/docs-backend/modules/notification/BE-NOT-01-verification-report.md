# BE-NOT-01 — Verification report

> **Trạng thái:** Planned — chưa chạy  
> **Task spec:** [BE-NOT-01-transactional-outbox.md](BE-NOT-01-transactional-outbox.md)

## Verification matrix

| Check | Lệnh / thủ tục | Expected | Actual | Status |
|---|---|---|---|---|
| Payload masking | Unit tests với synthetic PII patterns | Reject raw account/phone/email/OTP; payload chỉ có allowlisted fields | Chưa chạy | Planned |
| T2 rollback | Testcontainers + injected transaction failure | T2 rollback để lại zero notification rows | Chưa chạy | Planned |
| Claim concurrency | Hai PostgreSQL connections độc lập | Một row chỉ có một active lease | Chưa chạy | Planned |
| Gateway failure | Fake adapter throw/timeout | Transfer/ledger/audit không đổi; row retry/FAILED đúng policy | Chưa chạy | Planned |
| Lease recovery | Claim rồi dừng worker; expire lease theo DB time | Worker khác reclaim; stale worker không overwrite | Chưa chạy | Planned |
| Duplicate-send window | Gateway fake success rồi crash trước mark SENT | Retry có thể duplicate; money state không đổi; không claim exactly-once | Chưa chạy | Planned |
| Retry terminal state | Inject failure đến max attempts | Backoff đúng; cuối cùng FAILED; metric/exhaustion evidence | Chưa chạy | Planned |
| Polling latency | Đo enqueue-to-send và backlog age dưới workload/config ghi lại | p50/p95/p99 có số liệu; SLO chỉ so khi nhóm chốt | Chưa chạy; chưa có SLO | Planned |
| OTP boundary | Identity integration và scan synthetic artifacts/logs | OTP plaintext không được persist/log; auth outcome không báo success sai | Chưa chạy | Planned |

## Evidence

Revision/môi trường/log: chưa có. Không có code hoặc runtime verification lúc tạo report. Component tests và integration tests phải báo riêng.

## Open items

Chốt port DTO với Vũ, lease/dedupe schema, retry budget, provider idempotency và OTP boundary với Đạt. Không đánh dấu Verified nếu chưa chạy PostgreSQL Testcontainers. Gateway fake không chứng minh provider dedupe hoặc delivery guarantee.
