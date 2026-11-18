# Module `limit` — Chỉ mục & Đặc tả Kỹ thuật

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-LIMIT-00`
> **Phiên bản:** 1.0
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đang phát triển (Skeleton — đã chốt hợp đồng giao tiếp)
> **Owner:** Vũ
> **Phối hợp / Reviewer:** Phương (`ledger`, `common`), Đạt (`admin`, `identity`), Thịnh (`audit`), Thắng (`FE`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-016, FR-031, AC-028, AC-033), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.5), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§8.5, §8.8), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§5.3, §7, Phụ lục A), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§10, §13.4), [[digitalbanking-docs/docs-backend/modules/_overview/06-limit.md]], [[DECISIONS.md]] (`DEC-COM-001`, `DEC-TRF-001/002/008/009/015`)

---

## 1. Phạm vi và Trách nhiệm

Module `com.simulatedbank.banking.limit` quản lý cấu hình hạn mức chuyển tiền theo phân cấp (`GLOBAL`, `CUSTOMER`, `ACCOUNT`), sở hữu cơ chế khóa bảo vệ mức khách hàng (**`Customer Step-Up Guard`**) và bảng giữ chỗ hạn mức (**`limit_reservation`**) cho các giao dịch đang xử lý (`PENDING`), theo dõi bộ đếm hạn mức đã sử dụng trong ngày (`limit_usage` theo múi giờ `Asia/Ho_Chi_Minh`), thực thi khóa bi quan + kiểm tra hạn mức trong pha `T2`, và áp dụng thay đổi cấu hình hạn mức sau khi phê duyệt Maker-Checker.

---

## 2. Bảng Dữ liệu Sở hữu Độc quyền (`Write-Owner`)

Module `limit` là chủ sở hữu ghi duy nhất của 3 bảng (`04. Database design & analysis.md` §4.5, migration `V9` theo `DEC-COM-001` & `DEC-TRF-008/015`):

| Bảng | Cột & Ràng buộc Trọng tâm | Quy tắc Nghiệp vụ & Bất biến |
|---|---|---|
| **`banking.limit_config`** | - `limit_config_id BIGINT IDENTITY PK`, `scope_type IN ('GLOBAL','CUSTOMER','ACCOUNT')`, `scope_id BIGINT`<br>- `max_single_amount NUMERIC(19,2) CHECK (> 0)`, `daily_amount NUMERIC(19,2) CHECK (>= max_single_amount)`<br>- `status IN ('ACTIVE','SUPERSEDED','INACTIVE')`, `version INT NOT NULL`<br>- `approval_request_id`, `created_by`, `approved_by` (`CHECK approved_by <> created_by`), `effective_from`, `effective_to` | Partial unique index `uq_limit_config_active_scope` đảm bảo tại mọi thời điểm chỉ có duy nhất **1** cấu hình `ACTIVE` cho mỗi `(scope_type, scope_id)`. |
| **`banking.limit_usage`** | - `limit_usage_id BIGINT IDENTITY PK`, `scope_type IN ('CUSTOMER','ACCOUNT')`, `scope_id BIGINT NOT NULL`<br>- `usage_date DATE NOT NULL` (theo `Asia/Ho_Chi_Minh`)<br>- `used_amount NUMERIC(19,2) NOT NULL CHECK (>= 0)`, `tx_count INT NOT NULL CHECK (>= 0)`, `updated_at TIMESTAMPTZ` | Ràng buộc duy nhất `UNIQUE (scope_type, scope_id, usage_date)`. Trong `T2`, luôn khóa dòng `CUSTOMER` trước rồi mới khóa dòng `ACCOUNT` sau. |
| **`banking.limit_reservation`** | - `reservation_id BIGINT IDENTITY PK`, `transfer_id BIGINT NOT NULL UNIQUE FK -> transfer`<br>- `customer_id BIGINT NOT NULL`, `source_account_id BIGINT NOT NULL`, `amount NUMERIC(19,2) NOT NULL CHECK (> 0)`, `currency CHAR(3) ('VND')`<br>- `business_date DATE NOT NULL`, `status IN ('ACTIVE','SETTLED','RELEASED')`, `created_at`, `settled_at` | Giữ chỗ cho các giao dịch `PENDING` đã qua `T1` để tính chính xác tổng chuyển tiền dự kiến trong ngày (`DEC-TRF-001/008`), ngăn chặn hai yêu cầu đồng thời dùng khác `Idempotency-Key` vượt ngưỡng Step-up mà không bị yêu cầu OTP. |

---

## 3. Hợp đồng Giao tiếp Nội bộ (`design/05` §10, §13.4)

### 3.1. Public Ports do `limit` cung cấp (`com.simulatedbank.banking.limit.api`)

| Port & Phương thức | Consumer chính | Giao dịch (`TX`) | Hợp đồng Đầu vào / Đầu ra & Bất biến |
|---|---|---|---|
| `lockStepUpGuard(customerId)` (`TRFC-LI-04`) | `transfer` (`T0`, `T1`, `T2`, `Recovery`) | `JOIN` (`MANDATORY`) | Lấy khóa bảo vệ mức khách hàng (**bước 1** trong thứ tự khóa toàn cục `DEC-TRF-008`) để tuần tự hóa đánh giá Step-up, tạo reservation và chốt usage. |
| `evaluateStepUpUnderGuard(guardContext, amount, currency)` (`TRFC-LI-04`) | `transfer` (`T0`, `T1`) | `JOIN` (`MANDATORY`) | Tính $\text{Projected Daily Total} = \text{Used}_{\text{COMPLETED (today)}} + \sum \text{Reservation}_{\text{ACTIVE}} + \text{Amount}_{\text{current}}$ (`DEC-TRF-001/002/008`); trả về `CHALLENGE_REQUIRED` nếu `amount >= 10.000.000 VND` hoặc tổng dự kiến `> 20.000.000 VND`. |
| `reserveForPendingTransfer(guardContext, transferId, sourceId, amount, businessDate)` (`TRFC-LI-05`) | `transfer` (`T1`) | `JOIN T1` (`MANDATORY`) | Chèn bản ghi `limit_reservation (status = 'ACTIVE')` nguyên tử cùng `transfer PENDING` trong `T1`. |
| `lockAndCheck(customerId, sourceId, amount, currency, businessDate)` (`TRFC-LI-02`) | `transfer` (`T2`) | `JOIN T2` (`MANDATORY`) | Upsert khởi tạo 2 dòng `limit_usage` (`CUSTOMER`, `ACCOUNT`), khóa bi quan theo thứ tự `CUSTOMER -> ACCOUNT`, chụp cấu hình `ACTIVE` nhất quán và kiểm tra hạn mức đơn lệnh + hạn mức ngày (`DEC-TRF-009`). |
| `settleOrReleaseReservation(transferId, outcome, businessDate, guardContext)` (`TRFC-LI-06`) | `transfer` (`T2`, `Recovery`) | `JOIN T2` / `Recovery` (`MANDATORY`) | Khi `COMPLETED`, chuyển reservation sang `SETTLED` và cộng `amount` vào `limit_usage` của ngày nghiệp vụ kết thúc (`terminal_at`) **đúng một lần** (không gọi chồng `incrementUsage` — `DEC-TRF-009`); khi `FAILED`, chuyển sang `RELEASED` mà không cộng `limit_usage`. |
| `findReservationSettlementEvidence(transferId, systemContext)` (`TRFC-LI-07`) | `transfer` (`Recovery`) | `R` trong `Recovery` | Trả về bằng chứng trạng thái reservation/settlement phục vụ đối soát phục hồi `TransferRecoveryJob` (`DEC-TRF-006`). |
| `getUsageSnapshot(customerId, accountId, businessDate)` (`TRFC-LI-01`) | `limit` REST / `transfer` | `R` (Read-only) | Trả về hạn mức hiệu lực, tổng tiền đã dùng hôm nay và hạn mức còn lại. |
| `applyApprovedLimitChange(...)` | `admin` (`Approval`) | `JOIN approval TX` (`MANDATORY`) | Chuyển cấu hình `ACTIVE` cũ sang `SUPERSEDED` và kích hoạt cấu hình mới (`version = old + 1`) trong cùng giao dịch phê duyệt của Checker. |

### 3.2. Chiến lược Kiểm thử Độc lập cho Consumer (`AGENTS.md`)

Trong thời gian module `limit` đang ở trạng thái Skeleton (`src/main/java/com/simulatedbank/banking/limit/**/.gitkeep`), các module phụ thuộc (`transfer`, `admin`) tuân thủ quy tắc tại `AGENTS.md`:
- Sử dụng test double / fake chỉ trong `src/test` bám sát chữ ký và vector kiểm thử tại `05. Modules contract.md` §10 & §13.4 (ví dụ các bài kiểm thử trong `TransferPreflightCandidateTest`, `TransferIntentCandidateTest`, `TransferSettlementCandidateTest`, `TransferRecoveryCandidateTest`).
- Tuyệt đối không đăng ký fake bean làm provider production trong `src/main`.

---

## 4. Hợp đồng REST API Phụ trách (`07. API contracts.md` §8.5, §8.8)

- `API-LIM-01` — `GET /api/v1/limits`: Khách hàng (`CUSTOMER`, `LIMIT_READ_SELF`) tra cứu hạn mức giao dịch hiện hành, số tiền đã sử dụng trong ngày và hạn mức ngày còn lại.
- `API-LIM-02` / `API-LIM-03` — `POST /api/v1/admin/limits` & `POST /api/v1/admin/limits/{id}/approve`: Nhân viên (`OPERATIONS` với `LIMIT_MAKER` hoặc `ADMIN` với `LIMIT_MANAGE`, kèm đặc quyền `CHECKER` khi duyệt) đề xuất và phê duyệt thay đổi hạn mức qua module `admin`.

---

## 5. Checklist Công việc Triển khai (`Tasks`)

- [ ] **BE-LIM-01 (P0 — Migration & Domain/Persistence):** Tạo các bảng `limit_config`, `limit_usage`, `limit_reservation` trong migration `V9__create_limit_and_approval_tables.sql` (`DEC-COM-001`) và hiện thực thuật toán phân giải hạn mức hiệu lực (`ACCOUNT -> CUSTOMER -> GLOBAL`).
- [ ] **BE-LIM-02 (P0 — Step-Up Guard & Reservation Ports):** Hiện thực `lockStepUpGuard`, `evaluateStepUpUnderGuard`, `reserveForPendingTransfer`, `settleOrReleaseReservation` và `findReservationSettlementEvidence` theo `DEC-TRF-008/009/015`.
- [ ] **BE-LIM-03 (P0 — T2 Limit Check, Admin Mutation & REST API):** Hiện thực `lockAndCheck` (`CUSTOMER` trước, `ACCOUNT` sau), `applyApprovedLimitChange` và `GET /api/v1/limits` (`API-LIM-01`).
- [ ] **BE-LIM-04 (P0 — Kiểm thử Đồng thời PostgreSQL):** Kiểm chứng `T-LIM-01`, `T-LIM-02` (đua nhiều lệnh đồng thời không vượt hạn mức ngày) và `T-STEP-02` (đua 2 lệnh khác key cộng dồn vượt ngưỡng 20 triệu/ngày dưới customer guard) trên PostgreSQL 16 Testcontainers.
