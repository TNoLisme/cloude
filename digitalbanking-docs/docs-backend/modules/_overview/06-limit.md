# Module 06: `limit` — Tóm tắt & Công việc Thực thi

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-06`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Module:** `com.simulatedbank.banking.limit`
> **Owner chính:** **Vũ** (Phối hợp: Phương — `ledger`, Đạt — `admin`, Thắng — FE)
> **Mốc tập trung:** **Tuần 4 – Tuần 5 (W4–W5)** theo `10 Master timelines.md`
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-016, FR-031), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.5), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §8.3), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.5, §8.8), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.3, §7, Phụ lục A), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§10, §13.4), [[DECISIONS.md]] (`DEC-TRF-001/002/008/009/015`)

---

## 1. Mục tiêu và Phạm vi Trách nhiệm

Module `limit` quản lý cấu hình hạn mức chuyển tiền theo phân cấp (`GLOBAL`, `CUSTOMER`, `ACCOUNT`), cung cấp cơ chế khóa bảo vệ mức khách hàng (`Customer Step-Up Guard`) và giữ chỗ hạn mức (`limit_reservation`) cho các giao dịch đang xử lý (`PENDING`), theo dõi bộ đếm hạn mức đã sử dụng trong ngày (`limit_usage` theo múi giờ ngân hàng `Asia/Ho_Chi_Minh`) và thực thi khóa bi quan + kiểm tra hạn mức từng giao dịch (`max_single_amount`) lẫn hạn mức ngày (`daily_amount`) bên trong giao dịch `T2`.

---

## 2. Dữ liệu Sở hữu Độc quyền (`Write-Owner` trong Schema `banking`)

Module `limit` sở hữu độc quyền các bảng hạn mức và giữ chỗ (`04. Database design & analysis.md` §4.5, migration `V9` theo `DEC-COM-001`):

| Bảng | Cột & Ràng buộc Trọng tâm | Quy tắc & Bất biến |
|---|---|---|
| **`banking.limit_config`** | - `limit_config_id BIGINT IDENTITY PK`, `scope_type IN ('GLOBAL','CUSTOMER','ACCOUNT')`, `scope_id BIGINT`<br>- `max_single_amount NUMERIC(19,2) CHECK (> 0)`, `daily_amount NUMERIC(19,2) CHECK (>= max_single_amount)`<br>- `status IN ('ACTIVE','SUPERSEDED','INACTIVE')`, `version INT NOT NULL`<br>- `approval_request_id`, `created_by`, `approved_by`, `effective_from`, `effective_to` | - **Partial Unique Index (`uq_limit_config_active_scope`):** Đảm bảo tại mọi thời điểm chỉ có duy nhất **1** dòng `status = 'ACTIVE'` cho mỗi `(scope_type, scope_id)`.<br>- Ràng buộc `CHECK (approved_by IS NULL OR approved_by <> created_by)`. |
| **`banking.limit_usage`** | - `limit_usage_id BIGINT IDENTITY PK`, `scope_type IN ('CUSTOMER','ACCOUNT')`, `scope_id BIGINT`<br>- `usage_date DATE NOT NULL` (theo `Asia/Ho_Chi_Minh`)<br>- `used_amount NUMERIC(19,2) NOT NULL CHECK (>= 0)`, `tx_count INT NOT NULL CHECK (>= 0)`, `updated_at TIMESTAMPTZ` | - **Unique Constraint:** `UNIQUE (scope_type, scope_id, usage_date)`. |
| **`banking.limit_reservation`** | - `reservation_id BIGINT IDENTITY PK`, `transfer_id BIGINT NOT NULL UNIQUE FK -> transfer`<br>- `customer_id BIGINT NOT NULL`, `source_account_id BIGINT NOT NULL`, `amount NUMERIC(19,2) NOT NULL CHECK (> 0)`, `currency CHAR(3) ('VND')`<br>- `business_date DATE NOT NULL`, `status IN ('ACTIVE','SETTLED','RELEASED')`, `created_at`, `settled_at` | - Theo dõi các giao dịch `PENDING` đã qua `T1` để tính tổng chuyển tiền dự kiến trong ngày (`DEC-TRF-001/008/015`), chống vượt ngưỡng Step-up khi khách hàng gửi nhiều yêu cầu đồng thời với các `Idempotency-Key` khác nhau. |

---

## 3. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.5, §8.8)

| Mã API | Method & Path | Quyền truy cập | Hành vi cốt lõi |
|---|---|---|---|
| `API-LIM-01` | `GET /api/v1/limits` | `CUSTOMER` (`LIMIT_READ_SELF`) | Trả về hạn mức hiệu lực hiện tại (từng lần và theo ngày), tổng số tiền đã chuyển trong ngày hôm nay (`usedToday`) và hạn mức ngày còn lại (`remainingDaily`). |
| `API-LIM-02`<br>`API-LIM-03` | `POST /api/v1/admin/limits`<br>`POST /api/v1/admin/limits/{id}/approve` | Maker: `OPERATIONS` (`LIMIT_MAKER`) hoặc `ADMIN` (`LIMIT_MANAGE`)<br>Checker: `OPERATIONS`/`ADMIN` có quyền tương ứng + đặc quyền **`CHECKER`** | Luồng Maker-Checker đề xuất và phê duyệt thay đổi cấu hình hạn mức (được điều phối qua `approval_request` của module `admin` theo `07` §3.4 & §8.8). |

---

## 4. Hợp đồng Giao tiếp Nội bộ (`Internal Ports` — `design/05` §10, §13.4)

### 4.1. Ports cung cấp cho các module khác gọi (`limit.api`)
- **Nhóm Step-Up Guard & Reservation (`DEC-TRF-008`, `DEC-TRF-015` — được gọi bởi `transfer` ở `T0`, `T1`, `T2` và `Recovery`):**
  - `lockStepUpGuard(customerId)` (`TRFC-LI-04`, `JOIN`): Khóa tuần tự hóa mức khách hàng (`Customer Step-Up Guard` — bước 1 trong thứ tự khóa toàn cục `DEC-TRF-008`).
  - `evaluateStepUpUnderGuard(guardContext, amount, currency)` (`TRFC-LI-04`, `JOIN`): Đọc tổng `COMPLETED` trong ngày nghiệp vụ hiện tại cộng **toàn bộ** `limit_reservation` đang `ACTIVE` (kể cả từ ngày trước) cộng `amount` của lệnh mới để quyết định `CHALLENGE_REQUIRED` (`amount >= 10.000.000 VND` hoặc tổng dự kiến `> 20.000.000 VND`) hay `MAY_CREATE_INTENT`.
  - `reserveForPendingTransfer(guardContext, transferId, sourceAccountId, amount, businessDate)` (`TRFC-LI-05`, `JOIN T1`): Tạo bản ghi `limit_reservation (status = 'ACTIVE')` trong cùng giao dịch `T1`.
  - `settleOrReleaseReservation(transferId, outcome, businessDate, guardContext)` (`TRFC-LI-06`, `JOIN T2` / `Recovery`): Khi `COMPLETED`, chuyển reservation sang `SETTLED` và cộng dồn vào `limit_usage` của ngày nghiệp vụ kết thúc (`terminal_at`) **đúng một lần** (không gọi chồng `incrementUsage` gây cộng kép — `DEC-TRF-009`); khi `FAILED`, chuyển reservation sang `RELEASED` mà không tăng `limit_usage`.
  - `findReservationSettlementEvidence(transferId, systemContext)` (`TRFC-LI-07`, `R` trong `Recovery`): Trả về bằng chứng trạng thái reservation/settlement phục vụ đối soát phục hồi `TransferRecoveryJob` (`DEC-TRF-006`).
- **Nhóm Kiểm tra Hạn mức trong `T2` & Quản trị Cấu hình:**
  - `getUsageSnapshot(customerId, accountId, businessDate)` (`R`): Đọc nhanh hạn mức và mức đã dùng cho `GET /api/v1/limits` và bước kiểm tra sơ bộ.
  - `lockAndCheck(customerId, sourceAccountId, amount, currency, businessDate)` (`TRFC-LI-02`, `JOIN T2`): Upsert khởi tạo 2 dòng `limit_usage` (`CUSTOMER` và `ACCOUNT`) với `ON CONFLICT DO NOTHING` (`design/02` Pattern A.4), khóa bi quan theo thứ tự cố định **`CUSTOMER` trước, `ACCOUNT` sau** (`FOR UPDATE` — Pattern A.5), đọc cấu hình `ACTIVE` nhất quán và kiểm tra `amount <= max_single_amount` cùng `used_amount + amount <= daily_amount` (`DEC-TRF-009`).
  - `applyApprovedLimitChange(approvalId, scopeType, scopeId, newSingleLimit, newDailyLimit, expectedVersion, actor)` (`JOIN approval TX`): Được gọi bởi `admin` khi Checker phê duyệt `LIMIT_CHANGE`; chuyển bản ghi `ACTIVE` hiện tại sang `SUPERSEDED` và chèn bản ghi `ACTIVE` mới (`version = old + 1`) trong cùng giao dịch phê duyệt.

---

## 5. Danh sách Công việc Cần Triển khai & Viết Thiết kế Chi tiết

1. [ ] Hiện thực migration `V9` (các bảng `limit_config`, `limit_usage`, `limit_reservation`) và thuật toán phân giải hạn mức hiệu lực (`Effective Limit Resolution`: kết hợp cấu hình `ACCOUNT`, `CUSTOMER` và fallback `GLOBAL`).
2. [ ] Hiện thực `Customer Step-Up Guard` (`lockStepUpGuard`, `evaluateStepUpUnderGuard`) và quản lý vòng đời `limit_reservation` (`reserveForPendingTransfer`, `settleOrReleaseReservation`, `findReservationSettlementEvidence`) theo `DEC-TRF-008/009`.
3. [ ] Hiện thực `LimitJdbcRepository` với mẫu SQL Upsert + `FOR UPDATE` theo đúng thứ tự `CUSTOMER -> ACCOUNT` và port `applyApprovedLimitChange` cho module `admin`.
4. [ ] Viết bộ kiểm thử đồng thời trên PostgreSQL `T-LIM-01` & `T-LIM-02` (bắn 5 request đồng thời có tổng tiền vượt hạn mức ngày còn lại $\rightarrow$ chứng minh không thể vượt hạn mức ngày nhờ khóa `limit_usage`) và `T-STEP-02` (hai request khác key đồng thời cộng dồn vượt ngưỡng 20 triệu/ngày không thể bypass Step-up nhờ `Customer Step-Up Guard`).
