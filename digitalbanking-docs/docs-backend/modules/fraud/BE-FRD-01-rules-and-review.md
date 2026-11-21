# BE-FRD-01 — Đặc tả Bộ Quy tắc Gian lận và Thẩm định Cờ cảnh báo (`Fraud Rule Engine & Review`)

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-FRAUD-01` (`Task: BE-FRD-01`)
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã triển khai component (45 tests PASS — chờ ghép nối caller `T2` & REST Controller)
> **Owner:** Thịnh · **Reviewer:** Vũ (`transfer`, `limit`), Đạt (`identity`, `admin`), Phương (`common`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.7), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.3, §5.6), [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]], [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.7), [[digitalbanking-docs/openapi/digital-banking-v1.yaml]], [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]], [[DECISIONS.md]] (`DEC-COM-001`, `DEC-TRF-007/009`)

---

## 1. Mục tiêu và Phạm vi

Thực hiện các yêu cầu `FR-044..048`, `AC-026`, `AC-027`:
- Đánh giá quy tắc phát hiện gian lận tất định (`deterministic rule evaluation`) bên trong giao dịch chuyển tiền `T2`.
- Lưu trữ cờ cảnh báo gian lận (`banking.fraud_flag`) gắn với phiên bản quy tắc (`(rule_code, rule_version)`).
- Cung cấp quy trình thẩm định cờ gian lận (`Operator Review Workflow`) cho nhân viên vận hành (`OPERATIONS`).

Module `fraud` sở hữu độc quyền hai bảng `banking.fraud_rule_config` và `banking.fraud_flag`; không trực tiếp sửa trạng thái của `transfer`, `account`, `ledger_entry` hay `limit_usage`. Module `transfer` sở hữu trạng thái giao dịch tiền tệ, truy vấn số lượng giao dịch trong cửa sổ trượt dưới customer guard và áp dụng kết quả đánh giá trong `T2`.

---

## 2. Tái sử dụng Nền tảng `common`

- **Ngữ cảnh truy vết:** `RequestContextHolder` cung cấp `correlationId` và `requestId`.
- **Xử lý lỗi:** Sử dụng `ErrorCode` (`FRAUD_BLOCKED` `422`, `FRAUD_FLAG_NOT_FOUND` `404`, `FRAUD_FLAG_ALREADY_CLOSED` `409`) và `ProblemWriter` của `common`. Sự kiện `FRAUD_ENGINE_ERROR` là mã sự kiện vận hành nội bộ (`C4`), không phải mã lỗi công khai trả cho client.
- **Bảo vệ PII:** `FraudMatchedValueValidator` cưỡng chế allowlist cho cột `matched_value JSONB` (chỉ cho phép các trường số học/thời gian an toàn `amount`, `count`, `window`) và không bao giờ ghi nội dung ghi chú tự do (`notes`) vào log hay snapshot kiểm toán.
- **Cấu hình & thời gian:** Sử dụng `AppProperties` (`app.fraud.*`, `app.transfer.fraud-eval-timeout-ms = 1000`), `Clock` UTC và `ZoneId` (`Asia/Ho_Chi_Minh`) từ `common`.

---

## 3. Hợp đồng Giao tiếp Nội bộ & Bộ Quy tắc Chuẩn (`DEC-TRF-009`)

### 3.1. Hợp đồng Port đánh giá trong `T2`

```java
public interface FraudEvaluationPort {
    FraudEvaluationResult evaluateTransferInT2(FraudTransferSnapshot input);
}
```

`FraudTransferSnapshot` là Java `record` bất biến gồm 10 trường: `transferId`, `customerId`, `sourceAccountId`, `destinationAccountId`, `amount`, `currency`, `occurredAt`, `windowTransactionCount`, `isReplay`, `correlationId`.

### 3.2. Danh mục Quy tắc Gian lận Chuẩn (`DEC-TRF-009`)

| Quy tắc (`rule_type`) | Điều kiện kích hoạt chuẩn | Hành vi trong MVP |
|---|---|---|
| `LARGE_VALUE` | Giá trị giao dịch `amount >= threshold_amount` theo cấu hình quy tắc đang hiệu lực (`03. SRS.md` §2.13.5). | Gắn cờ `fraud_flag (status = 'OPEN')` và tiếp tục xử lý giao dịch (hoặc chặn `422 FRAUD_BLOCKED` nếu cấu hình chính sách chặn). |
| `HIGH_FREQUENCY` | Số lượng lệnh chuyển tiền (`transfer` intent đã commit `T1`) của cùng khách hàng trong cửa sổ trượt `window_seconds = 600` giây (10 phút) **lớn hơn `threshold_count = 5`** (tức từ lệnh thứ `6` trở đi trong 10 phút — `DEC-TRF-009`, `04` §4.7.1). Các yêu cầu phát lại (`isReplay = true`) bị loại trừ. | Gắn cờ `fraud_flag (status = 'OPEN')` và tiếp tục xử lý giao dịch. |
| `UNUSUAL_PATTERN` | Quy tắc mở rộng (`Should` trong `FR-047`). | Giữ ở trạng thái tắt mặc định (`disabled`) trong phạm vi MVP. |

---

## 4. Mô hình Dữ liệu, Ranh giới Giao dịch `T2` và Chính sách Cô lập Lỗi

### 4.1. Cấu trúc Bảng theo `04. Database design & analysis.md` §4.7.1–§4.7.2 (Migration `V10`)

- **`banking.fraud_rule_config`:**
  - Khóa chính tổ hợp `(rule_code, version)` (`FraudRuleConfigId`), `rule_type IN ('LARGE_VALUE','HIGH_FREQUENCY','UNUSUAL_PATTERN')`, `threshold_amount`, `threshold_count`, `window_seconds`, `severity IN ('LOW','MEDIUM','HIGH','CRITICAL')`, `enabled`, `effective_from`, `effective_to`, `created_by`, `approved_by`, `created_at`.
- **`banking.fraud_flag`:**
  - Khóa chính `flag_id BIGINT IDENTITY`, `transfer_id`, khóa ngoại tổ hợp `(rule_code, rule_version)`, `severity`, `status IN ('OPEN','UNDER_REVIEW','ALLOWED','BLOCKED','CLOSED')`, `matched_value JSONB NOT NULL`, `reviewed_by`, `reviewed_at`, `decision IN ('ALLOW','BLOCK','ESCALATE','CLOSE')`, `notes VARCHAR(500)`, `correlation_id`, `created_at`.
  - Ràng buộc duy nhất: `UNIQUE (transfer_id, rule_code, rule_version)`.

### 4.2. Thực thi trong `T2`, Savepoint Fail-Open và Kiểm toán `C2` (`DEC-TRF-007`)

1. Bước đánh giá gian lận chạy đồng bộ trong `T2` sau khi đã giữ `Customer Guard`, khóa dòng `transfer`, khóa hai tài khoản (`account_id ASC`) và khóa `limit_usage`, nhưng trước bước ghi nợ/ghi có tài khoản.
2. **Cô lập lỗi `Fail-Open` (`NFR-008`, `design/02` §5.6):** Bước đánh giá bổ trợ được bọc trong **JDBC Savepoint (`PROPAGATION_NESTED`)** với ngân sách mục tiêu `1000 ms`. Nếu engine gặp lỗi kỹ thuật, rollback về Savepoint để giữ cho giao dịch `T2` không bị đánh dấu aborted, ghi sự kiện vận hành `FRAUD_ENGINE_ERROR` (`C4`) và tiếp tục xử lý giao dịch hợp lệ.
3. **Ghi kiểm toán `C2` cuối giao dịch `T2` (`DEC-TRF-007`):** Đối với các cờ `fraud_flag` hợp lệ được giữ lại sau savepoint, sau khi toàn bộ thay đổi nghiệp vụ của `T2` đã `flush`, `transfer` gọi `AuditModuleApi.recordInTransaction` (`MANDATORY`) tuần tự cho từng sự kiện `FRAUD_FLAG_CREATED` (`C2`) theo `flag_id ASC` trước khi ghi sự kiện `TRANSFER_COMPLETED`/`FAILED` (`C1`). Lỗi ghi audit `C2` bắt buộc fail-closed (rollback toàn bộ `T2`).

---

## 5. Hợp đồng REST API & Thẩm định Cờ Gian lận (`07` §8.7)

| Endpoint | Quyền truy cập (`DEC-ID-001`) | Hành vi & Hợp đồng |
|---|---|---|
| `GET /api/v1/fraud-flags` (`API-FRD-01`) | `OPERATIONS` (`FRAUD_REVIEW`), `ADMIN` / `AUDITOR` (`FRAUD_READ`) | Tra cứu danh sách cờ cảnh báo theo `status`, `severity`, `ruleCode`, `reference`, `customerId`, `from`–`to`, phân trang `PageResponse<FraudFlag>`. |
| `POST /api/v1/fraud-flags/{id}/review` (`API-FRD-02`) | Chỉ **`OPERATIONS`** (`FRAUD_REVIEW`) | Body `ReviewFraudFlagRequest` gồm `decision` (bắt buộc: `ALLOW`, `BLOCK`, `ESCALATE`, `CLOSE`) và `notes` (tùy chọn, tối đa 500 ký tự). Khóa dòng `FOR UPDATE NOWAIT`, chuyển trạng thái (`ALLOW -> ALLOWED`, `BLOCK -> BLOCKED`, `ESCALATE -> UNDER_REVIEW`, `CLOSE -> CLOSED`) và ghi audit `FRAUD_FLAG_REVIEWED` (`C2`). Nếu cờ đã ở trạng thái kết thúc hoặc đang bị khóa bởi người duyệt khác, trả về `409 FRAUD_FLAG_ALREADY_CLOSED`. |

---

## 6. Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| `v0.1`–`v0.4` | 11–14/11/2026 | Khởi tạo đặc tả rule engine, review workflow và đối chiếu schema `04` §4.7 / OpenAPI. |
| `v1.0` | 18/11/2026 | Chuẩn hóa văn phong tiếng Việt, đồng bộ ngưỡng `HIGH_FREQUENCY` (`> 5` lệnh trong `600s` theo `DEC-TRF-009`), thứ tự ghi audit `C2` (`DEC-TRF-007`) và chuỗi migration `V10` (`DEC-COM-001`). |
