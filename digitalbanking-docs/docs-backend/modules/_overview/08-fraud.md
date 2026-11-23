# Module 08: `fraud` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-08`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.fraud`
> **Owner chính:** **Thịnh** (Phối hợp: Vũ — `transfer` & `limit`, Đạt — `admin`, Thắng — FE)
> **Mốc tập trung:** **Tuần 5 (W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-044..048, AC-026, AC-027), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.7.1, §4.7.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.6), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.7), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.3, §5.6), [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] (Toàn bộ), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§11.2), [[DECISIONS.md]] (`DEC-COM-001`, `DEC-TRF-007/009`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `fraud` thực thi bộ quy tắc phát hiện gian lận và dấu hiệu rủi ro theo thời gian thực ngay trong tiến trình xử lý giao dịch chuyển tiền (**In-Process Deterministic Rule Engine tại `T2`**), tạo cờ cảnh báo (`fraud_flag`) gắn với phiên bản quy tắc (`(rule_code, rule_version)`), hỗ trợ đánh giá an toàn với cơ chế cô lập lỗi `Savepoint Fail-open`, và cung cấp luồng thẩm định cờ gian lận (`Operator Review Workflow`) cho nhân viên vận hành.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `fraud` sở hữu độc quyền 2 bảng (`04. Database design & analysis.md` §4.7.1–§4.7.2, migration `V10` theo `DEC-COM-001`):

| Bảng | Cột & Ràng buộc Trọng tâm | Quy tắc Nghiệp vụ & Bất biến |
|---|---|---|
| **`banking.fraud_rule_config`** | - **Composite PK:** `(rule_code VARCHAR(32), version INT)` (`FraudRuleConfigId`)<br>- `rule_type IN ('LARGE_VALUE','HIGH_FREQUENCY','UNUSUAL_PATTERN')`<br>- `threshold_amount NUMERIC(19,2)`, `threshold_count INT`, `window_seconds INT`<br>- `severity IN ('LOW','MEDIUM','HIGH','CRITICAL')`, `enabled BOOLEAN NOT NULL`<br>- `effective_from TIMESTAMPTZ`, `effective_to TIMESTAMPTZ`, `created_by`, `approved_by`, `created_at` | Lưu cấu hình theo phiên bản của các quy tắc gian lận (không hard-code ngưỡng trong code); giữ lịch sử phiên bản `(rule_code, version)` để giải thích chính xác mọi quyết định gắn cờ trong quá khứ. |
| **`banking.fraud_flag`** | - `flag_id BIGINT GENERATED ALWAYS AS IDENTITY PK`<br>- `transfer_id BIGINT NOT NULL FK -> transfer`<br>- **Composite FK:** `(rule_code, rule_version) -> fraud_rule_config(rule_code, version)`<br>- `severity IN ('LOW','MEDIUM','HIGH','CRITICAL')`<br>- `status IN ('OPEN','UNDER_REVIEW','ALLOWED','BLOCKED','CLOSED')` (`FlagStatus`)<br>- `matched_value JSONB NOT NULL` (chỉ chứa các khóa an toàn `amount`, `count`, `window` đã qua `FraudMatchedValueValidator`)<br>- `reviewed_by BIGINT FK -> app_user`, `reviewed_at TIMESTAMPTZ`<br>- `decision IN ('ALLOW','BLOCK','ESCALATE','CLOSE')` (`ReviewDecision`), `notes VARCHAR(500)`, `correlation_id`, `created_at` | - **Unique Constraint (`uq_fraud_flag_transfer_rule_ver`):** `UNIQUE (transfer_id, rule_code, rule_version)` đảm bảo không tạo cờ trùng lặp cho cùng một giao dịch và phiên bản quy tắc.<br>- Ánh xạ quyết định thẩm định (`ReviewStateTransition`): `ALLOW -> ALLOWED`, `BLOCK -> BLOCKED`, `ESCALATE -> UNDER_REVIEW`, `CLOSE -> CLOSED`. Chỉ các cờ ở trạng thái `OPEN` hoặc `UNDER_REVIEW` mới được phép cập nhật quyết định; cờ đã kết thúc (`ALLOWED`, `BLOCKED`, `CLOSED`) trả về `409 FRAUD_FLAG_ALREADY_CLOSED`. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.7)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-FRD-01` | `GET /api/v1/fraud-flags` | `OPERATIONS` (`FRAUD_REVIEW`), `ADMIN` / `AUDITOR` (`FRAUD_READ`) | Tra cứu danh sách cờ cảnh báo gian lận (hỗ trợ lọc theo `status`, `severity`, `ruleCode`, `reference`, `customerId`, khoảng thời gian `from`–`to`, phân trang, `sort=createdAt\|severity`). |
| `API-FRD-02` | `POST /api/v1/fraud-flags/{id}/review` | `OPERATIONS` (`FRAUD_REVIEW`) | Nhân viên vận hành cập nhật quyết định thẩm định (`decision`: `ALLOW`, `BLOCK`, `ESCALATE`, `CLOSE`) kèm ghi chú (`notes` tối đa 500 ký tự). Khóa dòng bằng `SELECT ... FOR UPDATE NOWAIT` kết hợp `WHERE status IN ('OPEN','UNDER_REVIEW')` và ghi sự kiện kiểm toán `FRAUD_FLAG_REVIEWED` (`C2`). |

---

## 4. Bộ Quy tắc Gian lận & Hợp đồng Giao tiếp Nội bộ (`design/03`, `DEC-TRF-007/009`)

### 4.1. Danh mục Quy tắc Gian lận Chuẩn trong MVP
- **`LARGE_VALUE` (`RuleType.LARGE_VALUE`):** Kích hoạt khi giá trị giao dịch `amount >= threshold_amount` theo cấu hình quy tắc đang hiệu lực.
- **`HIGH_FREQUENCY` (`RuleType.HIGH_FREQUENCY` — `DEC-TRF-009`):** Kích hoạt khi số lượng lệnh chuyển tiền (`transfer` intent đã commit `T1`) của cùng khách hàng trong cửa sổ trượt `window_seconds = 600` giây (10 phút) **lớn hơn `threshold_count = 5`** (tức từ lệnh thứ `6` trở đi trong 10 phút). Mặc định gắn cờ `OPEN` và tiếp tục xử lý giao dịch.
- **`UNUSUAL_PATTERN` (`RuleType.UNUSUAL_PATTERN`):** Giữ ở trạng thái tắt mặc định (`disabled`) trong phạm vi MVP.

### 4.2. Port cung cấp cho `transfer` gọi trong Pha `T2` (`fraud.api`)
- **`FraudEvaluationPort.evaluateTransferInT2(FraudTransferSnapshot snapshot) -> FraudEvaluationResult`:**
  - Chạy đồng bộ trong `T2` (sau khi đã khóa tài khoản và hạn mức, trước khi ghi nợ/ghi có) với ngân sách thời gian mục tiêu `1000 ms`.
  - **Cơ chế Cô lập Lỗi `Fail-Open` (`design/02` §5.6, `DEC-TRF-007`):** Bọc bước đánh giá quy tắc bổ trợ trong một **JDBC Savepoint (`PROPAGATION_NESTED`)**. Nếu quá trình đánh giá quy tắc gặp lỗi kỹ thuật/timeout và rollback về Savepoint thành công mà không làm hỏng transaction chính, ghi nhận sự kiện vận hành `FRAUD_ENGINE_ERROR` (`C4`) và cho phép giao dịch hợp lệ tiếp tục.
  - Các cờ gian lận hợp lệ được giữ lại sau savepoint sẽ được ghi nhận kiểm toán `FRAUD_FLAG_CREATED` (`C2`) tuần tự theo `flag_id ASC` ở pha kiểm toán cuối của `T2` (lỗi ghi audit `C2` bắt buộc fail-closed làm rollback toàn bộ `T2` — `DEC-TRF-007`).

---

## 5. Danh sách Công việc & Trạng thái Triển khai

1. [x] Hiện thực domain & pure rule engine (`FraudRuleEvaluator`, `FraudTransferSnapshot`, `FraudEvaluationResult`, `FraudRuleMatch`, `FraudMatchedValueValidator` chặn tuyệt đối PII/secret tại ranh giới JSONB, `ReviewStateTransition`) (`BE-FRD-01`).
2. [x] Hiện thực tầng persistence theo kiến trúc Hexagonal (`FraudPersistencePort`, `FraudRuleConfigEntity` với composite key `FraudRuleConfigId` + `Persistable`, `FraudFlagEntity`, MapStruct mappers `FraudRuleConfigMapper` / `FraudFlagMapper`, và `FraudJdbcRepository` cho `FOR UPDATE NOWAIT` + atomic conditional review update) (`BE-FRD-01`).
3. [x] Hiện thực `FraudReviewService` và `ReviewFraudFlagRequest` DTO (tách biệt hoàn toàn `trustedReviewerId` khỏi request body để chống giả mạo người duyệt) cùng **45 tests PASS** (unit + PostgreSQL 16 Testcontainers) (`BE-FRD-01`).
4. [ ] Đưa bảng `fraud_rule_config` và `fraud_flag` vào migration `V10` theo chuỗi `DEC-COM-001`, mở REST Controller (`API-FRD-01/02`) và ghép nối vào pha `T2` của `transfer` cùng `AuditModuleApi` (`C2`).

**Tài liệu chi tiết module `fraud`:**
- [Chỉ mục Module Fraud](../fraud/README.md)
- [BE-FRD-01 — Rule Engine & Review Workflow](../fraud/BE-FRD-01-rules-and-review.md) ([Báo cáo kiểm chứng](../fraud/BE-FRD-01-verification-report.md))
- [BE-AI-01 — Nghiên cứu Khả thi AI/ML Hỗ trợ Fraud](../fraud/BE-AI-01-fraud-feasibility.md) ([Báo cáo kiểm chứng](../fraud/BE-AI-01-verification-report.md))
