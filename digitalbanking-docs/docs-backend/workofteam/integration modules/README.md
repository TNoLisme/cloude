# Kế hoạch tích hợp Backend — một người chịu trách nhiệm

> **Mã:** BE-INT-PLAN-01  
> **Ngày cập nhật:** 18/11/2026  
> **Người thực hiện và chốt duy nhất:** người điều hành/merge owner  
> **Quyền thực hiện:** được sửa source, public contract, adapter, test, migration chưa phát hành và tài liệu của mọi module liên quan. Không cần phân công, review hoặc sign-off của cá nhân khác (DEC-INT-003).

## 1. Mục tiêu và nguyên tắc

Ghép các module đã phát triển độc lập thành **một ứng dụng Spring Boot chạy với provider thật**. Người điều hành tự quyết khác biệt giữa thiết kế và source theo thứ tự `docs-common/01–11` → `docs-common/design` → `docs-backend`; ghi quyết định đổi nghĩa vào [DECISIONS.md](../../../../DECISIONS.md) và cập nhật [Modules contract](../../../docs-common/design/05.%20Modules%20contract.md) trước khi bật production wiring. Không chờ cá nhân khác xử lý dependency.

Quyền chốt là quyền quyết định kỹ thuật và merge, **không thay bằng chứng chạy**. Không gọi fake là provider thật, không đánh dấu test `PASS` khi chưa chạy, không bật money path khi thiếu JOIN/rollback/evidence hoặc migration chưa sạch. Khi module thiếu operation, người điều hành tự hiện thực trong package provider hoặc chọn phương án thay thế đã ghi ở Modules contract; mock chỉ dùng trong test. Giữ migration forward-only đối với DB đã áp dụng.

## 2. Gate kỹ thuật

| Gate | Điều kiện đạt | Khi chưa đạt |
|---|---|---|
| G0 — contract | Ma trận `TRFC-*`/`MOD-OI-*`, DTO, nullability, lỗi, quyền, TX, lock order, vector và adapter khớp; `provider.api` là boundary duy nhất. Người điều hành tự chốt. | Ghi gap và quyết định; không kích hoạt dependency production còn thiếu. |
| G1 — build/schema | `compileJava`, `testClasses`, một chuỗi Flyway sạch từ DB trống, Hibernate validate và đường nâng cấp DB đã áp dụng được chứng minh. | Sửa lỗi source/migration theo thứ tự dependency; không coi candidate SQL ngoài `db/migration` là đã chạy. |
| G2 — integration | T0/T1/T2/T3, Admin và recovery dùng provider thật trên cùng datasource/transaction manager; fault/race tests PostgreSQL chứng minh rollback, locking, audit, ledger, limit và outbox. | Giữ route money/approval liên quan chưa active; test fake chỉ là bằng chứng consumer. |
| G3 — phát hành | Regression, OpenAPI, security, D1–D5 và Test Plan §8.2/§10 có artifact; không còn Critical/High thuộc phạm vi release. | Không phát hành release candidate. |

## 3. Thứ tự thực hiện

Các mốc `T1`–`T5` là **thứ tự công việc tương đối**, không phải cam kết ngày lịch. Một người thực hiện tuần tự; lặp lại gate sau khi sửa lỗi.

| Bước | Việc làm và kết quả cần lưu |
|---|---|
| T1 | Khóa ma trận [BE-INT-01](BE-INT-01.md), vector và quyết định; sửa lỗi compile, kiểm kê migration/version/checksum. |
| T2 | Hợp nhất migration, boot ứng dụng; nối Identity/Customer/Account/Limit read và T0/T1. Chạy binding, 428, race, rollback. |
| T3 | Nối Account/Ledger/Limit/Fraud/Audit trong T2; chạy lock order, debit/credit, limit usage, savepoint và audit rollback PostgreSQL. |
| T4 | Nối Notification/Customer destination, Admin approval và recovery evidence; chạy outbox/retry, maker/checker, claim/fence. |
| T5 | Chạy full regression, OpenAPI/security, D1–D5; ghi report, known issues và tự quyết định merge/release dựa trên evidence. |

## 4. Backlog một owner

**Mọi task dưới đây do người điều hành/merge owner tự thực hiện và tự chốt.** Tên module chỉ quyền sở hữu code/data, không là người cần phê duyệt. Cập nhật task detail, Modules contract và DECISIONS khi source hoặc nghĩa contract đổi.

| Task | Phạm vi | Bằng chứng đóng task |
|---|---|---|
| `BE-INT-01` | Ma trận design ↔ Java API ↔ consumer ↔ migration cho mọi `TRFC-*`, `MOD-OI-*`, port dùng chung; xử lý gap DTO/ID/TX/lỗi. Phần ma trận/định danh đã DONE; G0 còn BLOCKED theo gap trong task. | Ma trận, quyết định, vector và report có kết quả/giới hạn trung thực; người điều hành chốt. G0 chỉ PASS khi contract/API liên quan được kiểm chứng. |
| `BE-INT-02` | Compile và fresh Flyway V1–V17 đã PASS; tiếp tục đường nâng cấp DB từng áp dụng migration module-local V2–V6, kiểm checksum/restore và full regression. | Build, fresh migration, Hibernate validate và checksum/upgrade report cho từng lịch sử DB. |
| `BE-INT-03` | Nối session, Customer, Account read/ownership/staff scope. | HTTP/PostgreSQL 401/403/404, masking, IDOR, session thật. |
| `BE-INT-04` | Hoàn thiện Limit guard, snapshot, reservation/settlement, Admin mutation và recovery evidence. | Race theo customer, ngày nghiệp vụ, version, rollback, no double count. |
| `BE-INT-05` | Nối Transfer T0/T1 với Identity/Audit/Limit; idempotency trước preflight. | 428 không tạo business row, binding/expiry/single-use, T1 rollback và replay. |
| `BE-INT-06` | Nối T2 Account/Ledger/Limit theo lock order và cùng transaction. | Debit/credit/ledger/limit atomic, concurrency và failure injection. |
| `BE-INT-07` | Nối Fraud/Audit C2→C1, C3 fallback. | Savepoint, block/engine error, audit failure rollback, hash chain. |
| `BE-INT-08` | Nối OTP/outbox, Customer destination theo purpose lúc gửi. | Dispatch fail-closed, outbox atomic, lease/retry/dedupe/privacy. |
| `BE-INT-09` | Nối Admin maker/checker với Identity/Limit/Audit. | Same-key replay, hai checker, stale version, provider/audit rollback. |
| `BE-INT-10` | Nối recovery evidence per-transfer và durable claim/fence. | Absence/partial/mismatch/DB error/crash, không re-post khi proof thiếu. |
| `BE-INT-11` | Ghép Frontend với API thật và OpenAPI. | Journey login/account/transfer/admin, status/Problem/CSRF/replay. |
| `BE-INT-12` | Full regression, D1–D5, security và release decision. | Test Plan §10 report, raw artifact, revision, known issues, quyết định của người điều hành. |

## 5. Quy tắc thực thi và báo cáo

1. Trước khi sửa contract, đọc nguồn ưu tiên, xác định conflict và ghi quyết định vào `DECISIONS.md`; cập nhật `05. Modules contract.md` nếu có operation/alternative mới.
2. Sửa từng lát nhỏ: compile → contract/unit test → PostgreSQL integration test → ghi bằng chứng. Khi dependency chưa có, dùng fake theo contract **chỉ trong test**, rồi thay bằng provider thật trước khi đánh dấu G2.
3. Mỗi kết quả test ghi source revision, môi trường, lệnh, expected/actual, artifact và giới hạn theo [Test Plan §10](../../../docs-common/09.%20Test%20plan.md). Kết quả cũ không chứng minh source mới.
4. Người điều hành tự quyết định merge và release. Giữ trạng thái `BLOCKED` cho gate kỹ thuật chưa đạt; không yêu cầu chữ ký cá nhân khác để cập nhật task hoặc tài liệu.

## Change log

| Ngày | Nội dung |
|---|---|
| 18/11/2026 | Viết lại kế hoạch theo chỉ đạo người điều hành: một người có toàn quyền sửa và chốt mọi module integration; bỏ phân công/sign-off theo cá nhân, giữ gate kỹ thuật theo evidence. |
