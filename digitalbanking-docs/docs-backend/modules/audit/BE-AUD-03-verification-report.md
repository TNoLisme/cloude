# BE-AUD-03 — Verification report

> **Trạng thái:** Planned — chưa chạy  
> **Task spec:** [BE-AUD-03-retention-legal-hold.md](BE-AUD-03-retention-legal-hold.md)

## Verification matrix

| Check | Lệnh / thủ tục | Expected | Actual | Status |
|---|---|---|---|---|
| Policy/approval review | Review với Đạt, Vũ, Phương | Retention/scope/privilege/API decisions explicit | Chưa review | Planned |
| Hold scope unit | Synthetic CUSTOMER/ACCOUNT/TRANSFER/AUDIT_LOG/DATA_SCOPE fixtures | Active/overlap hold resolved đúng | Chưa chạy | Planned |
| Purge runtime gate | Inspect scheduler/config/DB target | Purge disabled với shared/demo/prod; disposable target only | Chưa có code/deploy review | Planned |
| Archive/checkpoint failure | Testcontainers + inject failure | Không delete khi archive/checkpoint thiếu/corrupt | Chưa chạy | Planned |
| Hold/purge concurrency | Hai transaction PostgreSQL với lock protocol đã duyệt | Hold active luôn ngăn purge; không race | Chưa chạy | Planned |
| Audit/hold atomicity | Testcontainers + audit failure | Purge rollback; active hold không xóa row | Chưa chạy | Planned |
| Retained chain | Contiguous-prefix purge trong Testcontainers | Hash chain verify từ checkpoint; không xóa giữa chain | Chưa chạy | Planned |
| Restart/recovery | Crash sau archive/checkpoint/mid-batch/before-after purge | Restart idempotent; không mất dữ liệu hoặc xóa lặp | Chưa chạy | Planned |
| Checkpoint corruption | Tamper bytes/hash/range/count/checksum | Verifier dừng; không purge; giữ evidence và alert | Chưa chạy | Planned |
| Independent review | Review schema/lock/archive restore/runbook | Reviewer độc lập sign-off trước shared environment | Chưa review | Planned |

## Purge gate

Không chạy purge trên shared/demo/production trong MVP task. Chỉ Testcontainers disposable được phép. Purge disabled nếu policy/hold/archive/checkpoint invalid hoặc resolver lỗi. Không claim production-ready hoặc legal compliance từ component test.

## Evidence

Revision/môi trường/log: chưa có. Không chạy purge hoặc DB operation khi soạn docs.

## Open items

Retention values, scope resolution, admin approval API, privileged DB execution, archive/checkpoint signature và recovery. Giữ Planned/Blocked cho đến khi có evidence và reviewer decision.
