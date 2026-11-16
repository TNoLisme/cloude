# Module 06: `limit` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-06`
> **Module:** `com.simulatedbank.banking.limit`
> **Owner chính:** **Vũ** (Phối hợp: Phương — `ledger`, Đạt — `admin`, Thắng — FE)
> **Mốc tập trung:** **Tuần 4 – Tuần 5 (W4–W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-016, FR-031), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.5.1–4.5.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§7.2.13, §8.5), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.5, §A.4, §A.5, §A.8)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `limit` sở hữu cấu hình theo scope `CUSTOMER` và `ACCOUNT`, cùng dữ liệu usage theo ngày. Không có scope `GLOBAL`. E1 cung cấp nền dữ liệu, chọn cấu hình `ACTIVE` đang hiệu lực và kiểm tra hạn mức từng lần; daily enforcement trong T2 thuộc E2, chưa triển khai ở E1. Timezone nghiệp vụ còn mang nhãn đề xuất theo nguồn chuẩn; E1 nhận ngày/instant tường minh.

Xem [kế hoạch](../limit/DELIVERY-PLAN.md), [hướng dẫn module](../limit/README.md) và [bằng chứng E1](../limit/E1-verification.md). Các hợp đồng trong mục 3–4 bên dưới là hướng phát triển E2–E4, chưa phải API đã triển khai.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `limit` sở hữu độc quyền 2 bảng:

| Bảng | Cột & Ràng buộc Trọng tâm | Quy tắc & Bất biến |
|---|---|---|
| **`banking.limit_config`** | `limit_id`, `customer_id`, `account_id` nullable; `per_transaction_limit`, `daily_limit`, `currency`; `effective_from/to`; `status` (`PENDING_APPROVAL`, `ACTIVE`, `SUPERSEDED`, `REJECTED`), `version`; `created_by`, `approved_by`, `approval_id`, `created_at`, `approved_at`, `updated_at` | Giá trị dương, per-transaction ≤ daily, VND, khoảng hiệu lực hợp lệ, maker ≠ checker; ACTIVE/SUPERSEDED có attribution. Partial unique riêng cho customer (`account_id IS NULL`) và account (`account_id IS NOT NULL`) khi ACTIVE. |
| **`banking.limit_usage`** | `usage_id`, `customer_id`, `account_id` nullable, `usage_date`, `currency`, `used_amount`, `transfer_count`, `updated_at` | Amount/count không âm; VND; partial unique customer/date khi account null, account/date khi account không null. |

FK tới `customer`, `account`, `app_user`, `approval_request` được hoãn theo quyết định clarify E1 ngày 15/11/2026 vì bảng nguồn chưa có; không tạo bảng giả. Đây chưa phải bằng chứng toàn vẹn tham chiếu/ownership. Cấu hình/usage được ghi trong transaction do caller quản lý.

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.5)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-LIM-01` | `GET /api/v1/limits` | `CUSTOMER` | Trả về hạn mức hiệu lực hiện tại (từng lần và theo ngày), tổng số tiền đã chuyển trong ngày hôm nay (`usedToday` theo item) và hạn mức ngày còn lại (`remainingDaily` theo item, `effectiveRemainingDaily` tổng hợp). |
| *(Phối hợp `admin`)* | `POST /api/v1/admin/limits`<br>`POST /api/v1/admin/limits/{id}/approve` | Operations/Admin maker (`LIMIT_MAKER`/`LIMIT_MANAGE`), checker có privilege `CHECKER` | Luồng Maker-Checker đề xuất và phê duyệt thay đổi cấu hình hạn mức (được điều phối qua `approval_request` của module `admin`). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports`)

### 4.1. Provider E1 đã hiện thực (`limit.api`)

- `LimitRules.resolve(customerId, sourceAccountId, asOf)`: chỉ lấy ACTIVE với start ≤ asOf và end null hoặc > asOf; trả cả hai scope.
- `LimitRules.checkPerTransaction(...)`: bằng trần được chấp nhận; vượt bất kỳ scope nào trả lỗi `LIMIT_EXCEEDED`; thiếu cả hai trả `NOT_CONFIGURED`, không phải PASS. D1 enforcement còn mở.
- Không tạo/khóa/cộng usage; caller xác thực pair, T2 phải kiểm tra lại dưới khóa ở E2.

### 4.2. Hướng tích hợp E2–E4 — chưa triển khai

Các tên port/chữ ký bên dưới chỉ là đề xuất cũ, không thay hợp đồng E1 hoặc đặc tả vòng SDD tiếp theo.

- **`LimitEvaluationPort`** *(Được gọi bởi `transfer` trong pha `T0` và `T2`)*:
  - `getCurrentDailyUsage(customerId, accountId)`: Đọc nhanh mức đã dùng trong ngày ở `T0` để kiểm tra xem giao dịch dự kiến có làm tổng tiền trong ngày vượt ngưỡng yêu cầu Step-up OTP (`20,000,000 VND`) hay vượt trần ngày không.
  - `lockAndCheckLimitsInT2(customerId, sourceAccountId, amount)`:
    1. Upsert khởi tạo 2 dòng `limit_usage` cho ngày hiện tại (`CUSTOMER` và `ACCOUNT`) với `ON CONFLICT DO NOTHING` (`design/02` Pattern A.4).
    2. Khóa bi quan 2 dòng `limit_usage` theo thứ tự cố định: **`CUSTOMER` trước, `ACCOUNT` sau** (`FOR UPDATE` — Pattern A.5) để chống deadlock và ngăn chặn race condition vượt hạn mức ngày khi khách hàng bắn nhiều request đồng thời.
    3. Kiểm tra `amount <= effectivePerTxLimit` và `used_amount + amount <= effectiveDailyLimit`.
  - `incrementUsageInT2(customerId, sourceAccountId, amount)`: Cộng dồn `used_amount = used_amount + :amount` và `transfer_count = transfer_count + 1` (`Pattern A.8`) sau khi ghi nợ/ghi có thành công.
- **`LimitConfigMutationPort`** *(Được gọi bởi `admin` khi Checker phê duyệt `LIMIT_CHANGE`)*:
  - `applyApprovedLimitChange(approvalId, scopeType, scopeId, newPerTxLimit, newDailyLimit)`: Chuyển bản ghi `ACTIVE` hiện tại sang `SUPERSEDED` và chèn bản ghi `ACTIVE` mới (`version = old + 1`) trong cùng transaction phê duyệt.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [x] E1: Hiện thực thuật toán phân giải hạn mức hiệu lực (`Effective Limit Resolution`: kiểm tra đồng thời `CUSTOMER` và `ACCOUNT`; không fallback GLOBAL).
2. [ ] E2: Mở rộng `LimitJdbcRepository` với mẫu SQL Upsert + `FOR UPDATE` theo đúng thứ tự `CUSTOMER -> ACCOUNT`.
3. [ ] Hiện thực `LimitConfigMutationPort` phục vụ luồng phê duyệt Maker-Checker của module `admin`.
4. [ ] Viết bài test đồng thời `T-LIM-01` & `T-LIM-02` (bắn 5 request đồng thời có tổng tiền vượt hạn mức ngày còn lại $\rightarrow$ chứng minh không thể vượt hạn mức ngày nhờ khóa `limit_usage`).


## Trạng thái provider E4 (15/11/2026)

`LimitChanges` cung cấp prepare/approve/reject nội bộ theo [hướng dẫn module](../limit/README.md#e4--lim-07lim-08-provider) và [bằng chứng E4](../limit/E4-verification.md). LIM-07/08 dùng caller transaction, scope version mới, pending/active/superseded/rejected, explicit policy không default; không ghi usage. Shared customer advisory lock phối hợp với E2. Future effectiveFrom bị từ chối theo clarify; account scope chưa được tracking không kích hoạt sau khi customer đã dùng trong ngày, giữ pending để duyệt ngày mới. LIM-09/10 và các REST API ở mục 3 vẫn chưa triển khai/nghiệm thu; provider không thay auth/approval/audit của admin. FK/ownership vẫn chờ owner thật.
