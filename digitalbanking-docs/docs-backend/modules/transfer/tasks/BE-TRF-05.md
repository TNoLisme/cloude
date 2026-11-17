# BE-TRF-05 — T2 money transaction

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** `BE-TASK-TRANSFER-05`  
> **Ưu tiên / kế hoạch:** P0 / W4–W5  
> **Trạng thái:** In progress / Blocked — có T2 candidate chưa đăng ký production; các AC và tích hợp provider thật chưa được nghiệm thu.  
> **Owner:** Vũ  
> **Phối hợp / review:** Account/Customer, Ledger, Limit, Fraud, Audit, Notification, Identity, Backend Lead/Architect, DBA, QA, Security và Ops theo phạm vi.

## 1. Mục tiêu, phạm vi và hiện trạng

Điều phối một giao dịch PostgreSQL T2 cho transfer đã tạo ở T1: kiểm tra lại điều kiện nghiệp vụ dưới lock; ghi debit, credit, ledger, settlement limit, trạng thái terminal, response idempotency, outbox và audit theo cùng ranh giới commit. T2 không gọi HTTP, broker, gateway hay đợi người dùng trong transaction. Các owner giữ quyền sở hữu dữ liệu và cung cấp API tham gia cùng transaction; Transfer không truy cập bảng/entity private của module khác.

Phạm vi gồm T2 thành công, business rejection sau T1, phân loại technical failure, protocol an toàn cho credit failure, retry transaction có giới hạn, contract tests và bằng chứng PostgreSQL. T0/T1, canonical hash và challenge thuộc [BE-TRF-04](BE-TRF-04.md); discovery/claim recovery, reconciliation và purge thuộc BE-TRF-06; notification delivery và telemetry wiring thuộc BE-TRF-07. BE-TRF-05 phải cung cấp executor và boundary an toàn để recovery dùng lại, không tự tuyên bố BE-TRF-06 đã hoàn thành.

**Hiện trạng:** `TransferSettlementCandidate` là candidate chưa được đăng ký runtime. Candidate so sánh cả epoch và token fence; 19 test fake gồm recovery `MANDATORY`, và 5 test PostgreSQL dùng provider/table synthetic, theo [evidence ngày 17/11/2026](evidence/BE-TRF-04-05-candidate.md). Đây là snapshot cũ, không phải lần chạy mới hay proof provider/fence production. Candidate nay có nhánh fraud fail-open qua savepoint chỉ khi provider opt-in và chứng minh transaction/persistence context usable; mặc định fail-closed. `Flag.ruleVersion` đã đổi thành `int > 0`. Credit savepoint, Tfail, retry, reconciliation và recovery đầy đủ vẫn chưa có; xem [evidence bổ sung](evidence/BE-TRF-05-savepoint.md). Chưa có bằng chứng money path, security, migration/provider thật hoặc release. Không đánh dấu AC Verified chỉ vì fake/fixture pass.

## 2. Nguồn và dependency

- [Backlog và AC](README.md) §BE-TRF-05; [thiết kế Transfer](../design.md) §§1–3, 5–7; [BE-TRF-02](BE-TRF-02.md) về schema/migration; [BE-TRF-04](BE-TRF-04.md) về T1/idempotency.
- [[digitalbanking-docs/docs-common/03. SRS.md]] AC-006–014, AC-026, AC-034; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §§4.4–4.8; [[digitalbanking-docs/docs-common/07. API contracts.md]] §§5–6, 8.3; [[digitalbanking-docs/docs-common/09. Test plan.md]] §§7, 10.
- [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §§4, 7.2–11, 13.4; [DECISIONS.md](../../../../../DECISIONS.md) DEC-TRF-003–011, 013–015. Nguồn chuẩn cấp common và API có thứ tự ưu tiên theo thiết kế Transfer; mâu thuẫn chưa được phân xử phải chuyển owner/điều hành, không tự coi là sign-off.

| Dependency | Contract / vai trò | Tình trạng và cách làm |
|---|---|---|
| BE-TRF-01–04 | T1 commit PENDING/RECEIVED, reservation và audit CREATED; schema, identity/context, key/response | Candidate và migration chưa chứng minh production; T2 không bao T0–T2 trong outer transaction. |
| Account | `TRFC-AC-03/04/05`: lock accounts, conditional debit, credit | JOIN T2; revalidate ownership/status/currency và balance; provider thật, propagation và rollback cần chứng minh. |
| Limit | `TRFC-LI-02/04/05`: guard, snapshot/check, settle/release reservation | JOIN T2; config/scope và usage đồng thời cần provider thật, migration và test race. |
| Ledger | `TRFC-LE-01`: post và xác minh hai entry | JOIN T2; duplicate/partial/mismatch rollback, không tự coi duplicate là thành công. |
| Fraud | `TRFC-FR-01`: evaluate, flag và decision | JOIN T2; savepoint, rule/version và transaction/persistence context cần chứng minh. |
| Audit | `TRFC-AU-01`: C2/C1 `recordInTransaction` | JOIN/MANDATORY, fail-closed, audit phase cuối; cần proof chain lock và rollback. |
| Notification | `TRFC-NO-01`: enqueue outcome đã mask | JOIN T2; delivery sau commit do Notification sở hữu; lỗi insert rollback T2. |
| BE-TRF-06 / Ops | Durable claim/fence, evidence và xử lý unknown commit | Claim/recovery protocol và runbook có ở [candidate BE-TRF-06](BE-TRF-06.md). Provider evidence, Tfail và rollout còn Blocked. Lease expiry chuyển MANUAL khi outcome chưa rõ. |

`05` §13.4 đã có baseline và phương án phát triển độc lập: consumer-owned seam cùng fake theo contract chỉ trong test/dev, provider-owned DTO/API khi ghép; không tạo fake production bean. Nếu thiếu provider hoặc transaction proof, giữ money path **Blocked**. Không cần thêm dependency liên module mới trong task này.

## 3. Đầu việc và tiêu chí chấp nhận

| ID | Đầu việc / kết quả cần chứng minh |
|---|---|
| TRF-05.1 | T2 có ranh giới transaction, primary read, guard/fence, lock order và bounded retry theo §4; stale worker không ghi tiền/terminal. |
| TRF-05.2 | Kiểm tra lại account và limit dưới lock; debit có điều kiện, credit; đúng hai ledger entry cân bằng, settlement usage đúng một lần (§5). Không âm, không double debit, không ledger cho FAILED/PENDING bình thường. |
| TRF-05.3 | Fraud BLOCK trước money; engine failure chỉ fail-open khi rollback savepoint an toàn; C2 theo flag ID tăng rồi C1 cuối, lỗi audit rollback toàn T2 (§6). |
| TRF-05.4 | COMPLETED hoặc FAILED hợp lệ cập nhật terminal, response/idempotency, reservation, outbox theo policy và audit atomic; response chỉ sau commit. Phân biệt rollback đã xác nhận với commit chưa rõ (§7). |
| TRF-05.5 | Contract/failure/concurrency tests theo §8, bằng chứng provider thật và PostgreSQL nhiều connection, review owner và QA; các mục thiếu ghi Blocked. |

AC gốc và trạng thái chưa hoàn tất được giữ trong [backlog](README.md). Kết quả test giả lập chỉ chứng minh nhánh consumer, không chứng minh locking, transaction propagation hoặc an toàn vận hành.

## 4. Ranh giới transaction, claim và thứ tự lock

1. T2 chạy trên primary, `READ COMMITTED`, transaction riêng ngắn; khi recovery gọi executor thì executor **tham gia transaction xử lý hiện hữu**, không commit riêng (`MANDATORY`); caller chỉ công bố kết quả sau commit. Transaction claim của recovery phải commit bền trước transaction xử lý theo DEC-TRF-014. Trường hợp T2 trực tiếp cũng phải có claim/fence hợp lệ theo protocol đã được chứng minh.
2. Plain-read context bất biến để xác định customer, không lock; lấy guard CUSTOMER → lock/re-read transfer và attempt fence, xác minh context, PENDING, epoch **và** token/claim hợp lệ → Account lock hai account ID tăng dần → usage CUSTOMER rồi ACCOUNT. Lock FK/unique, challenge, config, purge và trigger phải tương thích global order; kiểm chứng trên PostgreSQL, không giả định order loại bỏ mọi deadlock.
3. Sau account locks và **trước usage locks**, lấy đúng một `terminal_at` từ primary `clock_timestamp()`, lưu UTC, suy business date theo timezone cấu hình `Asia/Ho_Chi_Minh`; dùng cùng mốc cho terminal/completed_at, settlement, audit C1 và response theo contract. Nếu `terminal_at < created_at`, không commit terminal; alert và xử lý đồng hồ. Đây là mốc logic, không phải thời điểm commit; commit qua nửa đêm vẫn dùng ngày của mốc đã chụp.
4. Sau business writes, terminal/idempotency và outbox, flush và kiểm tra mọi SQL/trigger business trước khi lấy audit chain lock. Ghi audit cuối theo §6 rồi commit; không business SQL, deferred ORM flush/trigger business hoặc network sau audit lock. Rà deferred constraints, không coi `flush` đơn lẻ là proof.
5. Baseline `lock_timeout` 2 giây, `statement_timeout` 5 giây; deadlock/lock timeout rollback và retry có giới hạn 2–3 lần với backoff + jitter trên **toàn transaction mới**, chỉ khi rollback đã xác nhận và fence/protocol cho phép. Không retry một statement trong transaction đã lỗi; unknown commit đi theo §7.

Claim metadata có trong migration candidate không đồng nghĩa durable fencing đã hoàn chỉnh. Trước mọi money/terminal write phải giữ lock, xác minh epoch/token; worker stale hoặc lease hết hạn không tự được ghi. Hết lease không chứng minh transaction cũ rollback; thiếu fence/evidence thì giữ trạng thái thực tế, alert/manual.

## 5. Luồng business và invariant tiền

1. Dưới account locks, xác minh source thuộc customer, hai ID khác nhau, cả hai tồn tại/ACTIVE, currency đúng và điều kiện balance còn hợp lệ. Phân biệt business rejection có kiểu với lỗi DB/system; kết quả preflight không thay revalidation. Lỗi amount/account/thiếu tiền đã phát hiện **trước T1** không tạo transfer; thay đổi phát hiện **sau T1** đi nhánh FAILED atomic (§7).
2. Limit đọc config CUSTOMER và ACCOUNT ACTIVE có hiệu lực trong **cùng một statement snapshot** trên primary, lưu ID/version và giá trị dùng; check per-transaction và daily, áp dụng scope chặt hơn. CUSTOMER thiếu/trùng config hợp lệ hoặc lỗi đọc → fail-closed; ACCOUNT chỉ optional nếu policy quy định rõ. Config đổi sau snapshot không thay quyết định T2 hiện tại. Lock usage CUSTOMER rồi ACCOUNT theo ngày ở §4. `LIMIT_EXCEEDED` là business result trước money.
3. Evaluate Fraud theo §6 trước money. Nếu ALLOW, Account thực hiện debit conditional cho source ACTIVE với available balance đủ, kiểm affected rows, sau đó credit destination. Dùng decimal/currency chính xác, không `double`; cả available và ledger balance theo mô hình Account. Không commit debit một phía.
4. Ledger `postDoubleEntry` trong cùng T2 nhận `balanceAfter` từ Account: đúng seq 1 DEBIT source và seq 2 CREDIT destination, cùng transfer, amount, currency. Trước audit, Ledger xác minh đồng bộ đúng hai entry, linkage, phía, số tiền, currency và balanceAfter; thiếu/thừa/trùng/lệch là invariant error rollback. Không thay kiểm tra này bằng reconciliation hay deferred constraint trigger chưa được review.
5. Limit `settleOrReleaseReservation(COMPLETED)` atomically settle reservation và tăng usage đúng một lần cho mỗi scope áp dụng. **Không** gọi thêm `incrementUsage` cho cùng transfer/scope. Reservation thiếu/lệch là invariant error; PENDING không release theo TTL.
6. Persist COMPLETED, response masked để replay và idempotency terminal cùng transaction; đọc snapshot terminal TTL/version hợp lệ ≥24 giờ, fail-closed nếu thiếu/sai, lưu `expires_at = max(expiry hiện tại, completed_at + TTL_terminal)` (DEC-TRF-005/013). Enqueue outcome theo policy và audit §6. Chỉ trả thành công sau commit; terminal không được ghi đè.

## 6. Fraud, audit và outbox

- `evaluateTransfer` JOIN T2 sau account/limit locks, trả ALLOW/BLOCK/ENGINE_ERROR và flags; mỗi flag có ID, rule code, `ruleVersion` số nguyên dương, severity. BLOCK theo policy là business FAILED trước money, không ledger/usage. ALLOW có thể có flag.
- Rule DEC-TRF-009 đếm transfer duy nhất của cùng customer có T1 đã commit, mọi trạng thái, `created_at` thuộc `(terminal_at − 600 giây, terminal_at]`; **>5** tạo flag, bản thân rule không auto-block. Retry/replay/428 không tạo intent nên không tăng count; không cộng transfer hiện tại hai lần. Lưu rule/config version, count và window khi có flag.
- Chỉ chuyển lỗi engine thành fail-open sau rollback savepoint phần fraud và xác nhận cả transaction lẫn persistence context còn usable, không rollback-only; bỏ descriptor của flag đã rollback, ghi telemetry/C4 theo policy. Không bắt invariant tiền hay lỗi audit vào nhánh fail-open. Nếu không chứng minh usable, rollback T2.
- Gom descriptor allowlist của flag thực sự còn tồn tại. Sau business flush và trước commit, Audit `recordInTransaction` JOIN/MANDATORY ghi `FRAUD_FLAG_CREATED` C2 lần lượt theo flag ID tăng, sau đó C1 `TRANSFER_COMPLETED` hoặc `TRANSFER_FAILED`. Lỗi bất kỳ C2/C1 rollback toàn T2, kể cả event trước đó; log không thay audit. Không gửi PII, raw key, secret hay số tài khoản đầy đủ.
- Notification enqueue masked payload theo outcome/template/channel đã chốt, unique theo transfer/customer/type/channel; insert lỗi rollback T2. Worker gửi sau commit ngoài transaction; delivery at-least-once, lỗi gửi không đảo tiền (DEC-TRF-011). Không hứa exactly-once hoặc thực hiện gateway call trong T2.

## 7. Thất bại, replay và recovery boundary

| Trường hợp | Xử lý / oracle |
|---|---|
| Revalidation, limit hoặc fraud BLOCK sau T1 | Không debit/credit/ledger/usage; release reservation FAILED, lưu status + response `422`/idempotency + audit C1 và outbox theo policy trong cùng transaction. Audit/outbox lỗi rollback nhánh này. |
| Credit lỗi sau debit | Rollback mọi money/ledger về savepoint; chỉ hoàn tất FAILED trong **cùng T2** nếu xác minh không sót hiệu ứng tiền và transaction + persistence context usable. Nếu T2 đã abort, Tfail riêng chỉ khi rollback được xác nhận, có durable fence ngăn worker chạy lại và evidence per-transfer đủ; claim/re-read PENDING và atomically release reservation, FAILED, response, audit/outbox theo policy. Không suy đoán rollback từ exception/lease. |
| Technical error, invariant, audit/outbox lỗi | Rollback toàn transaction; nếu rollback được xác nhận, transfer/idempotency vẫn PENDING/RECEIVED từ T1 và không có side effect T2. Không chuyển lỗi kỹ thuật thành business failure giả. |
| Crash, connection loss hoặc commit outcome chưa rõ | Đọc primary, claim/re-read theo global order và protocol; nếu terminal đã commit thì giữ/replay, không post tiền lại. Nếu chưa xác minh được outcome/fence/evidence, giữ trạng thái thực tế, alert/manual; không khẳng định PENDING hay FAILED. |
| HTTP retry | Cùng actor/key/hash nonterminal → `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`, HTTP không tự resume T2; terminal replay status/body gốc + `Idempotent-Replayed: true` theo DEC-TRF-004. Recovery của 06 chỉ resume khi đủ bằng chứng an toàn. |

Tfail và reconciliation cần phối hợp BE-TRF-06: không tự hoàn tất PENDING có ledger chỉ dựa vào số dư hiện tại hoặc tổng usage. Evidence per-transfer Account/Ledger/Limit/Audit, response và mode phải đủ theo DEC-TRF-006; thiếu/lệch hoặc unknown thì alert/manual, không re-post hay sửa ledger.

## 8. Kế hoạch kiểm thử và evidence

| Nhóm | Thiết lập và oracle tối thiểu |
|---|---|
| Contract consumer | Fake test/dev theo `05` §13.4: typed business vs technical errors, mọi input/output và propagation; ruleVersion INT > 0; reservation thiếu/lệch; không thêm `incrementUsage`; audit/outbox failure propagate. Không dùng fake làm proof DB/provider. |
| Success / rollback | PostgreSQL với migration và owner thật: debit/credit, đúng hai entry + balanceAfter, usage/reservation, terminal/response/outbox/C2/C1 cùng commit; inject lỗi tại từng bước, assert toàn bộ balance, ledger, usage, reservation, transfer, idempotency, audit và outbox sau rollback. Kiểm tra không có `REQUIRES_NEW` ẩn trong business path. |
| Business rejection | Amount/account/balance trước T1 không có intent; account status/ownership/balance đổi sau T1, limit exceeded và fraud BLOCK → FAILED/422 atomic, reservation release, không tiền/ledger/usage. Đồng thời xác nhận stale preflight không cho phép vượt limit. |
| Concurrency `T-CON-01–04`, `T-LIM-01–03` | Nhiều connection có barrier: cùng nguồn chi vượt balance, A→B/B→A, cùng customer nhiều account vượt daily limit, same transfer/token và stale worker. Assert không âm/double debit/usage kép; bounded deadlock/timeout retry trên transaction mới, không giả định deadlock không xảy ra. |
| Ngày / config | Test `terminal_at` hai phía nửa đêm, commit qua ngày, đồng hồ lùi, reservation PENDING ngày trước, config đổi đồng thời, thiếu/trùng config và snapshot hai scope; TTL terminal thay đổi, expiry không giảm và config invalid rollback. |
| Fraud / audit | 5 so với 6 unique T1; hai biên window và mọi status; retry/428 không tăng; rule/version snapshot. Inject fraud lỗi sau partial writes: savepoint rollback và context usable hoặc rollback T2; lỗi C2 giữa chuỗi/C1 sau C2 rollback mọi business. Xác minh ordering, deferred SQL/trigger và chain cạnh tranh. |
| Credit / fencing / recovery `T-REC-01–04`, `T-AUD-06` | Lỗi sau debit trước credit; savepoint usable và unusable, abort/Tfail, stale epoch/token, hai worker, crash trước/sau commit và unknown commit. Không debit đơn lẻ hoặc terminal giả; thiếu evidence → alert/manual. Recovery executor JOIN transaction, inner failure làm rollback caller. |
| API / outbox | `CT-API-011` 422 không ledger; replay 201/422, 409 + Retry-After và correlation theo `07`; outbox insert failure rollback, delivery fail sau commit không đổi tiền, retry/dedupe/masking theo `T-OUT-01/02`. |
| Provider integration / release | Chạy cùng contract vectors trên provider thật và PostgreSQL nhiều connection, kiểm transaction propagation, migration và security; BE-TRF-08/09 chạy D1–D5 và review liên module. |

Mỗi evidence theo `09` §10: test ID và phạm vi, revision, lệnh, môi trường, PostgreSQL/migration/seed, kết quả, artifact synthetic không PII/secret, người chạy và reviewer. Ghi rõ fake, fixture hoặc provider thật; thiếu môi trường/provider/review là **Blocked**, không gọi candidate test là end-to-end hay release evidence.

## 9. Phối hợp và điều kiện đóng

| Vấn đề | Owner / việc cần hoàn tất |
|---|---|
| Provider APIs và JOIN T2 | Account, Limit, Ledger, Fraud, Audit, Notification: review DTO v1, error/propagation và contract suite; provider thật, migration và rollback proof. |
| Savepoint / persistence context | Fraud, Account, Transfer, DBA: chứng minh rollback phần fraud và credit, context usable, failure injection và không deferred write sau audit. |
| Fencing, Tfail và unknown commit | Transfer, [BE-TRF-06](BE-TRF-06.md), Ops/DBA: candidate có durable claim và fixture stale worker/crash; Tfail và evidence provider thật vẫn Blocked. |
| Lock order / snapshot / business date | Limit, Account, DBA, QA: config/version, lock/FK/trigger, midnight, concurrency và clock primary. |
| Audit C2/C1 và outbox | Audit, Fraud, Notification, QA: chain lock cuối, transaction rollback, policy/template, masking và delivery sau commit. |
| Candidate so với baseline | Transfer/Fraud: sửa kiểu `ruleVersion`, triển khai các gap đã nêu và test lại; không kích hoạt candidate như production khi thiếu provider/review. |

**Điều kiện đóng:** toàn bộ AC của [backlog](README.md) được Verified bằng implementation và provider thật, migration production, PostgreSQL nhiều connection/failure injection, transaction propagation và security phù hợp; evidence tái lập và owner/QA review. Các rủi ro release, recovery, D1–D5 tiếp tục theo BE-TRF-06–09; tài liệu hoặc test fake không thay các gate đó.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 17/11/2026 | Chi tiết phạm vi T2, contract, lock order, money/failure, test oracle, candidate và điều kiện đóng; chưa triển khai hoặc nghiệm thu. |
