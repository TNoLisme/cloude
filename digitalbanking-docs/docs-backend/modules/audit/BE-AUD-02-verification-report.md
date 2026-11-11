# BE-AUD-02 — Verification report

> **Trạng thái:** Planned — chưa chạy  
> **Task spec:** [BE-AUD-02-query-export-verification.md](BE-AUD-02-query-export-verification.md)

## Verification matrix

| Check | Lệnh / thủ tục | Expected | Actual | Status |
|---|---|---|---|---|
| Audit search/detail contract | Chạy API tests liên quan khi implementation có | Schema/filter/paging/permissions khớp OpenAPI | Chưa chạy | Planned |
| Export bounds/format | Gọi CSV/JSONL và vượt `maxRows` | Format đúng; vượt mức trả 413, không cắt thầm | Chưa chạy | Planned |
| Export audit-before-response | Inject lỗi audit và quan sát response stream | Lỗi audit không gửi file bytes | Chưa chạy | Planned |
| PII and RBAC | Test synthetic fixtures với role được/không được phép | Mask đúng; 401/403 đúng; không lộ SQL/PII | Chưa chạy | Planned |
| Chain verifier | Testcontainers: valid, tamper, unknown version | Valid pass; mismatch/unsupported phát hiện và cảnh báo theo contract | Chưa chạy | Planned |

## Evidence

Revision/môi trường/log: chưa có. Không có implementation hoặc runtime verification ở thời điểm tạo report.

## Open items

Chốt export memory/snapshot strategy, manifest headers, checkpoint/anchor contract và integration permissions. Không đánh dấu Verified nếu chưa lưu evidence tái lập được.
