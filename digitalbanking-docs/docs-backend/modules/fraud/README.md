# Module `fraud` — Chỉ mục & Đặc tả Kỹ thuật

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-FRAUD-00`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 19/11/2026
> **Trạng thái:** Bộ đánh giá Fraud đã ghép T2 theo BE-INT-07. REST tra cứu/thẩm định thuộc luồng tích hợp riêng.
> **Owner:** Thịnh
> **Phối hợp / Reviewer:** Vũ (`transfer`, `limit`), Đạt (`admin`, `identity`), Phương (`common`), Thắng (`FE`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-044..048, AC-026, AC-027), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.7.1, §4.7.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.6), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.7), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.3, §5.6), [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]], [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§11.2), [[digitalbanking-docs/docs-backend/modules/_overview/08-fraud.md]], [[DECISIONS.md]] (`DEC-COM-001`, `DEC-TRF-007/009`)

---

## 1. Phạm vi và Trách nhiệm

Module `com.simulatedbank.banking.fraud` thực thi bộ quy tắc phát hiện gian lận tất định ngay trong tiến trình xử lý giao dịch chuyển tiền (**In-Process Deterministic Rule Engine** tại pha `T2`), quản lý cấu hình quy tắc có đánh số phiên bản (`banking.fraud_rule_config`), ghi nhận cờ cảnh báo gian lận (`banking.fraud_flag`) và cung cấp dịch vụ thẩm định cờ gian lận (`FraudReviewService`) cho nhân viên vận hành.

---

## 2. Hiện trạng Triển khai trong `digitalbanking-backend/src/main/java/com/simulatedbank/banking/fraud`

Fraud cung cấp `fraud.api.FraudEvaluation` cho Transfer. `JdbcFraudEvaluation` ghép T2 bằng JDBC savepoint, đọc rule/version và lịch sử intent, rồi ghi cờ OPEN. Các thành phần phát triển độc lập gồm
- **`domain/` (12 classes):** `RuleType` (`LARGE_VALUE`, `HIGH_FREQUENCY`, `UNUSUAL_PATTERN`), `Severity`, `FlagStatus` (`OPEN`, `UNDER_REVIEW`, `ALLOWED`, `BLOCKED`, `CLOSED`), `ReviewDecision` (`ALLOW`, `BLOCK`, `ESCALATE`, `CLOSE`), `FrequencyComparator`, `FraudRuleConfig`, `FraudTransferSnapshot`, `FraudRuleMatch`, `FraudEvaluationResult`, `FraudFlagItem`, `FraudRuleEvaluator`, `FraudMatchedValueValidator` (chặn tuyệt đối PII/secret tại ranh giới JSONB), `ReviewStateTransition`.
- **`application/` & `application/port/` & `application/dto/` (4 classes):** `FraudReviewService`, `FraudConfiguration`, `FraudPersistencePort`, `ReviewFraudFlagRequest` (Bean Validation, tách biệt `trustedReviewerId` khỏi payload).
- **`adapter/out/` & `adapter/out/persistence/` (8 classes):** `FraudJdbcRepository` (`SELECT ... FOR UPDATE NOWAIT` + cập nhật quyết định nguyên tử), `FraudRuleConfigEntity`, `FraudRuleConfigId` (`@IdClass`), `FraudRuleConfigJpaRepository`, `FraudRuleConfigMapper` (MapStruct), `FraudFlagEntity` (`@JdbcTypeCode(SqlTypes.JSON)`), `FraudFlagJpaRepository`, `FraudFlagMapper` (MapStruct).

---

## 3. Bảng Sở hữu & Hợp đồng Giao tiếp Nội bộ

- **Bảng sở hữu độc quyền (`Write-Owner`):**
  1. `banking.fraud_rule_config`: PK tổ hợp `(rule_code, version)` (`04` §4.7.1, migration `V15`, bổ sung cấu hình và rule seed ở `V21`).
  2. `banking.fraud_flag`: PK `flag_id`, FK tổ hợp `(rule_code, rule_version)`, `matched_value JSONB`, `notes VARCHAR(500)`, `UNIQUE (transfer_id, rule_code, rule_version)` (`04` §4.7.2, migration `V15`).
- **Quy tắc chuẩn trong MVP (`DEC-TRF-009`):**
  - `LARGE_VALUE`: `amount >= threshold_amount`.
  - `HIGH_FREQUENCY`: số lượng lệnh chuyển tiền của cùng khách hàng trong cửa sổ trượt `600 giây` (10 phút) `> 5` (tức từ lệnh thứ 6 trở đi).
  - `UNUSUAL_PATTERN`: tắt mặc định trong phạm vi MVP.
- **Kiểm chứng** · [BE-INT-07 trên PostgreSQL 16](../../workofteam/integration%20modules/BE-INT-07-verification.md).
- **Thứ tự ghi kiểm toán `C2` (`DEC-TRF-007`):** Các cờ gian lận hợp lệ được giữ lại sau savepoint sẽ được ghi nhận kiểm toán `FRAUD_FLAG_CREATED` (`C2`) tuần tự theo `flag_id ASC` ở pha kiểm toán cuối của `T2` trước sự kiện `C1` (`TRANSFER_COMPLETED`/`FAILED`).

---

## 4. Danh mục Tài liệu & Báo cáo Kiểm chứng trong `modules/fraud/`

| Tài liệu | Nội dung | Trạng thái |
|---|---|---|
| [Tóm tắt Module 08: `fraud`](../_overview/08-fraud.md) | Tổng quan phạm vi, bảng `fraud_rule_config` & `fraud_flag`, REST API `API-FRD-01/02` và quy tắc gian lận. | Hoàn thành |
| [BE-FRD-01 — Bộ quy tắc Gian lận và Thẩm định Cờ cảnh báo](BE-FRD-01-rules-and-review.md) | Đặc tả chi tiết rule engine, cô lập lỗi savepoint trong `T2`, mô hình dữ liệu chuẩn `04` §4.7 và luồng thẩm định `API-FRD-01/02`. | Đã triển khai component |
| [BE-FRD-01 — Báo cáo Kiểm chứng](BE-FRD-01-verification-report.md) | Kết quả kiểm chứng 45 tests (unit, MapStruct, Bean Validation, JPA CRUD và `FOR UPDATE NOWAIT` concurrency trên PostgreSQL 16 Testcontainers). | Component Verified |
| [BE-AI-01 — Nghiên cứu Khả thi AI/ML Hỗ trợ Fraud](BE-AI-01-fraud-feasibility.md) | Khung đánh giá thử nghiệm offline trên dữ liệu synthetic (ngoài luồng hạch toán `T2` của MVP). | Hoàn thành thiết kế |
| [BE-AI-01 — Báo cáo Kiểm chứng](BE-AI-01-verification-report.md) | Ma trận đánh giá nghiên cứu offline `BE-AI-01`. | Planned (Ngoài phạm vi MVP) |
