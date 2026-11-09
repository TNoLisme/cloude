# Module 08: `fraud` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-08`
> **Module:** `com.simulatedbank.banking.fraud`
> **Owner chính:** **Thịnh** (Phối hợp: Vũ — `transfer` & `limit`, Đạt — `admin`, Thắng — FE)
> **Mốc tập trung:** **Tuần 5 (W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-044..048, AC-026), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.7), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.6), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§11.8), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.6), [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] (Toàn bộ)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `fraud` thực thi bộ quy tắc phát hiện gian lận và dấu hiệu rủi ro theo thời gian thực ngay trong tiến trình xử lý giao dịch chuyển tiền (**In-Process Deterministic Rule Engine tại `T2`**), tạo cờ cảnh báo (`fraud_flag`) gắn với phiên bản quy tắc (`rule_version`), hỗ trợ giữ/chặn giao dịch bất thường theo cấu hình và cung cấp luồng thẩm định (`Operator Review Workflow`) cho nhân viên vận hành.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `fraud` sở hữu độc quyền 2 bảng:

| Bảng | Cột & Ràng buộc Trọng tâm | Quy tắc Nghiệp vụ |
|---|---|---|
| **`banking.fraud_rule_config`** | - `rule_code` (`PK`), `rule_name`, `version_no`<br>- `enabled`, `priority`, `severity` (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`)<br>- `threshold_json` / `threshold_value`, `window_seconds`, `action` / `auto_block`<br>- `effective_from`, `effective_to`, `updated_at` | Lưu cấu hình động của các quy tắc gian lận (không hard-code ngưỡng trong code) và giữ lịch sử phiên bản (`version_no`) để giải thích lại quyết định cũ. |
| **`banking.fraud_flag`** | - `fraud_flag_id` (`PK`), `transfer_id` (`FK -> transfer`)<br>- `rule_code` (`FK -> fraud_rule_config`), `rule_version`<br>- `severity`, `status` (`OPEN`, `UNDER_REVIEW`, `ALLOWED`/`CLOSED_FALSE_POSITIVE`, `BLOCKED`/`CLOSED_CONFIRMED`, `CLOSED`)<br>- `score_details` / `observed_value` (`JSONB`)<br>- `reviewed_by`, `review_note` / `review_reason`, `reviewed_at`, `created_at` | **Unique Constraint:** `UNIQUE (transfer_id, rule_code)` đảm bảo retry không tạo cờ trùng lặp cho cùng một giao dịch và một quy tắc. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.8)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-FRD-01` | `GET /api/v1/fraud-flags` | `STAFF` (`FRAUD_READ`) | Tra cứu danh sách cờ cảnh báo gian lận (hỗ trợ lọc theo `status`, `severity`, `ruleCode`, `transferReference`, khoảng thời gian `from`–`to`, phân trang). |
| `API-FRD-02` | `POST /api/v1/fraud-flags/{id}/review` | `STAFF` (`FRAUD_REVIEW`) | Nhân viên vận hành/rủi ro cập nhật quyết định thẩm định cờ gian lận kèm lý do (`reviewNote`). Khóa dòng bằng `FOR UPDATE NOWAIT` (`design/02` Pattern A.15) và ghi audit `FRAUD_FLAG_REVIEWED` (`C2`). |

---

## 4. Bộ Quy tắc Gian lận & Hợp đồng Giao tiếp Nội bộ (`design/03`)

### 4.1. Danh mục Quy tắc Gian lận (`Rule Catalog` từ `design/03` §4)
- **Nhóm quy tắc Baseline & Mở rộng Phương án B (`design/03` §4.1–4.2):**
  - `FRD-01 LARGE_VALUE` (`LARGE_TRANSFER`): Giao dịch giá trị lớn vượt ngưỡng cấu hình.
  - `FRD-02 DAILY_STEP_UP`: Tổng chuyển đi dự kiến trong ngày vượt `20,000,000 VND/ngày` $\rightarrow$ kích hoạt Step-up OTP và gắn cờ `DAILY_THRESHOLD`.
  - `FRD-03 DAILY_CAP`: Tổng chuyển đi dự kiến trong ngày vượt trần cứng (`50,000,000 VND/ngày` hoặc theo `limit_config`) $\rightarrow$ `REJECT` (`Fail-closed`).
  - `FRD-04 HIGH_FREQUENCY`: Tần suất giao dịch liên tục từ cùng khách hàng/tài khoản trong cửa sổ trượt (ví dụ: giao dịch thứ 3 trong `120 giây`) $\rightarrow$ yêu cầu Step-up OTP và gắn cờ `WARNING`.
  - `FRD-05 REPEATED_VELOCITY`: Tiếp tục chuyển tiền khi cảnh báo velocity (`WARNING` TTL 15 phút) còn hiệu lực $\rightarrow$ giữ giao dịch chờ Operator xem xét hoặc chặn theo cấu hình release.
  - `FRD-06 UNUSUAL_PATTERN` / `FRD-07 AUTH_ABUSE` / `FRD-08 OPERATOR_ANOMALY`: Các quy tắc quan sát và gắn cờ phục vụ kiểm toán.

### 4.2. Port cung cấp cho `transfer` gọi trong Pha `T2` (`fraud.api`)
- **`FraudEvaluationPort.evaluateTransferInT2(transfer, lockedAccounts)`:**
  - Chạy đồng bộ trong `T2` trước khi ghi sổ cái với ngân sách thời gian mục tiêu `1 giây`.
  - **Cơ chế Cô lập Lỗi `Fail-Open` (`design/02` §5.6, `design/03` §8):** Bọc phần tính toán thống kê/cảnh báo bổ trợ trong một **JDBC Savepoint (`PROPAGATION_NESTED`)** kèm `SET LOCAL statement_timeout = '1000ms'`. Nếu quá trình đánh giá quy tắc bổ trợ gặp lỗi kỹ thuật/timeout, rollback về Savepoint, ghi sự kiện `FRAUD_ENGINE_ERROR` (`C4`) và cho phép giao dịch hợp lệ tiếp tục (không làm hỏng giao dịch vì lỗi phụ trợ).

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Chốt phạm vi các quy tắc của Phương án B đưa vào bản phát hành cùng Vũ (`transfer`, `limit`) và Đạt (`admin`) theo checklist tại mục 13.2 của `design/03. fraud-detection-rules.md`.
2. [ ] Hiện thực `FraudRuleEngineService` đọc cấu hình từ `fraud_rule_config`, đánh giá các quy tắc trong `T2` với Savepoint isolation và ghi `fraud_flag` + audit `FRAUD_FLAG_CREATED` (`C2`).
3. [ ] Hiện thực `FraudReviewService` và 2 API `GET /api/v1/fraud-flags`, `POST /api/v1/fraud-flags/{id}/review`.
4. [ ] Viết bộ kiểm thử ranh giới ngưỡng gian lận (`FRD-T01..T18` trong `design/03` và `T-FRAUD-01`, `T-FRAUD-B01..B08` trong `09. Test plan.md`).
