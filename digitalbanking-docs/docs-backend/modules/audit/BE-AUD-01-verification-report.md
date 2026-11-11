# BE-AUD-01 — Verification report

> **Dự án:** Digital Banking MVP; **Owner:** Thịnh  
> **Ngày:** 11/11/2026; **Trạng thái:** Planned — chưa chạy  
> **Task spec:** [BE-AUD-01-audit-writer.md](BE-AUD-01-audit-writer.md)

## Verification matrix

| Check | Lệnh / thủ tục | Expected | Actual | Status |
|---|---|---|---|---|
| Event validation | Unit tests `AuditEventValidatorTest` | Catalog, actor/outcome, allowlist, size, PII và money rules đúng | Chưa có class; chưa chạy | Planned |
| Canonical/hash vectors | `AuditCanonicalizationTest` với golden JCS vectors | Independent RFC vectors pass; writer/verifier dùng cùng canonical UTF-8 bytes | Provider chưa chọn; chưa chạy | Blocked |
| Migration/writer | PostgreSQL Testcontainers `AuditWriterIT` | Migration apply; genesis/prev_hash/record_hash đúng; insert trả ref | Chưa chạy | Planned |
| Concurrent writer | `AuditConcurrencyIT` với 50 connections/barrier | Chain liên tục; count đúng; sequence gap không false positive | Chưa chạy | Planned |
| DB permission | Role test trong Testcontainers | App INSERT/SELECT được; UPDATE/DELETE/TRUNCATE bị từ chối | Chưa chạy | Planned |
| C1/C2 rollback | `AuditTransactionIT` + injected writer failure | Business mutation và audit insert rollback cùng nhau | Chưa chạy | Planned |
| C3 semantics | Standalone transaction test | C3 failure không rollback security state; fallback safe | Chưa chạy | Planned |
| Transfer integration | Caller thật sau khi Vũ ghép contract | T1/T2 failure/replay/correlation/invariants đúng | Chưa tích hợp | Blocked |
| Global-lock benchmark | Concurrent writer/transfer workload; record hardware/PostgreSQL/pool/revision | Throughput, lock-wait p50/p95/p99, transaction duration, pool wait/timeouts/errors đo so với SLO nhóm chốt | Chưa chạy; chưa có SLO/capacity claim | Planned |
| Tail truncation | Verify với trusted external anchor khi có | Chỉ phát hiện truncation khi anchor tồn tại và verify được | External anchor ngoài scope AUD-01 | Not verified |

Không báo TPS limit hoặc “hundreds TPS” là kết quả đo nếu chưa có evidence. Không gọi tail truncation nội bộ là tampering đã chứng minh khi thiếu anchor.

## Evidence

Revision/môi trường/log/artifact: chưa có. Không claim implementation, integration hoặc test pass từ spec này.

## Open items

Chốt AUD-P01 validation, AUD-P02 formula/schema với nguồn `04`, AUD-P03 propagation/context với Phương và caller contract với Đạt/Vũ. Chọn JCS provider/version/license và vectors trước hash integration. Benchmark cần workload/SLO được nhóm chốt trước khi đánh giá.
