# Module 06: `limit` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-06`
> **Module:** `com.simulatedbank.banking.limit`
> **Owner chính:** **Vũ** (Phối hợp: Phương — `ledger`, Đạt — `admin`, Thắng — FE)
> **Mốc tập trung:** **Tuần 4 – Tuần 5 (W4–W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-016, FR-031), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§5.5), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§11.6), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.5, §A.4, §A.5, §A.8)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `limit` quản lý cấu hình hạn mức chuyển tiền theo phân cấp (`GLOBAL`, `CUSTOMER`, `ACCOUNT`), theo dõi bộ đếm hạn mức đã sử dụng trong ngày (`limit_usage` theo múi giờ ngân hàng `Asia/Ho_Chi_Minh`) và thực thi khóa bi quan + kiểm tra hạn mức từng lần (`per_tx_limit`) cũng như hạn mức ngày (`daily_limit`) bên trong giao dịch `T2`.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `limit` sở hữu độc quyền 2 bảng:

| Bảng | Cột & Ràng buộc Trọng tâm | Quy tắc & Bất biến |
|---|---|---|
| **`banking.limit_config`** | - `limit_config_id` (`PK`), `scope_type` (`GLOBAL`, `CUSTOMER`, `ACCOUNT`), `scope_id`<br>- `per_tx_limit` (`CHECK > 0`), `daily_limit` (`CHECK >= per_tx_limit`)<br>- `status` (`ACTIVE`, `SUPERSEDED`, `INACTIVE`), `version_no`<br>- `approval_request_id`, `effective_from`, `effective_to` | - **Partial Unique Index:** Đảm bảo tại mọi thời điểm chỉ có duy nhất **1** dòng `status = 'ACTIVE'` cho mỗi `(scope_type, scope_id)`. |
| **`banking.limit_usage`** | - `limit_usage_id` (`PK`), `scope_type` (`CUSTOMER`, `ACCOUNT`), `scope_id`<br>- `usage_date` (`DATE` theo `Asia/Ho_Chi_Minh`)<br>- `used_amount` (`CHECK >= 0`), `tx_count` (`CHECK >= 0`), `updated_at` | - **Unique Constraint:** `UNIQUE (scope_type, scope_id, usage_date)`. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §11.6)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-LIM-01` | `GET /api/v1/limits` | `CUSTOMER` | Trả về hạn mức hiệu lực hiện tại (từng lần và theo ngày), tổng số tiền đã chuyển trong ngày hôm nay (`usedAmountToday`) và hạn mức ngày còn lại (`remainingDailyLimit`). |
| *(Phối hợp `admin`)* | `POST /api/v1/admin/limits`<br>`POST /api/v1/admin/limits/{id}/approve` | `STAFF` (`LIMIT_PROPOSE` / `LIMIT_APPROVE`) | Luồng Maker-Checker đề xuất và phê duyệt thay đổi cấu hình hạn mức (được điều phối qua `approval_request` của module `admin`). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports`)

### 4.1. Ports cung cấp cho các module khác gọi (`limit.api`)
- **`LimitEvaluationPort`** *(Được gọi bởi `transfer` trong pha `T0` và `T2`)*:
  - `getCurrentDailyUsage(customerId, accountId)`: Đọc nhanh mức đã dùng trong ngày ở `T0` để kiểm tra xem giao dịch dự kiến có làm tổng tiền trong ngày vượt ngưỡng yêu cầu Step-up OTP (`20,000,000 VND`) hay vượt trần ngày không.
  - `lockAndCheckLimitsInT2(customerId, sourceAccountId, amount)`:
    1. Upsert khởi tạo 2 dòng `limit_usage` cho ngày hiện tại (`CUSTOMER` và `ACCOUNT`) với `ON CONFLICT DO NOTHING` (`design/02` Pattern A.4).
    2. Khóa bi quan 2 dòng `limit_usage` theo thứ tự cố định: **`CUSTOMER` trước, `ACCOUNT` sau** (`FOR UPDATE` — Pattern A.5) để chống deadlock và ngăn chặn race condition vượt hạn mức ngày khi khách hàng bắn nhiều request đồng thời.
    3. Kiểm tra `amount <= effectivePerTxLimit` và `used_amount + amount <= effectiveDailyLimit`.
  - `incrementUsageInT2(customerId, sourceAccountId, amount)`: Cộng dồn `used_amount = used_amount + :amount` và `tx_count = tx_count + 1` (`Pattern A.8`) sau khi ghi nợ/ghi có thành công.
- **`LimitConfigMutationPort`** *(Được gọi bởi `admin` khi Checker phê duyệt `LIMIT_CHANGE`)*:
  - `applyApprovedLimitChange(approvalId, scopeType, scopeId, newPerTxLimit, newDailyLimit)`: Chuyển bản ghi `ACTIVE` hiện tại sang `SUPERSEDED` và chèn bản ghi `ACTIVE` mới (`version_no = old + 1`) trong cùng transaction phê duyệt.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực thuật toán phân giải hạn mức hiệu lực (`Effective Limit Resolution`: kết hợp cấu hình riêng của `ACCOUNT`, `CUSTOMER` và fallback về `GLOBAL`).
2. [ ] Hiện thực `LimitJdbcRepository` với mẫu SQL Upsert + `FOR UPDATE` theo đúng thứ tự `CUSTOMER -> ACCOUNT`.
3. [ ] Hiện thực `LimitConfigMutationPort` phục vụ luồng phê duyệt Maker-Checker của module `admin`.
4. [ ] Viết bài test đồng thời `T-LIM-01` & `T-LIM-02` (bắn 5 request đồng thời có tổng tiền vượt hạn mức ngày còn lại $\rightarrow$ chứng minh không thể vượt hạn mức ngày nhờ khóa `limit_usage`).
