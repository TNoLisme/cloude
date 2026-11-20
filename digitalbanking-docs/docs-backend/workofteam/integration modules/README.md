# Kế hoạch tích hợp Backend — một người chịu trách nhiệm

> **Mã:** BE-INT-PLAN-01  
> **Ngày cập nhật:** 20/11/2026<br>
> **Người thực hiện và chốt duy nhất:** người điều hành/merge owner  
> **Quyền thực hiện:** được sửa source, public contract, adapter, test, migration chưa phát hành và tài liệu của mọi module liên quan. Không cần phân công, review hoặc sign-off của cá nhân khác (DEC-INT-003).

## 1. Mục tiêu và nguyên tắc

Ghép các module đã phát triển độc lập thành **một ứng dụng Spring Boot chạy với provider thật**. Người điều hành tự quyết khác biệt giữa thiết kế và source theo thứ tự `docs-common/01–11` → `docs-common/design` → `docs-backend`; ghi quyết định đổi nghĩa vào [DECISIONS.md](../../../../DECISIONS.md) và cập nhật [Modules contract](../../../docs-common/design/05.%20Modules%20contract.md) trước khi bật production wiring. Không chờ cá nhân khác xử lý dependency.

Quyền chốt là quyền quyết định kỹ thuật và merge, **không thay bằng chứng chạy**. Không gọi fake là provider thật, không đánh dấu test `PASS` khi chưa chạy, không bật money path khi thiếu JOIN/rollback/evidence hoặc migration chưa sạch. Khi module thiếu operation, người điều hành tự hiện thực trong package provider hoặc chọn phương án thay thế đã ghi ở Modules contract; mock chỉ dùng trong test. Giữ migration forward-only đối với DB đã áp dụng.

**Phạm vi test local — DEC-INT-007:** mặc định chỉ chạy method/class liên quan trực tiếp và dependency bị ảnh hưởng, giới hạn bằng `--tests` và ghi lý do chọn. Khi PASS thì dừng; chỉ mở rộng khi lỗi/thay đổi mới tạo căn cứ cụ thể. Không tự chạy toàn suite hoặc lệnh `build`/`check`/`clean build` kéo toàn bộ test. Full suite chỉ chạy khi người điều hành yêu cầu rõ; các mục “full regression” bên dưới là công việc cần lên phạm vi/thời điểm, không phải lệnh tự chạy trên local.

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
| T5 | Chạy regression theo phạm vi đã chốt, OpenAPI/security, D1–D5; full suite theo yêu cầu rõ của người điều hành (DEC-INT-007). Ghi report, known issues và quyết định merge/release dựa trên evidence. |

## 4. Backlog một owner

**Mọi task dưới đây do người điều hành/merge owner tự thực hiện và tự chốt.** Tên module chỉ quyền sở hữu code/data, không là người cần phê duyệt. Cập nhật task detail, Modules contract và DECISIONS khi source hoặc nghĩa contract đổi.

| Task        | Phạm vi                                                                                                                                                                                                                                                                                                              | Bằng chứng đóng task                                                                                                                                                  |
| ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `BE-INT-01` | Ma trận design ↔ Java API ↔ consumer ↔ migration cho mọi `TRFC-*`, `MOD-OI-*`, port dùng chung; xử lý gap DTO/ID/TX/lỗi. Phần ma trận/định danh đã DONE; G0 còn BLOCKED theo gap trong task.                                                                                                                         | Ma trận, quyết định, vector và report có kết quả/giới hạn trung thực; người điều hành chốt. G0 chỉ PASS khi contract/API liên quan được kiểm chứng.                   |
| `BE-INT-02` | **DONE / G1 VERIFIED cho DB canonical mới** theo DEC-INT-005/006; full clean build PASS **2.285 test / 202 class**, fresh/upgrade V1–V19, Hibernate/packaged boot và legacy fail-closed/restore PASS. V18 giới hạn quyền approval, V19 chặn NaN; V1–V17 giữ nguyên. Copy dữ liệu legacy thật N/A cho môi trường này. | [Task/checklist](BE-INT-02.md), [verification report](BE-INT-02-verification.md); build exit 0, Hibernate validate, checksum/upgrade và artifact gắn source manifest. |
| `BE-INT-03` | [Chi tiết task](BE-INT-03.md): nối session, Customer, Account read và ownership theo DEC-INT-008/009. **Verified** cho single-bank MVP.                                                                                                                                                                              | [Báo cáo](BE-INT-03-verification.md) ghi HTTP/PostgreSQL, lỗi provider, masking, IDOR và C3. Diện kiểm thử mục tiêu 8/8 PASS.                                         |
| `BE-INT-04` | **Verified** cho Limit provider, adapter Transfer và V20. [Chi tiết task](BE-INT-04.md)                                                                                                                                                                                                                              | [Báo cáo](BE-INT-04-verification.md) ghi 340 ca liên quan PASS, gồm fresh/upgrade V20, race, ranh giới ngày và hiệu lực, rollback và usage đúng một lần.              |
| `BE-INT-05` | **Verified** theo [chi tiết task](BE-INT-05.md) cho Transfer T0/T1 với Customer/Account/Identity/Audit/Limit và idempotency trước preflight.                                                                                                                                                                         | [Báo cáo](BE-INT-05-verification.md) ghi binding, race, rollback, replay và điều kiện activation.                                                                     |
| `BE-INT-06` | **Verified** theo [chi tiết task](BE-INT-06.md) cho T2 Account/Ledger/Limit, durable claim, terminal replay và Audit C1.                                                                                                                                                                                             | [Báo cáo PostgreSQL](BE-INT-06-verification.md) ghi debit/credit/ledger/usage nguyên tử, race và rollback sau debit.                                                  |
| `BE-INT-07` | **Verified** theo [chi tiết task](BE-INT-07.md) cho Fraud thật trong T2, Audit C2→C1 và C3 fallback.                                                                                                                                                                                                                 | [Kiểm chứng PostgreSQL](BE-INT-07-verification.md) gồm rule/version, savepoint, block/replay, rollback C2/C1, hash chain và C3.                                       |
| `BE-INT-08` | Nối OTP/outbox, Customer destination theo purpose lúc gửi.                                                                                                                                                                                                                                                           | Dispatch fail-closed, outbox atomic, lease/retry/dedupe/privacy.                                                                                                      |
| `BE-INT-09` | Nối Admin maker/checker với Identity/Limit/Audit.                                                                                                                                                                                                                                                                    | Same-key replay, hai checker, stale version, provider/audit rollback.                                                                                                 |
| `BE-INT-10` | Nối recovery evidence per-transfer và durable claim/fence.                                                                                                                                                                                                                                                           | Absence/partial/mismatch/DB error/crash, không re-post khi proof thiếu.                                                                                               |
| `BE-INT-11` | Backend QA đã có baseline OpenAPI v1.1.0 và smoke HTTP theo [hướng dẫn QA](../../deployment/qa-backend-local.md). Ghép Frontend với API thật và OpenAPI là bước tiếp theo.                                                                                                                                                                                                                                                                               | Journey login/account/transfer/admin, status/Problem/CSRF/replay.                                                                                                     |
| `BE-INT-12` | Full regression, D1–D5, security và release decision.                                                                                                                                                                                                                                                                | Test Plan §10 report, raw artifact, revision, known issues, quyết định của người điều hành.                                                                           |

## 5. Quy tắc thực thi và báo cáo

1. Trước khi sửa contract, đọc nguồn ưu tiên, xác định conflict và ghi quyết định vào `DECISIONS.md`; cập nhật `05. Modules contract.md` nếu có operation/alternative mới.
2. Sửa từng lát nhỏ: compile → contract/unit test liên quan → PostgreSQL integration test liên quan → ghi bằng chứng. Chọn method/class nhỏ nhất đủ kiểm thay đổi và dependency, ghi lý do; PASS thì dừng. Tuân DEC-INT-007, không tự kéo toàn suite qua `build`/`check`. Khi dependency chưa có, dùng fake theo contract **chỉ trong test**, rồi thay bằng provider thật trước khi đánh dấu G2.
3. Mỗi kết quả test ghi source revision, môi trường, lệnh, expected/actual, artifact và giới hạn theo [Test Plan §10](../../../docs-common/09.%20Test%20plan.md). Kết quả cũ không chứng minh source mới.
4. Người điều hành tự quyết định merge và release. Giữ trạng thái `BLOCKED` cho gate kỹ thuật chưa đạt; không yêu cầu chữ ký cá nhân khác để cập nhật task hoặc tài liệu.

## Change log

| Ngày | Nội dung |
|---|---|
| 18/11/2026 | Viết lại kế hoạch theo chỉ đạo người điều hành: một người có toàn quyền sửa và chốt mọi module integration; bỏ phân công/sign-off theo cá nhân, giữ gate kỹ thuật theo evidence. |
| 18/11/2026 | DEC-INT-007: chỉ chạy test liên quan trên local; full suite cần yêu cầu rõ của người điều hành, không tự suy từ mục “full regression”. |
| 19/11/2026 | BE-INT-04 xác minh provider Limit và migration V20 theo DEC-INT-011. |
| 20/11/2026 | BE-INT-09 Verified theo DEC-INT-018: provider thật, V23 khóa role tối thiểu, pha Audit cuối và 228 test liên quan có evidence PASS theo từng class. |
| 20/11/2026 | BE-INT-10 Verified theo DEC-INT-019. Recovery và Tfail với provider thật, claim/fence và migration V24. |
