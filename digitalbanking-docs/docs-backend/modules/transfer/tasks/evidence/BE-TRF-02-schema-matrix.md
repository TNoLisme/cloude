# BE-TRF-02 — Ma trận đối chiếu schema Transfer

> **Lưu trữ lịch sử:** nội dung bên dưới phản ánh snapshot trước DEC-TRF-013. Các nhận định rằng `04` chưa chọn nơi lưu TTL/fencing không còn là trạng thái mới nhất; xem [DEC-TRF-013](../../../../../../DECISIONS.md) và `04` §4.4. SQL candidate `digitalbanking-backend/src/main/resources/db/transfer-candidate/V2__transfer.sql` chưa nằm trong Flyway location production; provider, migration order và review vẫn chưa có bằng chứng.

> Snapshot 16/11/2026; **Draft / Blocked**, chưa phải review, migration, bằng chứng thực thi hay nghiệm thu. Transfer (Vũ) là write-owner hai bảng dưới đây; DBA/Architect và các owner liên quan cần review. Ma trận là bản đối chiếu, không tự thay đổi baseline hay xác nhận provider đã tồn tại.

Nguồn chính: [Database design 04](../../../../../docs-common/04.%20Database%20design%20%26%20analysis.md) §3.7–3.8, §4.4.1, §4.4.3, §5, §7–8; [BE-TRF-02](../BE-TRF-02.md) §2–8; [Modules contract 05](../../../../../docs-common/design/05.%20Modules%20contract.md) §5, §7–10, §11, §13–14; [DECISIONS](../../../../../../DECISIONS.md) DEC-TRF-003, 005, 006, 008, 010. Các tài liệu `docs-common/01–11` ưu tiên `design/` và backend khi mâu thuẫn; các bất nhất trong cùng nguồn hoặc chi tiết chưa quyết định cần chuyển owner/điều hành, không suy đoán.

Ký hiệu: **N** = NOT NULL, **Y** = nullable, `—` = không có default/check riêng trong bảng nguồn. Trạng thái chung mọi dòng: **baseline dự kiến, chưa được chứng minh bằng migration/catalog**. Hiện V1 chỉ tạo schema `banking`; migration thực của Customer, Identity và Account chưa có trong source theo task. Không tạo bảng provider giả trong migration production; không sửa V1 hoặc chọn trước version phụ thuộc chưa thống nhất.

## 1. Bảng `banking.transfer` — owner Transfer

Nguồn từng hàng: `04` §4.4.1; đối chiếu task §4.1. ID provider chỉ là scalar ID tại boundary, không đưa entity/repository nội bộ provider ra API.

| Cột | Kiểu | Null | Default | Key / check / ý nghĩa |
|---|---|---|---|---|
| `transfer_id` | BIGINT | N | IDENTITY | PK |
| `reference_no` | VARCHAR(32) | N | — | UNIQUE; độ dài 1–32; immutable |
| `customer_id` | BIGINT | N | — | FK `customer(customer_id)`; owner denormalized |
| `initiated_by_user_id` | BIGINT | N | — | FK `app_user(user_id)`; actor |
| `source_account_id` | BIGINT | N | — | FK `account(account_id)`; khác destination |
| `destination_account_id` | BIGINT | N | — | FK `account(account_id)`; khác source |
| `amount` | NUMERIC(19,2) | N | — | `> 0`; ứng dụng kiểm precision/scale trước khi ghi, dùng decimal chính xác |
| `currency` | CHAR(3) | N | `'VND'` | chỉ `VND` |
| `description` | VARCHAR(255) | Y | — | tối đa 255; ứng dụng validate/mask, không lưu dữ liệu nhạy cảm |
| `status` | VARCHAR(16) | N | `'PENDING'` | `PENDING`, `COMPLETED`, `FAILED`; tổ hợp timestamp dưới đây |
| `failure_code` | VARCHAR(32) | Y | — | bắt buộc khác NULL khi FAILED |
| `failure_message` | VARCHAR(255) | Y | — | thông điệp an toàn, không PII |
| `correlation_id` | VARCHAR(64) | N | — | trace qua các module |
| `step_up_challenge_id` | BIGINT | Y | — | FK `auth_challenge(challenge_id)`; unique từng giá trị khác NULL |
| `created_at` | TIMESTAMPTZ | N | `now()` | thời điểm tạo |
| `updated_at` | TIMESTAMPTZ | N | `now()` | thời điểm cập nhật |
| `completed_at` | TIMESTAMPTZ | Y | — | bắt buộc khi COMPLETED; NULL khi PENDING/FAILED |
| `failed_at` | TIMESTAMPTZ | Y | — | bắt buộc khi FAILED; NULL khi PENDING/COMPLETED |

Checks theo `04`: PENDING có cả hai terminal timestamp NULL; COMPLETED có `completed_at` và `failed_at` NULL; FAILED có `failed_at` và `failure_code`, `completed_at` NULL. Không suy thêm từ bảng baseline rằng `failure_code` hoặc `failure_message` bắt buộc NULL ở trạng thái khác. Chỉ chuyển `PENDING → COMPLETED` hoặc `PENDING → FAILED`; dùng update có điều kiện `status='PENDING'`, kiểm số dòng dưới lock theo contract. DB check không chứng minh race. `terminal_at` là mốc logic theo DEC-TRF-008, không phải thời điểm commit thực.

`04` §4.4.1 yêu cầu trigger chặn sửa **status, amount, accounts, reference** sau terminal; tên/implementation trigger chưa được baseline định danh và phải review. `reference_no` còn immutable như natural key tại §3.7/§4.4.1; trigger terminal đơn lẻ không chứng minh bất biến reference trong PENDING. Cơ chế và policy cho các trường ngoài danh sách terminal cần review; không tự mở rộng phạm vi trigger. Không giả định trigger đã tồn tại.

## 2. Bảng `banking.idempotency_record` — owner Transfer

Nguồn từng hàng: `04` §4.4.3; đối chiếu task §4.2, §5–6.

| Cột | Kiểu | Null | Default | Key / check / ý nghĩa |
|---|---|---|---|---|
| `idempotency_key` | VARCHAR(255) | N | — | PK global; dài 1–255; immutable natural key; không log raw |
| `request_hash` | CHAR(64) | N | — | SHA-256 payload chuẩn hóa; không log raw |
| `transfer_id` | BIGINT | N | — | UNIQUE FK `transfer(transfer_id)` |
| `status` | VARCHAR(16) | N | `'RECEIVED'` | `RECEIVED`, `IN_PROGRESS`, `COMPLETED`, `FAILED` |
| `response_status` | SMALLINT | Y | — | 100–599 khi có; bắt buộc khi terminal |
| `response_body` | JSONB | Y | — | chỉ response an toàn đã mask; application lưu body gốc an toàn cho replay terminal |
| `created_at` | TIMESTAMPTZ | N | `now()` | thời điểm tạo |
| `completed_at` | TIMESTAMPTZ | Y | — | bắt buộc cho cả COMPLETED và FAILED; mốc retention terminal |
| `expires_at` | TIMESTAMPTZ | N | — | lớn hơn `created_at`; không dùng expiry đơn lẻ để purge nonterminal |

Check của `04`: status terminal ⇒ `completed_at IS NOT NULL AND response_status IS NOT NULL`. DB baseline cho phép body NULL nhưng application cần response gốc an toàn cùng status trong transaction terminal để replay. Một record tham chiếu đúng một transfer và mỗi transfer tối đa một record; record terminal có thể purge còn transfer tồn tại. Key global, actor/customer/hash được application đối chiếu khi replay; PK không phải scoped theo customer.

DEC-TRF-005: T1 snapshot TTL hợp lệ tối thiểu 24h/version và đặt `expires_at = created_at + TTL_T1`. Terminal snapshot TTL/version, atomically lưu status, response, `completed_at`, expiry `max(expires_at, completed_at + TTL_terminal)`; config thiếu/sai fail-closed, không rút ngắn expiry. Chỉ purge record terminal đủ hạn khi đã claim/lock và kiểm lại; không purge PENDING. Vị trí lưu snapshot/version chưa có thiết kế vật lý được review; không tự đặt cột.

## 3. FK và xóa

Theo `04` §3.7, §7.6 và task §4, FK dùng `ON DELETE RESTRICT`/`NO ACTION` và `ON UPDATE RESTRICT`/`NO ACTION`, không cascade. Task chọn RESTRICT và yêu cầu xác nhận với migration provider. FK chỉ xác nhận tồn tại, không thay ownership/status/authorization do provider kiểm qua public contract.

| Child → parent theo baseline | Nullable | Cardinality / ràng buộc | Provider / blocker |
|---|---|---|---|
| `transfer.customer_id → customer.customer_id` | N | N:1 | Customer; migration/PK và delete policy thực chưa kiểm |
| `transfer.initiated_by_user_id → app_user.user_id` | N | N:1 | Identity; migration/PK thực chưa kiểm |
| `transfer.source_account_id → account.account_id` | N | N:1; khác destination | Account; migration/PK và compatibility thực chưa kiểm |
| `transfer.destination_account_id → account.account_id` | N | N:1; khác source | Account; migration/PK và compatibility thực chưa kiểm |
| `transfer.step_up_challenge_id → auth_challenge.challenge_id` | Y | tối đa một transfer cho mỗi challenge khác NULL | Identity; migration/PK thực chưa kiểm |
| `idempotency_record.transfer_id → transfer.transfer_id` | N | UNIQUE; mỗi transfer tối đa một record | Transfer; test FK và purge với migration thực |

Không dùng bảng provider do Transfer tạo để chứng minh FK/delete hay upgrade. T1 insert transfer trước record; thua unique key phải rollback toàn T1 rồi đọc lại trên primary ở transaction mới, không để orphan hoặc side effect. Collision reference/challenge là unique lỗi riêng, không xem là replay key. Provider guard/reservation, consume challenge và audit tham gia transaction qua contract `05`; fake không chứng minh atomicity liên module.

## 4. Index, trigger và truy vấn

Tên/cột theo `04` §4.4.1, §4.4.3; công dụng là workload dự kiến, chưa phải `EXPLAIN` hoặc bằng chứng index. Không tạo index trùng chức năng của unique/PK.

| Bảng / index | Cột / tính chất | Truy vấn dự kiến |
|---|---|---|
| transfer / `uq_transfer_reference` | UNIQUE (`reference_no`) | Tra cứu theo reference |
| transfer / `ix_transfer_customer_time` | (`customer_id`, `created_at DESC`) | Lịch sử customer theo thời gian |
| transfer / `ix_transfer_source_time` | (`source_account_id`, `created_at DESC`) | Lịch sử account nguồn |
| transfer / `ix_transfer_destination_time` | (`destination_account_id`, `created_at DESC`) | Lịch sử account đích |
| transfer / `ix_transfer_status_time` | (`status`, `created_at`) | Quét PENDING / recovery theo thời gian |
| transfer / `ix_transfer_correlation` | (`correlation_id`) | Trace theo correlation |
| transfer / `uq_transfer_step_up_challenge` | UNIQUE (`step_up_challenge_id`) WHERE khác NULL | Chặn dùng lại challenge; nhiều NULL hợp lệ |
| idempotency / PK | (`idempotency_key`) | Lookup global key để replay/conflict |
| idempotency / `uq_idempotency_transfer` | UNIQUE (`transfer_id`) | Tối đa một record mỗi transfer; lookup theo transfer |
| idempotency / `ix_idempotency_expires` | (`expires_at`) | Tìm candidate expiry; lọc lại terminal dưới lock |
| idempotency / `ix_idempotency_status` | (`status`) | Lọc lifecycle/status |

Trigger terminal trong §1 là yêu cầu độc lập với check, conditional update và unique; phải kiểm bằng SQL trực tiếp sau migration. Không có bằng chứng catalog, query plan, lock order hay quyền DDL trong ma trận này.

## 5. Các điểm chờ quyết định / phối hợp

| Vấn đề | Ảnh hưởng / bước tiếp theo | Đầu mối theo task |
|---|---|---|
| Migration provider và version Flyway | FK, thứ tự migration, clean install/upgrade, delete rule và validation Blocked. Xác nhận PK/FK thực, thứ tự và version duy nhất; không sửa migration đã áp dụng. | Customer, Identity, Account, Transfer, DBA |
| TTL snapshot/version | Yêu cầu logic đã quyết định, thiếu vị trí/cột/kiểu/nullability/lifecycle đã review trong `04`; đồng bộ `04` sau review trước migration bổ sung. | Transfer, DBA, Ops |
| Durable fence / recovery | DEC-TRF-003/010 yêu cầu claim, epoch/token/lease, CAS và retry bền; schema, constraints, lock/crash protocol chưa review. Không chạy recovery tự động khi thiếu proof/fence; không suy rollback chỉ vì lease hết hạn. | Transfer, DBA/Architect, Ops |
| Limit guard/reservation | Cần provider và schema/lock/FK/migration thực; không tạo bảng Limit trong Transfer. Consumer fake theo `05`, sau đó chạy provider + PostgreSQL thật. | Limit, Identity, Transfer |
| Review/evidence | Kiểm PostgreSQL 16 clean + upgrade, catalog FK/index/trigger/check, `flyway validate`, `ddl-auto=validate`, negative constraints, multi-connection T1 race, terminal và TTL; review rollout/forward repair và evidence theo `09` §10. | DBA/Architect, provider owners, QA, Ops |

## 6. Bất nhất và giới hạn nguồn

- `04` §2.7 C-04 dùng cụm “record tạo ... trước T1”, trong khi chính `04` §4.4.3, §7.7.1 và task §5 mô tả insert transfer rồi record **trong T1**. Đây là bất nhất nội bộ cần owner/Architect xác nhận và đồng bộ nguồn; ma trận không sửa nguồn hoặc coi câu shorthand là thay đổi transaction đã mô tả chi tiết.
- `04` §5.1 mô tả record “bắt buộc khi transfer tồn tại” và §7.6 ghi dữ liệu tài chính không xóa; `04` §4.4.3 và retention §9.6 cho phép purge record terminal, đồng thời task §4 giải thích quan hệ tối đa một record. Cần owner/DBA xác nhận và chỉnh shorthand để không cản purge; không suy rằng transfer hoặc ledger được purge.
- Trạng thái và migration được nêu theo snapshot tài liệu/source tại thời điểm lập. Ma trận không xác nhận sign-off, migration SQL, test, provider, hay query plan đã đạt. BE-TRF-02 chỉ được đóng khi các AC và review ở task §8 đầy đủ.
