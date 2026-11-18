# BE-FRD-01 — Báo cáo Kiểm chứng Bộ Quy tắc Gian lận & Thẩm định Cờ cảnh báo

> **Dự án:** Digital Banking MVP · **Owner:** Thịnh
> **Mã tài liệu:** `BE-MOD-FRAUD-01-REPORT`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã kiểm chứng thành phần (`Component Verified` — 45 tests Fraud + ArchUnit PASS trên PostgreSQL 16 Testcontainers)
> **Đặc tả công việc:** [BE-FRD-01-rules-and-review.md](BE-FRD-01-rules-and-review.md)

---

## 1. Ma trận Kiểm chứng (`Verification Matrix`)

| Hạng mục kiểm tra | Bài kiểm thử / Thủ tục | Kết quả mong đợi | Kết quả thực tế | Trạng thái |
|---|---|---|---|---|
| Đánh giá biên quy tắc (`LARGE_VALUE`, `HIGH_FREQUENCY`) | `FraudRuleEvaluatorTest` (16 tests) | Đánh giá chính xác biên số tiền (`amount >= threshold`), tần suất (`>` và `>=`), khoảng thời gian hiệu lực của phiên bản quy tắc và loại trừ giao dịch phát lại (`isReplay`) | **16/16 tests PASS** | **Verified** |
| Kiểm tra an toàn `matched_value` tại biên lưu trữ | `FraudFlagIT.directInsertFlagRejectsUnsafeMatchedValue` | `FraudJdbcRepository.insertFlag` chặn tuyệt đối số tài khoản chưa che dấu, email, SĐT, cấu trúc lồng không hợp lệ, số âm/NaN/overflow và secret; không chèn dòng lỗi vào DB | **PASS** (12 kịch bản kiểm tra hợp lệ và vi phạm) | **Verified** |
| Máy trạng thái thẩm định & kiểm tra `notes` | `ReviewStateTransitionTest` (10 tests), `ReviewFraudFlagRequestTest` (5 tests) | Ánh xạ đúng `ALLOW/BLOCK/ESCALATE/CLOSE`; cờ đã đóng trả `409 FRAUD_FLAG_ALREADY_CLOSED`; `notes <= 500` ký tự và không chứa PII/secret; tách biệt `trustedReviewerId` khỏi request DTO | **15/15 tests PASS** | **Verified** |
| Lưu trữ JPA, MapStruct & Ràng buộc Unique | `FraudJpaRepositoryIT` (5 tests), `FraudMapperTest` (2 tests), `FraudFlagIT` | Lưu trữ composite key `(rule_code, version)` với `Persistable`, ánh xạ `JSONB` qua `@JdbcTypeCode(SqlTypes.JSON)`, kiểm tra `UNIQUE (transfer_id, rule_code, rule_version)` và rollback theo giao dịch `T2` | **PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Đua thẩm định đồng thời & Khóa dòng `FOR UPDATE NOWAIT` | `FraudFlagIT.concurrentReviewOneTerminalWinner` & `reviewWhileRowLockedMapsPostgresLockConflict` | Hai nhân viên thẩm định đồng thời trên cùng 1 cờ: duy nhất 1 người thành công, người thứ hai nhận `409 FRAUD_FLAG_ALREADY_CLOSED` (bao gồm ánh xạ lỗi khóa PostgreSQL `55P03`) | **PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Cô lập lỗi bằng Savepoint trên PostgreSQL | `FraudFlagIT.savepointIsolationRollsBackSubtransaction` | Lỗi DB bên trong subtransaction được rollback về savepoint trong khi giao dịch bao ngoài vẫn tiếp tục và commit bình thường | **PASS** trên PostgreSQL 16 Testcontainers | **Verified** |
| Kiểm tra ranh giới kiến trúc Hexagonal (`ArchUnit`) | `ModuleBoundaryArchTest` (`fraudApplicationMustNotDependOnOutboundAdapters`) | `FraudReviewService` chỉ phụ thuộc vào `FraudPersistencePort` (`application.port`), không import trực tiếp `adapter.out` | **PASS** | **Verified** |
| Ghép nối luồng `T2` của `transfer`, `AuditModuleApi` (`C2`) & REST Controller | Kiểm thử tích hợp liên module | Gọi đánh giá trong `T2`, ghi audit `FRAUD_FLAG_CREATED` / `FRAUD_FLAG_REVIEWED` (`C2`) và kiểm tra quyền `FRAUD_REVIEW` / `FRAUD_READ` trên HTTP | Chờ ghép nối khi hợp nhất nhánh (`V10`) | **Chờ ghép tích hợp** |

---

## 2. Lệnh Tái lập Kiểm thử

Từ thư mục `digitalbanking-backend/` (yêu cầu Java 21 và Docker cho Testcontainers):

```bash
./gradlew test \
  --tests '*FraudRuleEvaluatorTest' \
  --tests '*ReviewStateTransitionTest' \
  --tests '*ReviewFraudFlagRequestTest' \
  --tests '*FraudFlagIT' \
  --tests '*FraudJpaRepositoryIT' \
  --tests '*FraudMapperTest' \
  --tests '*ModuleBoundaryArchTest'
```

- **Kết quả:** `BUILD SUCCESSFUL` — **45 tests Fraud + 10 rules ArchUnit PASS**, `0` thất bại, `0` lỗi, `0` bỏ qua.

---

## 3. Phân tích Thiết kế Persistence & Chuẩn hóa Kiến trúc (`BE-ARCH-00` §3, §4, §6)

| Thành phần / Phương thức | Bản chất thao tác | Công nghệ áp dụng | Lý do kỹ thuật | Bài kiểm thử |
|---|---|---|---|---|
| `insertRuleConfig`, `findActiveRuleConfigs` (`FraudRuleConfigJpaRepository`) | CRUD cấu hình quy tắc | **Spring Data JPA + MapStruct** | Bảng `fraud_rule_config` dùng composite PK `@IdClass(FraudRuleConfigId.class)` và triển khai `Persistable<FraudRuleConfigId>` kết hợp `saveAndFlush` để giữ ngữ nghĩa `INSERT` nghiêm ngặt; truy vấn `findActiveRules` sắp xếp theo `ruleCode ASC, version DESC`. | `FraudJpaRepositoryIT`, `FraudMapperTest` |
| `insertFlag`, `findFlagById`, `findFlagsByTransferId` (`FraudFlagJpaRepository`) | CRUD cờ gian lận thông thường | **Spring Data JPA + MapStruct** | Sau khi kiểm tra allowlist qua `FraudMatchedValueValidator.validate`, lưu `FraudFlagEntity` với `@JdbcTypeCode(SqlTypes.JSON)` cho cột `matched_value`. | `FraudFlagIT`, `FraudJpaRepositoryIT` |
| `findFlagForUpdateNowait`, `updateReview` (`FraudJdbcRepository`) | Kiểm soát đồng thời đặc thù PostgreSQL | **Native SQL / `JdbcTemplate`** | Thực thi `SELECT ... FOR UPDATE NOWAIT` và `UPDATE ... WHERE flag_id = ? AND status IN ('OPEN', 'UNDER_REVIEW')` để đảm bảo đúng 1 người duyệt thắng cuộc mà không cần thêm cột `version` ngoài thiết kế `04` §4.7.2. | `FraudFlagIT` |
| `FraudPersistencePort` & `FraudReviewService` | Đảo ngược phụ thuộc (`Dependency Inversion`) | **Hexagonal Outbound Port** | `FraudReviewService` (`application`) chỉ phụ thuộc vào `FraudPersistencePort` (`application.port`), được kiểm tra tự động bởi `ModuleBoundaryArchTest`. | `FraudFlagIT`, `ModuleBoundaryArchTest` |

---

## 4. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1`–`v0.10` | 11–15/11/2026 | Triển khai `FraudRuleEvaluator`, `FraudMatchedValueValidator`, `ReviewStateTransition` và kiểm thử PostgreSQL Testcontainers. |
| `v0.11`–`v0.14` | 15/11/2026 | Hoàn thiện JPA Entities (`FraudRuleConfigEntity`, `FraudFlagEntity`), MapStruct mappers, `FraudPersistencePort` và rule ArchUnit (45 tests Fraud PASS). |
| `v1.0` | 18/11/2026 | Chuẩn hóa báo cáo tiếng Việt, loại bỏ đường dẫn máy cá nhân và đồng bộ quyết định `DEC-COM-001`, `DEC-TRF-007/009`. |
