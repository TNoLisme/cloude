# Kế hoạch triển khai module `transfer`

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-TRANSFER-02`
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 15/11/2026
> **Trạng thái:** Draft — chờ review; tất cả implementation task chưa xác minh
> **Owner:** Vũ
> **Reviewer:** Backend Lead, các owner phụ thuộc, QA, Security — chờ review
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] §2.4, §2.13; [[digitalbanking-docs/docs-common/09. Test plan.md]] §7–§10; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5–§14; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §11–§13; [thiết kế module](../design.md).

Các task dưới đây mô tả công việc và bằng chứng còn cần để hoàn thiện module; việc đã có một phần code hoặc evidence không đồng nghĩa toàn bộ task đạt. Ưu tiên **P0** là điều kiện cho money path/release an toàn; **P1** hoàn thiện trải nghiệm, vận hành và hardening trong phạm vi MVP, không tự hạ bất kỳ yêu cầu Must. Mốc W3–W8 là dự kiến theo `10`, phụ thuộc lịch và năng lực của các owner. Trước mỗi task kiểm tra code hiện tại, hợp đồng và quyết định mới; tái sử dụng common, không sao chép tính năng đã có. Mỗi task có thể làm phần độc lập trước khi dependency sẵn sàng nhưng chỉ đánh dấu hoàn thành khi toàn bộ AC và bằng chứng đạt.

## 1. Checklist và thứ tự phụ thuộc

| Trạng thái | ID / ưu tiên / mốc         | Task                                                     | Phụ thuộc                                                           |
| ---------- | -------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------- |
| [ ]        | **BE-TRF-01 / P0 / W3–W4** | Chốt hợp đồng và các rủi ro liên module                  | API, BA, Identity, Limit, Ledger, Audit, QA, Security               |
| [ ]        | **BE-TRF-02 / P0 / W4**    | Schema, migration và state domain (candidate; xem task/evidence) | 01 (schema/key); migration Account/Identity và FK                   |
| [ ]        | **BE-TRF-03 / P0 / W4**    | API, validation, authorization và query/receipt          | 01; identity/account thật; 02 cho persistence                       |
| [ ]        | **BE-TRF-04 / P0 / W4**    | Idempotency, canonical hash, T1 và step-up               | 01–03; identity challenge, audit C1, common crypto                  |
| [ ]        | **BE-TRF-05 / P0 / W4–W5** | T2: lock, balance, ledger, limit, fraud, audit và outbox | 01–04; account, ledger, limit, audit, fraud, notification APIs thật |
| [ ]        | **BE-TRF-06 / P0 / W4–W5** | Recovery, reconciliation và purge                        | 02, 04–05; Ledger/Audit/Ops; quyết định recovery và TTL             |
| [ ]        | **BE-TRF-07 / P0 / W4–W6** | Tích hợp bảo mật, telemetry và notification              | 03–06; identity/audit/notification/common và Ops                    |
| [ ]        | **BE-TRF-08 / P0 / W4–W6** | Test chức năng, contract, concurrency và failure         | 02–07; PostgreSQL và module thật                                    |
| [ ]        | **BE-TRF-09 / P0 / W6–W8** | Nghiệm thu D1–D5, vận hành và bàn giao                   | 01–08; QA/Security/Ops, FE và môi trường test/load/Cloud khi cần    |

Đường găng: 01 → 02/03 → 04 → 05 → 06/07 → 08 → 09. Contract tests và thiết kế test chuẩn bị song song từ 01; test cụ thể viết cùng mỗi task, không đợi 08. Thiếu dependency ghi **Blocked** cho AC bị ảnh hưởng, không thay production module bằng stub để tuyên bố tích hợp xong.

## 2. Chi tiết công việc và tiêu chí nghiệm thu

### [BE-TRF-01 — Chốt hợp đồng và rủi ro liên module](BE-TRF-01.md)

**Mô tả:** Đối chiếu `03/04/06/07/09`, thiết kế concurrency và OpenAPI; thống nhất contract, phạm vi write-owner, T1/T2, chính sách và kế hoạch integration. Tạo decision record theo quy trình nhóm, liên kết owner và cập nhật nguồn có thẩm quyền khi được giao.

**AC:**

- Ghi nhận key global, `IDEMPOTENCY_CONFLICT`, replay HTTP/body gốc theo `04/07`; tạo việc đồng bộ overview và YAML 200 replay với API/FE/QA. Xác định `202` là tùy chọn async, không ngầm thay MVP synchronous.
- Identity/Limit/Architect/BA review protocol DEC-TRF-008 chống race step-up, 428 trước T1, expiry và retry. Chứng minh oracle hai key đồng thời; release money path **Blocked** khi chưa có review và evidence.
- BA/QA/Architect review quyết định DEC-TRF-003 cho AC-006/012; kiểm thử riêng lỗi phát hiện trước T1, điều kiện đổi sau T1 và credit failure trước nghiệm thu.
- BA/Product/BE chốt retention/purge và hướng dẫn retry sau TTL; Backend/Ops/Ledger/Audit chốt xử lý PENDING có ledger/audit, retry/escalation. `09` T-REC-02 yêu cầu hoàn tất không duplicate khi đủ bằng chứng; chỉ làm theo protocol đã chứng minh và thống nhất tính nhất quán response/idempotency; thiếu/chênh bằng chứng giữ nguyên, cảnh báo.
- Liệt kê owner/trạng thái/ảnh hưởng của `OI-CON-02–13`, gồm business date/timezone, config version, fraud count, audit throughput, multi-instance, notification duplicate và replay correlation; DEC-TRF-009–012 đã chọn phương án; review và evidence thiếu vẫn chặn AC tương ứng. Phân công contract API cùng transaction cho từng owner.

**Evidence:** decision log, liên kết nguồn và owner/reviewer, matrix dependency, contract/test oracle đã thống nhất.

### [BE-TRF-02 — Schema, migration và domain](BE-TRF-02.md)

**Mô tả:** Thiết kế rồi tạo migration forward-only cho `transfer` và `idempotency_record` theo `04`, cùng state/value objects. Phối hợp owner migrations theo thứ tự FK; không sửa V1 hay tạo bảng owner khác trong module này.

**AC:**

- Schema khớp tên/kiểu/constraint/index nguồn; key global, reference unique, FK, amount dương, hai tài khoản khác nhau, trạng thái hợp lệ, challenge unique khi có, response/expiry đúng lifecycle. Có index lookup reference và quét PENDING theo workload.
- Terminal bất biến; không có orphan trong T1 race; JPA không xuất entity ra API. Migration sạch và nâng cấp chạy được trên PostgreSQL, schema validation và rollback/recovery plan migration được review.
- Test constraint, unique race, FK và state transition trên DB thật; các thay đổi schema đề xuất chưa được chốt không tự thêm.

### [BE-TRF-03 — API, validation, quyền và tra cứu](BE-TRF-03.md)

**Mô tả:** Xây controller/DTO và query service cho POST/GET theo `07`, tích hợp session/CSRF và service authorization; hỗ trợ masked receipt.

**Một phần đã có:** boundary HTTP candidate đã triển khai và test bằng fake (DEC-TRF-016): `TransferController`/`TransferHttpConfiguration` trong `com.simulatedbank.transfercandidate.web` (ngoài scan root, đăng ký tường minh khi 04/05 + provider thật + migration sẵn sàng), DTO record `TransferResponse`/`TransferDetail`/`AccountRef`/`FraudFlagSummary`; `TransferCreateService` (re-check `TRANSFER_CREATE`, C3 deny, ủy quyền `TransferOrchestrator`), `TransferQueryService` (read-only, ownership nguồn/đích qua `AccountOwnershipPort`, fraud flags theo quyền), `SecurityDenyAudit` (C3 best-effort + fallback log/metric). Parser `TransferCreateInput` nối endpoint, mang `errors[].field` (10 test). 38 test mới (service/query/HTTP/contract OpenAPI) đạt; `./gradlew check` 338 test xanh. Vẫn **Blocked**: policy PII description/key, scope staff server-side, format reference OQ-02, session/Account/Audit thật, orchestrator 04/05, quota shared đa instance, review API/FE/QA và YAML còn thiếu header `no-store`/`Pragma` + default currency. Xem [evidence BE-TRF-03](evidence/BE-TRF-03-candidate-verification.md).

**AC:**

- Schema, header, 201/Location, Problem và GET đúng `07`/OpenAPI sau đồng bộ; amount decimal string, một destination, VND, description và key validation đúng biên; actor từ session.
- Chỉ CUSTOMER có permission và sở hữu nguồn được tạo; GET chỉ participant hoặc staff đúng permission/scope; test IDOR và cross-customer; staff-only fraud flags không lộ customer. CSRF, `no-store`, masking và lỗi an toàn được xác nhận.
- Test controller/contract và session thật; kiểm tra nguồn/đích bằng account API. Không dùng rate limiter local làm bằng chứng quota toàn cụm.

### [BE-TRF-04 — Idempotency, step-up và T1](BE-TRF-04.md)

**Mô tả:** Chuẩn hóa payload, dùng common JCS/SHA, kiểm tra key/replay; điều phối challenge và tạo intent bền vững trong transaction riêng.

**Một phần đã có:** các primitive `TransferRequestHash`, `IdempotencyDecision`, `StepUpAmountPolicy`, `IdempotencyRetention` và `TransferReferenceGenerator`. `TransferPreflightCandidate` có 13 test fake cho lookup trước preflight, đọc lại khi từ chối, binding T1, `ChallengeRequired` mang hash và `Settlement` receipt; `TransferIntentCandidate` có 14 test fake cho guard/recheck, challenge UUID→BIGINT attach FK, rollback giả lập, đọc sau rollback và TTL fail-closed. Seam `TransferCreateOrchestrator` (candidate, chưa đăng ký) map outcome cho boundary 03, điều phối T0 `requestChallenge` sau gate và map lỗi 409/410/500/503; port `StepUpChallengePort` ánh xạ TRFC-ID-02/05. Gap UUID/BIGINT `05` §13.4 đã đóng trong candidate (DEC-TRF-017). Candidate tự hash payload immutable và kiểm tra binding giá trị T1; Account provider thật phải xác minh resolution, caller phải xác thực và phân quyền, và HTTP/security/contract tests vẫn cần trước activation. Chưa có provider adapter thật, wiring preflight (findVerified/ownership/TTL config), authorization/PII/security hoàn chỉnh, replay HTTP hay proof guard/challenge/transaction liên module; các AC dưới đây vẫn chưa hoàn tất. Xem [evidence](evidence/BE-TRF-04-05-candidate.md).

**AC:**

- Hash có test vector theo `07 §5.2`, kể cả scale amount, null/rỗng, field order và destination type; không log raw key/body/PII. Cùng key/hash terminal replay status/body gốc với header; khác hash/actor conflict + C3, không lộ dữ liệu; nonterminal → HTTP 409 `Retry-After`, recovery do worker nội bộ theo DEC-TRF-004.
- T0 challenge bind user/hash/key; nhánh 428 không tạo transfer/idempotency/ledger, request khác cùng key có thể commit đồng thời. T1 consume VERIFIED chưa hết hạn đúng binding, một lần; rollback T1 hoàn lại consume. Test guard/reservation, key race và PENDING qua ngày theo DEC-TRF-008.
- T1 insert transfer rồi insert key `ON CONFLICT`; request thua rollback toàn bộ trước khi đọc lại, không orphan. Audit CREATED fail rollback T1; T1 commit để lại PENDING/RECEIVED khi T2 chưa chạy. Test cùng key nhiều connection, challenge race/expiry và crash sau T1.

### BE-TRF-05 — T2 money transaction

**Mô tả:** Điều phối owner APIs trong một PostgreSQL transaction, với lock order và invariant theo thiết kế; không HTTP/broker trong T2.

**Một phần đã có:** `TransferSettlementCandidate` chưa đăng ký runtime; kiểm tra epoch/token fence và yêu cầu rollback khi port lỗi. Có 19 test fake, gồm recovery `MANDATORY`, và 5 test PostgreSQL dùng provider/table synthetic, gồm recovery rollback-only theo [evidence](evidence/BE-TRF-04-05-candidate.md). Test PostgreSQL kiểm tra transaction trong fixture, không chứng minh provider thật hay production fencing. Candidate chưa thực hiện fraud fail-open savepoint, credit savepoint, Tfail, retry, reconciliation hoặc recovery đầy đủ; money path, security và các AC dưới đây vẫn chưa hoàn tất.

**AC:**

- Đọc context không lock → guard CUSTOMER → transfer/fence → account ID tăng → usage customer/account → business flush → audit cuối theo DEC-TRF-008; revalidate ownership/status/balance/limit, debit có điều kiện, credit và hai entry ledger cân bằng. Capture terminal timestamp sau account lock, trước usage lock; usage chỉ tăng khi COMPLETED. Không balance âm, không double debit, không ledger cho FAILED/PENDING bình thường.
- Fraud engine lỗi fail-open có savepoint thực sự; auto-block commit FAILED trước money. Audit `FRAUD_FLAG_CREATED` C2 theo flag ID tăng rồi C1 sau business flush, fail-closed; lỗi audit không bị savepoint nuốt. Outcome nghiệp vụ FAILED commit audit/outbox/response, không ledger/usage; nếu T2 rollback được xác nhận thì PENDING/RECEIVED, còn commit chưa rõ thì xác minh primary và không suy đoán trạng thái.
- Status/idempotency response/outbox và audit completion cùng commit; không terminal state transition sai; retry deadlock/lock timeout bounded trên toàn transaction mới. Test PostgreSQL với failure injection và các owner thật, xác nhận transaction propagation.

### BE-TRF-06 — Recovery, reconciliation và purge

**Mô tả:** Job startup/định kỳ claim PENDING quá ngưỡng, đối soát và dùng lại T2; phối hợp runbook với Ops, ledger và audit.

**AC:**

- Discovery không lock → transaction claim lấy guard rồi `FOR UPDATE SKIP LOCKED`, reread, ghi epoch/token/lease bền và commit → transaction xử lý lấy lại guard/transfer/fence, kiểm evidence và execute cùng transaction; hai instance không xử lý trùng; chỉ resume khi đủ evidence an toàn và fence cho phép (DEC-TRF-014).
- T-REC-01–04 đạt, bao gồm hoàn tất PENDING khi đủ evidence per-transfer Account/Ledger/Limit/Audit, mode và response theo DEC-TRF-006; đồng bộ idempotency/response không tạo ledger kép. Thiếu/lệch/error hoặc outcome không rõ giữ nguyên trạng thái thực tế và alert/manual; terminal không bị ghi đè.
- Purge chỉ record terminal đủ điều kiện; TTL mặc định/tối thiểu 24h từ `completed_at` theo DEC-TRF-005; không purge PENDING kể cả quá TTL. Test long-PENDING, concurrent purge/retry và key sau purge có thể thành yêu cầu mới. Cung cấp runbook và evidence crash/restart, audit failure, nhiều worker.

### BE-TRF-07 — Security, observability và notification

**Mô tả:** Kết nối common context, audit, outbox và metrics; chốt vận hành liên module.

**AC:**

- Correlation liên kết request/challenge/transfer/ledger/audit/outbox; audit C1 fail-closed và C3 fallback/alert theo catalog. Logs, errors, audit và outbox không lộ credential, key, OTP hoặc PII đầy đủ; test leak và log injection.
- Metric outcome, pending age, idempotency, lock và latency dùng label bounded; alert audit/PENDING/deadlock/backlog có owner/runbook. Không dùng reference/account/user làm label.
- Outbox tạo atomically với terminal; worker delivery sau commit có lease/retry/backoff, dedupe theo thỏa thuận; gateway lỗi hoặc crash không đổi transfer và không mất event. Test gửi lại/multi-instance.

### BE-TRF-08 — Kiểm thử tích hợp và hồi quy

**Mô tả:** Thực thi các test quy định tại [TKCT §7](../design.md), bổ sung test độc lập cho các task; không chứng minh invariant bằng mock/H2.

**AC:**

- D1 `T-CON-01–04` và limit race; D2 `T-IDEM-01–06`; D3 `T-REC-01–04`, `T-AUD-06/07`, `T-OUT-01/02` có evidence PostgreSQL, nhiều connection và assert cả balance/ledger/usage/audit/outbox/response.
- Step-up single-use, ngưỡng lũy kế đồng thời, ownership/IDOR/CSRF, fraud fail-open/auto-block và lỗi audit C2 khi ghi fraud flag, schema/contract và masking đạt; audit C2 lỗi phải rollback T2. Test terminal replay của cả 201 và 422; crash ở các cửa sổ T1/T2/T3.
- `./gradlew check` và ArchUnit đạt; test ID, revision, lệnh, môi trường, output và artifact không chứa PII/secret được lưu theo `09 §10`; bất kỳ phần thiếu dependency ghi Blocked.

### BE-TRF-09 — Nghiệm thu, vận hành và bàn giao

**Mô tả:** Tập hợp evidence liên module, chạy D1–D5, kiểm tra readiness và bàn giao cho QA/Security/Ops/FE.

**AC:**

- D1–D4 có report, D5 load theo `09` với mục tiêu 20 RPS × 10 phút, p95/p99 và kiểm invariant; không đánh dấu pass nếu chưa có môi trường/evidence. Regression sau fix và môi trường triển khai theo kế hoạch W6–W8 được ghi riêng.
- Mọi Must AC, sự khác biệt contract, policy §1.4 của TKCT, runbook PENDING/audit/DB/outbox, retention và ownership được xử lý hoặc ghi blocker release có owner; review bởi các bên liên quan, không tự sign-off.
- Bàn giao client retry/step-up/receipt, support tra reference và giới hạn TTL; cập nhật tài liệu module và index liên quan theo phạm vi được giao, lưu test report và bằng chứng không chứa dữ liệu thật.

## 3. Kiểm thử độc lập và kiểm tra sau khi ghép module

Khi provider chưa có, dùng mock/stub/fake **chỉ trong test/dev**, bám operation và outcome trong `docs-common/design/05. Modules contract.md`: Identity (session/challenge), Account (ownership, lock và kết quả debit/credit), Limit (guard, reservation và usage), Ledger (posting/evidence), Audit (C1/C2), Fraud và Notification (decision/outbox). Test consumer phải kiểm tra mapping, thứ tự điều phối, failure và không tạo side effect sai; fake không được đóng giả provider production hoặc chứng minh lock/rollback. Fixture khóa synthetic của schema candidate chỉ chứng minh DDL đối với fixture đó.

Sau khi ghép các provider thật, chạy lại contract suite với implementation thật và kiểm tra trên PostgreSQL nhiều connection: migration và FK thật; T1 rollback/key race/challenge/reservation; T2 lock order, balance/ledger cân bằng, limit, fraud savepoint, audit C1/C2 và outbox atomic; crash, commit chưa rõ, fence và recovery; purge/replay/TTL; authorization/IDOR/CSRF; notification lease/retry và gateway trùng. Ghi revision, môi trường, test ID và evidence theo `09`; D1–D5, load và review vẫn là gate riêng. Không gọi các test với fake là end-to-end hoặc đánh dấu task hoàn tất khi thiếu evidence tương ứng.

## 4. Quy tắc cập nhật tiến độ

Mỗi task chỉ chuyển `[x]` khi toàn bộ AC được **Verified**, evidence có thể tái lập và dependency liên module được kiểm chứng. Ghi link report, revision, lệnh, kết quả, blocker và người review bên cạnh task khi thực hiện; chưa review không đồng nghĩa sign-off. Lỗi hoặc thiếu môi trường ghi `Blocked`, không xóa tiêu chí để đóng task. Tham chiếu trạng thái/evidence [[digitalbanking-docs/docs-common/09. Test plan.md]] §2.2, §8, §10.

## Change Log

| Version | Ngày | Người cập nhật | Mô tả |
|---|---|---|---|
| 1.0 | 15/11/2026 | Codex | Tạo backlog, phụ thuộc, AC và quy tắc theo dõi implementation. |
| 1.1 | 16/11/2026 | Codex | Ghi primitive và T1/T2 candidate, giới hạn fake/fixture và kiểm tra provider sau ghép. |
| 1.2 | 16/11/2026 | Codex | Cập nhật preflight và evidence T2/recovery với fake và PostgreSQL synthetic. |
| 1.3 | 16/11/2026 | Qoder | Liên kết tài liệu chi tiết BE-TRF-03; không thay đổi AC hay trạng thái task. |
| 1.4 | 16/11/2026 | Qoder | Ghi boundary HTTP candidate BE-TRF-03 (controller/DTO/query/service/C3 fake), 38 test mới và check 338 xanh; AC vẫn Blocked chờ provider thật/review. |
| 1.5 | 16/11/2026 | Qoder | Liên kết tài liệu chi tiết BE-TRF-04; không thay đổi AC hay trạng thái task. |
<<<<<<< HEAD
| 1.6 | 17/11/2026 | Qoder | Ghi seam orchestrator BE-TRF-04, boundary challenge UUID, port T0 và reference generator (DEC-TRF-017); 28 test mới, check 366 backend/163 Transfer xanh; AC vẫn Blocked chờ provider thật/review. |
=======
>>>>>>> origin/dev2
