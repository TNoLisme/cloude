# BE-TRF-01 — Chốt hợp đồng và rủi ro liên module

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-TRANSFER-01`
> **Ưu tiên / kế hoạch:** P0 / W3–W4
> **Trạng thái:** Blocked — các quyết định DEC-TRF-001–012 đã ghi; contract/provider review và evidence chưa hoàn tất
> **Owner:** Vũ
> **Phối hợp / review:** Backend Lead, Architect, API, BA/Product, Identity, Account, Ledger, Limit, Audit, Fraud, Notification, QA, Security, FE và Ops theo từng vấn đề.

## 1. Mục tiêu và phạm vi

Tạo căn cứ thống nhất để các task Transfer tiếp theo có thể triển khai và kiểm thử an toàn: đối chiếu yêu cầu, hợp đồng công khai, quyền sở hữu dữ liệu, ranh giới transaction, các mâu thuẫn nguồn và chính sách còn mở. Đầu ra là hồ sơ quyết định, matrix dependency và oracle kiểm thử được các bên liên quan review; việc viết tài liệu task này không đồng nghĩa các bên đã thống nhất.

Task này không triển khai money path, migration, provider hay tự duyệt chính sách. Mỗi vấn đề chưa chốt phải có đầu mối, ảnh hưởng, phương án, nguồn và trạng thái; chỉ ghi quyết định vào `DECISIONS.md` sau khi người có thẩm quyền quyết định. Không tự đổi nguồn có thẩm quyền hoặc coi giá trị đề xuất là policy đã duyệt. Việc độc lập có thể chuẩn bị song song; AC phụ thuộc quyết định hoặc provider chưa sẵn sàng vẫn **Blocked**.

**Cập nhật 16/11/2026:** người điều hành đã yêu cầu trợ lý quyết định và đồng bộ tài liệu; các lựa chọn được ghi tại [DECISIONS.md](../../../../../DECISIONS.md) DEC-TRF-001–012. Nội dung ở các bảng dưới mô tả yêu cầu và danh mục vấn đề tại thời điểm lập task; trạng thái hiện hành, review và evidence còn thiếu xem [hồ sơ bàn giao](evidence/BE-TRF-01-handoff.md) và [risk register](evidence/BE-TRF-01-risk-register.md). Quyết định không thay sign-off của các owner hay kết quả test.

## 2. Tài liệu cần đọc và thứ tự nguồn

- [[digitalbanking-docs/docs-common/03. SRS.md]]: FR/AC về transfer, atomicity, step-up và audit; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4: schema và key; [[digitalbanking-docs/docs-common/06. System architecture.md]]: boundary và luồng giao dịch.
- [[digitalbanking-docs/docs-common/07. API contracts.md]] §5–§6, §8.3 và OpenAPI: payload, canonical hash, lỗi, retry và replay; [[digitalbanking-docs/docs-common/09. Test plan.md]] §7–§10: test ID và evidence.
- [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5–§10, §16.2: T0–T3, recovery và `OI-CON-01–13`; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §3–§14: provider/consumer contract và `MOD-OI-01–09`.
- [Thiết kế Transfer](../design.md) §1.2–§1.4, §3–§7 và [backlog](README.md) §2: phạm vi, sai lệch, AC và phụ thuộc. Kiểm tra source, contract và quyết định mới trước khi bắt đầu từng phần.

Theo quy tắc nguồn của thiết kế Transfer, `docs-common/01–11` ưu tiên hơn `docs-common/design/` và tài liệu backend; `07` là căn cứ ngữ nghĩa API, YAML cần đồng bộ. Nếu các nguồn có thẩm quyền mâu thuẫn, chuyển BA/Architect/owner phân xử và cập nhật các nguồn liên quan sau quyết định; không tự suy diễn sign-off.

## 3. Đầu việc và tiêu chí nghiệm thu

| ID | Công việc | Đầu ra và điều kiện nghiệm thu |
|---|---|---|
| TRF-01.1 | Lập trace và matrix dependency theo §4; xác nhận provider, consumer, write-owner và contract ID. | Mỗi operation có input/output/nullability, precondition/quyền, transaction/lock, side effect, lỗi, idempotency, dữ liệu nhạy cảm, version và test vector; provider/consumer review theo `05` §13.1. Thiếu giao tiếp trong `05`: nêu phương án thay thế, đưa đề xuất vào `05` và xin review, không giả định API tồn tại. |
| TRF-01.2 | Đối chiếu idempotency/API/schema và đồng bộ với API/FE/QA. | Ghi key global, `IDEMPOTENCY_CONFLICT`, replay terminal HTTP/body gốc (201 hoặc 422) theo `04/07`; tạo việc đồng bộ overview và YAML đang mô tả 200. `202` là tùy chọn async, không thay baseline MVP synchronous. Test vector bao gồm actor khác, payload khác, live/stale và replay FAILED. |
| TRF-01.3 | Chốt policy/protocol step-up theo tổng ngày với Identity, Limit, Architect, BA. | Quy định serialization/reservation hoặc thay contract, 428 trước T1, binding, expiry, retry và rollback; có oracle hai key đồng thời quanh ngưỡng (§6). Chưa được duyệt/chứng minh: **chặn release money path**. |
| TRF-01.4 | Phân xử AC-006 và AC-012 với nhánh sau T1 trong `design/02`. | `AC-012` yêu cầu FAILED khi debit xong và credit lỗi, trong khi `design/02` xử lý lỗi credit technical bằng rollback về PENDING. BA/QA/Architect và Account/Transfer thống nhất oracle cho lỗi thấy trước T1, thay đổi sau T1 và lỗi credit technical; đồng bộ nguồn trước nghiệm thu. Không giả định `FAILED` sau T1 đã thỏa AC-006. |
| TRF-01.5 | Chốt TTL/purge và recovery PENDING bất thường. | Product/BA/Security/BE duyệt retention và hướng dẫn retry/query sau TTL; Transfer/Ledger/Audit/Ops duyệt tiêu chuẩn bằng chứng, thao tác atomic status/idempotency/response, retry/escalation và runbook. `T-REC-02` phải hoàn tất không duplicate khi đủ bằng chứng theo protocol đã chứng minh; trước khi protocol được duyệt, hoặc bằng chứng thiếu/lệch, giữ nguyên và alert/manual; không tự sửa ledger. Không purge PENDING. |
| TRF-01.6 | Theo dõi `OI-CON-02–13`, `MOD-OI-01–09` và audit C2/C1. | Bảng §5 có owner, ảnh hưởng và trạng thái; chốt phương án audit flag C2 cùng C1, lock audit cuối, rollback khi audit lỗi. Policy, version, throughput, multi-instance, notification và correlation đã chọn tại DEC-TRF-009–012; review và evidence còn thiếu chặn AC tương ứng. |
| TRF-01.7 | Chuẩn bị contract suite, integration plan và hồ sơ bàn giao. | Provider/consumer thống nhất vector và oracle §6; xác định test fake và test PostgreSQL/provider thật, owner, evidence và blocker. Mỗi quyết định được liên kết `DECISIONS.md`, nguồn cập nhật, reviewer và bằng chứng; không đánh dấu task hoàn thành khi thiếu AC hoặc review. |

## 4. Matrix giao tiếp cần chốt

Các operation dưới đây đã được mô tả trong `05. Modules contract`; tên/chữ ký phần lớn vẫn là **đề xuất**, không phải xác nhận runtime hoặc sign-off. Transfer gọi public API của provider, không ghi trực tiếp bảng thuộc module khác. T1/T2 phải tham gia cùng PostgreSQL transaction theo contract, không gọi HTTP/broker trong T2.

| Provider / owner theo kế hoạch | Transfer cần | Boundary và điểm cần xác nhận | Nguồn |
|---|---|---|---|
| Identity — Đạt | Session/permission, challenge bind user/hash/key, verify và consume một lần | T0 challenge trước T1; consume trong T1 rollback cùng intent; expiry, race, CSRF và ownership của principal | `05` §5; MOD-OI-01/07 |
| Customer / Account — Đạt | Resolve đích, ownership/status; lock 2 account, debit có điều kiện, credit | Account sở hữu balance; lock ID tăng dần, revalidate dưới lock T2, lỗi/rollback; không lộ PII | `05` §6–§7; MOD-OI-02/08 |
| Ledger — Phương | Post double-entry; đọc evidence reconciliation | Ledger sở hữu entries; post cùng T2, cân bằng và append-only; phân biệt absent/error/mismatch khi đọc | `05` §9; MOD-OI-03 |
| Limit — Vũ | Lock/check và tăng usage | CUSTOMER trước ACCOUNT, check/increment cùng T2; date, config version và race step-up cần quyết định | `05` §10; MOD-OI-01/06 |
| Audit — Thịnh | CREATED C1 trong T1; completion/failure C1 và fraud flag C2 trong T2; conflict C3; evidence | Mandatory audit fail-closed, audit chain lock cuối; chốt staging/batch C2+C1, xử lý lỗi và thứ tự | `05` §11.1; MOD-OI-03/05 |
| Fraud — Thịnh | Evaluate in-process, flag/auto-block | Engine lỗi fail-open với savepoint; lỗi mandatory audit không được nuốt; review count window/version theo DEC-TRF-009 | `05` §11.2; MOD-OI-05/06 |
| Notification — Thịnh | Enqueue outbox trong T2; delivery sau commit T3 | Notification sở hữu outbox/worker; lease, retry, dedupe và lỗi gateway; không giữ OTP plaintext | `05` §11.3; MOD-OI-07 |
| Common — Phương | Problem, request context, canonicalization/hash, masking, Clock/security | Dùng API common hiện có; xác nhận mapping, correlation và kiểm thử tích hợp thực tế | `05` §12.2; MOD-OI-08/09 |
| API / FE / QA / Ops | POST/GET, retry/receipt, oracle và xử lý incident | Chốt status/body/header, quyền đọc, hướng dẫn sau TTL và runbook; phân công reviewer theo từng quyết định | `07` §5, §8.3; `05` §13–§14 |

Với contract đã ghi nhưng chưa chốt chi tiết, dùng quy trình review `05` §13 thay vì tự tạo chữ ký riêng. Nếu provider chưa sẵn sàng, consumer có thể dùng mock/fake trong test/dev theo cùng contract và vector; không đưa stub vào production hoặc coi mock là bằng chứng integration/lock/rollback.

## 5. Danh mục quyết định và rủi ro cần theo dõi

Trạng thái dưới đây là **mở/chờ xác nhận** tại thời điểm lập task; owner là đầu mối theo tài liệu, không biểu thị đã chấp thuận. Theo dõi `MOD-OI-01–09` tại `05` §14, gồm cả OTP lifecycle, quyền đọc và interface/version đã chọn tại DEC-TRF-011/012 nhưng chưa được review/kiểm chứng. Trước khi chốt một mục, kiểm tra quyết định mới nhất và ghi link evidence/reviewer vào hồ sơ thực hiện.

| ID | Vấn đề và ảnh hưởng | Đầu mối | Liên kết / AC bị ảnh hưởng |
|---|---|---|---|
| OI-CON-01 | TTL, duplicate sau purge và client retry | Product/BA, Security/BE | MOD-OI-04; TRF-01.5, 04/06 |
| OI-CON-02 | `IN_PROGRESS` không bền trong T2; state/API | Backend | TRF-01.2, 04 |
| OI-CON-03 | 409 khi live so với resume khi stale; UX/retry | FE/BE | TRF-01.2, 04/06 |
| OI-CON-04 | Key global có thể bị đoán/DoS; scoped key là đề xuất P1 | Security/Backend | MOD-OI-04; TRF-01.2, 02/04 |
| OI-CON-05 | Đổi cấu hình limit trong T2, version/snapshot | BA/Backend/Limit | MOD-OI-06; 05/08 |
| OI-CON-06 | Fraud count window, trạng thái và giao dịch hiện tại | Fraud/BA | MOD-OI-06; 05/08 |
| OI-CON-07 | Retry reconciliation và ngưỡng manual/escalation | Backend/Ops | MOD-OI-03/06; TRF-01.5, 06 |
| OI-CON-08 | Deferred constraint trigger ledger và invariant | Backend/DBA/Ledger | MOD-OI-06; 05/08 |
| OI-CON-09 | Business date/timezone, ranh giới ngày limit | Backend/BA/Limit | MOD-OI-06; 05/08 |
| OI-CON-10 | Throughput global audit lock và mục tiêu tải | Architect/Audit | MOD-OI-06; 05/09 |
| OI-CON-11 | Notification at-least-once, duplicate/dedupe | Product/Backend/Notification | MOD-OI-07; 07/08 |
| OI-CON-12 | Scheduler/worker nhiều instance, claim/lease | DevOps/Backend/Ops | MOD-OI-06/07; 06–08 |
| OI-CON-13 | Correlation của replay body/header | API owner/FE | MOD-OI-08; 03/04/07 |

Các số `02–09` ở cột cuối là task `BE-TRF-02–09`; chúng chỉ ra phần bị ảnh hưởng, không hạ ưu tiên yêu cầu Must. Đề xuất cụ thể và owner gốc xem `design/02` §16.2; step-up race, mâu thuẫn AC-006/AC-012, audit C2 và recovery được theo dõi bổ sung tại `05` §14.

## 6. Oracle kiểm thử và bằng chứng tối thiểu

| Tình huống | Oracle cần các owner thống nhất / kiểm chứng |
|---|---|
| Idempotency | Cùng actor/key/hash terminal replay đúng status/body gốc và header replay; hash hoặc actor khác conflict, không lộ dữ liệu; live trả 409, stale xử lý theo contract được duyệt. Kiểm tra cả 201 và 422, `T-IDEM-01–06` và vector canonicalization theo `07` §5.2. |
| Step-up đồng thời | Hai key khác nhau cùng dưới ngưỡng khi đọc riêng nhưng tổng vượt ngưỡng: protocol được duyệt phải đảm bảo policy và 428 trước T1; kiểm tra DB không transfer/idempotency/ledger cho nhánh 428, binding, expiry, consume một lần và rollback. Kỳ vọng cụ thể theo DEC-TRF-001/002/008. |
| Validation và money | Tách lỗi đã tồn tại ở preflight, thay đổi sau T1 và credit lỗi technical; assert response, transfer, balance, ledger, usage và audit theo oracle BA/QA duyệt. `T-CON-01–04` dùng nhiều connection PostgreSQL, kiểm lock và invariant. |
| Audit/fraud | Inject lỗi engine và lỗi ghi C2/C1 riêng; engine fail-open theo contract, lỗi audit bắt buộc rollback T2, không false success. Chứng minh savepoint, thứ tự audit và transaction thật (`T-AUD-06/07`). |
| Recovery | `T-REC-01–04`: không ledger; đủ ledger/audit; thiếu, partial hoặc mismatch; nhiều worker/crash. Đủ evidence chỉ hoàn tất theo protocol đã duyệt và đồng bộ response/idempotency, không duplicate; thiếu/lệch giữ nguyên và alert. |
| Notification và TTL | Outbox atomic cùng terminal, gateway lỗi/retry/duplicate và worker lease (`T-OUT-01/02`); terminal đủ điều kiện purge theo policy, PENDING không purge, mô tả rủi ro key thành yêu cầu mới sau purge. |

Dùng cùng vector cho fake và provider contract suite; khi provider sẵn sàng chạy integration với implementation thật. PostgreSQL thật, connection độc lập và failure injection là bắt buộc để chứng minh lock, unique, savepoint và atomicity; mock/H2 không đủ. Evidence theo `09` §10: revision, lệnh, môi trường, test ID, kết quả, artifact synthetic không chứa PII/secret, owner và reviewer. Các test ở đây là kế hoạch, chưa có kết quả thực thi.

## 7. Bàn giao và điều kiện đóng task

- Matrix contract có link PR/interface, vector, owner/provider/consumer review và trạng thái dependency; cập nhật `05` nếu phát hiện giao tiếp chưa được mô tả.
- Decision log trong `DECISIONS.md` có người quyết định, ngày, phương án, lý do, nguồn và task bị ảnh hưởng; các nguồn `03/04/07/09`, OpenAPI, overview hoặc thiết kế được đồng bộ theo quyết định và quyền sửa được giao.
- Có hồ sơ issue/owner, oracle, kế hoạch integration và evidence có thể tái lập. Mọi vấn đề chưa quyết định hoặc provider chưa được kiểm chứng phải nêu **Blocked**, người theo dõi và ảnh hưởng; không đánh dấu `[x]` trong backlog cho đến khi toàn bộ AC được Verified và reviewer liên quan xác nhận.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 15/11/2026 | Chi tiết phạm vi, dependency, quyết định mở, oracle và điều kiện nghiệm thu BE-TRF-01. |
| v1.1 | 16/11/2026 | Liên kết DEC-TRF-001–012 và trạng thái review/evidence; chưa nghiệm thu. |
