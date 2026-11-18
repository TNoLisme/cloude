# BE-INT-01 — Bằng chứng đối chiếu và tích hợp ban đầu

> **Ngày:** 18/11/2026 (Asia/Ho_Chi_Minh)  
> **Thực hiện/chốt:** người điều hành/merge owner theo DEC-INT-003, không cần sign-off cá nhân khác  
> **Source:** workspace `dev2` chưa commit; lệnh dùng `--offline`, OpenJDK 21 ở Gradle test worker, PostgreSQL 16 Testcontainers.  
> **Trạng thái:** ma trận và quyết định contract đã cập nhật; **G0 BLOCKED** ở các seam Transfer/Limit/Identity/recovery còn thiếu. G1 và full regression hiện hành đã được BE-INT-02 kiểm chứng trên DB canonical mới; xem [report BE-INT-02](BE-INT-02-verification.md). Các kết quả 146 test bên dưới là lịch sử BE-INT-01, không đại diện source mới.

## 1. Thay đổi đã kiểm chứng

| ID | Thay đổi | Bằng chứng |
|---|---|---|
| BUILD-01 | Gỡ luồng Admin LIMIT cũ trùng ApprovalProposal/Decision; sửa `SafeJsonLogEncoder`; đồng bộ `LimitCustomerService` với `AccountQuery.findAccount`. | `./gradlew compileJava --offline` và `./gradlew testClasses --offline` exit 0. |
| AU-API-01 | `AuditModuleApi` dùng `AuditEvent`/`AuditRecordRef` generic theo §11.1; Admin adapter gọi cùng API. Hai Audit writer dùng một chain lock và đều lưu canonical payload; V17 giữ cột bằng chứng và quyền app audit append-only. | `AuditModulePostgreSqlIntegrationTest`, `DurableAdminAuditAdapterTest`, `AuditWriterIT`, `AuditQueryJdbcStoreIT` exit 0; test xen kẽ hai writer + chain verifier PASS. |
| DB-01 | Loại 5 migration module-local trùng V2–V6 khỏi Flyway location, lưu tại `db/migration-legacy`; dùng V1–V11 canonical, thêm V12 Limit usage, V13 Transfer/idempotency, V14 Ledger, V15 Fraud, V16 Notification, V17 Audit bridge. | `AccountV8MigrationPostgreSqlIntegrationTest` kiểm fresh DB và upgrade canonical V7→V17; Hibernate validate + `AccountReadPostgreSqlIntegrationTest` qua. |
| LI-API-01 | Test fixture Limit dùng parent rows Customer/Account/Approval và quyền DB theo schema tích hợp; fake Audit/Account có signature mới. | `LimitChangesIT`, `LimitEnforcementIT`, `LimitCustomerHttpIT`, `LimitCustomerServiceTest` qua sau chỉnh fixture. |
| LOG-01 | Encoder chỉ cho event `SECURITY_AUDIT_GAP` phát bounded allowlist, không phát secret hoặc completion fields không liên quan. | `SecurityAuditLogEncoderTest` exit 0. |

Lượt kiểm chứng lịch sử BE-INT-01 trước khi tiếp tục BE-INT-02 chạy 12 test class, **146 test PASS, 0 failure/error** (bao gồm test xen kẽ hai Audit writer; log lệnh chọn class tại `/tmp/be-int-verified-tests.log`, `BUILD SUCCESSFUL in 39s`). Artifact JUnit nằm tại `digitalbanking-backend/build/test-results/test/` và HTML report tại `digitalbanking-backend/build/reports/tests/test/index.html`. Tất cả test dùng dữ liệu tổng hợp; fake chỉ chứng minh consumer contract, không được coi là provider production.

## 2. Kết quả lịch sử và trạng thái xử lý

| ID | Actual | Điều kiện đóng |
|---|---|---|
| ADM-HTTP-01 | Lịch sử BE-INT-01: 11/27 FAIL. **Đã xử lý trong BE-INT-02** theo DEC-INT-005/006; `LimitAdminHttpIT` hiện **28/28 PASS** (thêm case future) trong full build 2.285 test / 202 class, source manifest và artifact ở [report BE-INT-02](BE-INT-02-verification.md). | Đã đóng lỗi regression. Provider/consumer vector với toàn bộ module thật và scope tiếp tục thuộc BE-INT-09/G2. |
| FULL-01 | Lượt BE-INT-01 cũ bị dừng không được coi PASS. **BE-INT-02 đã clean build PASS** 2.285 test / 202 class / 0 failure/error/skip, exit 0; xem report và archive hiện hành. | Đã đóng full regression standard suite ở BE-INT-02; không thay gate E2E/D1–D5. |
| TRF-G0 | Transfer vẫn dùng candidate/consumer seams; `TRFC-ID-05`, `TRFC-LI-01/02/04/05/06/07`, `TRFC-FR-01`, `TRFC-CU-02`, `TRFC-NO-01` và recovery evidence chưa khớp đầy đủ public provider API/runtime. | Người điều hành hiện thực adapter/provider theo `05` §13.4, chạy cùng [vector](BE-INT-01-vectors.md) trên PostgreSQL (JOIN/rollback/race), rồi mới bật route money path. |
| DB-UPGRADE-01 | Fresh DB và canonical V7→V17 được test. DB nào từng áp dụng migration module-local V2–V6 có cùng version nhưng khác checksum/schema, chưa có lộ trình nâng cấp dữ liệu chứng minh. | Kiểm kê `flyway_schema_history` từng môi trường, viết migration dữ liệu riêng và restore rehearsal; không dùng `repair` để che khác schema. |

## 3. Quyết định bàn giao

Người điều hành đã chốt `TRFC-LI-05` reserve và `TRFC-LI-07` settle/release (DEC-INT-001), đồng thời có toàn quyền sửa và chốt mọi integration module (DEC-INT-003). BE-INT-01 đã hoàn tất phần **kiểm kê/ma trận/định danh contract và sửa các xung đột build/schema được phát hiện**; G0 chưa đạt để bật Transfer money path. Các điều kiện còn lại được giữ trong ma trận [BE-INT-01.md](BE-INT-01.md) và backlog [README.md](README.md), không chuyển cho cá nhân khác.
