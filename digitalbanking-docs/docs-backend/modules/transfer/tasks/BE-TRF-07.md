# BE-TRF-07 — Security, observability và notification

> **Dự án:** Digital Banking MVP  
> **Ưu tiên / dự kiến:** P0 / W4–W6  
> **Phiên bản / ngày:** v1.1 / 17/11/2026  
> **Trạng thái:** Candidate phía Transfer; nghiệm thu tích hợp Blocked  
> **Owner:** Transfer (Vũ); phối hợp Identity, Account, Audit, Notification, Common, Customer, Ops/DBA, QA và Security

## 1. Mục tiêu và phạm vi

Kết nối bảo mật, context, audit, telemetry và notification cho luồng Transfer thật. Kết quả cần đạt: truy vết được một yêu cầu qua T0–T3/recovery; không lộ secret/PII; audit bắt buộc cùng transaction nghiệp vụ; phát hiện sự cố bằng metric/alert; thông báo terminal được ghi atomically và gửi sau commit, không làm thay đổi kết quả tiền khi delivery lỗi.

Task tích hợp các control của [BE-TRF-03](BE-TRF-03.md), [04](BE-TRF-04.md), [05](BE-TRF-05.md) và [06](BE-TRF-06.md). Notification sở hữu outbox, worker và gateway; Common sở hữu HTTP context/security/telemetry; Transfer cung cấp facts, adapter và điểm đo nghiệp vụ. Kiểm thử nghiệm thu tổng thể thuộc BE-TRF-08/09. Đặc tả này không bật candidate runtime hoặc thay policy chưa được chốt bằng giá trị tự đoán.

## 2. Nguồn, truy vết và hiện trạng

| Nguồn | Nội dung áp dụng |
|---|---|
| [Backlog](README.md), [thiết kế Transfer](../design.md) §§4–7 | Ba nhóm AC của BE-TRF-07; boundary API/T1/T2/T3/recovery. |
| [[digitalbanking-docs/docs-common/03. SRS.md]] §§2.13, 10 | FR-034, FR-038–043; AC-018, AC-022–025, AC-030, AC-033–034; NFR-008, NFR-020, NFR-023, NFR-025. |
| [[digitalbanking-docs/docs-common/07. API contracts.md]] §§2–6, 8.3 | Correlation, session/CSRF, permission/scope, masking, Problem, cache headers và replay. |
| [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §§3–5, 6, 11–13 | Session SPI; TRFC-AU-01/02, TRFC-NO-01, TRFC-CU-02; write-owner, JOIN/AFTER và phương án adapter §11.3.2. |
| [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]]; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §§5, 10, 13; [[digitalbanking-docs/docs-common/design/04. security.md]] | C1/C2 fail-closed, C3 fallback, audit cuối transaction, outbox/lease và bảo vệ dữ liệu. |
| [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] §§9–10; [deployment](../../../deployment/README.md) §7; [configuration](../../../deployment/configuration.md) | Structured logs, metrics, alert, runbook và cấu hình vận hành. Ngưỡng Proposed/Open giữ nguyên nhãn. |
| [[digitalbanking-docs/docs-common/09. Test plan.md]] §§7–10 | T-AUD-06/07, T-OUT-01/02, D3/D4/D5 và evidence. |
| [DECISIONS.md](../../../../../DECISIONS.md) DEC-TRF-004, 007, 010–012, 014–018 | Replay correlation; audit order; notification lease/retry/dedupe/contact resolution; quyền và quota; recovery. |
| [BE-NOT-01](../../notification/BE-NOT-01-transactional-outbox.md), [Common current state](../../common/CURRENT-STATE.md) | Component/provider đã có, proposal và giới hạn bằng chứng tích hợp. |

**Hiện trạng quan sát ngày 17/11/2026, chưa chạy lại test trong task viết tài liệu này:**

- Common có `CorrelationAndRequestIdFilter`, `RequestContext`/`RequestContextHolder`, HTTP logging/metrics và session SPI. `RequestContext` kiểm correlation bằng `[A-Za-z0-9._:-]{1,64}`, request ID UUID do server sinh; filter từ chối header sai/trùng bằng `400 VALIDATION_ERROR`. Các primitive này cần được kiểm chứng cùng Transfer và Identity thật.
- Transfer có boundary HTTP candidate, `SecurityDenyAudit` và recovery candidate. C3 fallback hiện có log/counter khi port ném exception; cần kiểm thêm kết quả empty của `Optional` và đường alert. Chưa coi đó là tích hợp Audit bền vững hoặc telemetry recovery hoàn chỉnh.
- Notification có `NotificationOutboxPort.enqueueTransferNotificationsInT2(List<TransferNotice>)`, service `MANDATORY`, worker và component metrics. `TransferNotice`/channel còn ở `notification.domain`, khác boundary chỉ import `provider.api`; contract nguồn dùng `enqueueTransferOutcome → NotificationRef`, còn API candidate trả `void`. Cần hai owner review/mapping, không import domain để ghép tạm. Provider migration, recipient resolver, gateway và duplicate semantics vẫn cần evidence thật.
- DEC-TRF-011 đã chọn baseline notification. Những phần BE-NOT-01 cũ còn gọi lease/retry/dedupe là proposal phải được owner đồng bộ khi triển khai; lựa chọn thiết kế không thay migration, integration test hay sign-off.

### Candidate phía Transfer

- HTTP tái sử dụng Common cho session, CSRF, correlation và Problem. Service POST yêu cầu role CUSTOMER và permission. GET staff cần role/permission hợp lệ và `TransferStaffScope` cho phép trước khi đọc fraud flags. Adapter scope thật và quota atomic toàn cụm vẫn Blocked.
- Preflight kiểm correlation của command T1 với actor hiện tại. T2 trực tiếp dùng correlation đã lưu và request ID của lần thực thi. Recovery discovery đọc correlation bền; scope worker cho từng bản ghi dùng request ID mới, bỏ IP/User-Agent và phục hồi Common context/MDC khi kết thúc. Quyền SYSTEM vẫn do port recovery kiểm tra. Correlation cũ sai định dạng được báo `CONTEXT_INVALID` và bỏ qua để điều tra.
- `SecurityDenyAudit` fallback cả khi Audit trả empty hoặc ném lỗi. Common encoder giữ action/outcome, actor hash và context hợp lệ cho sự kiện fallback. C1/C2 vẫn fail-closed; lỗi ghi audit T2 ánh xạ `500 AUDIT_WRITE_FAILED`. `TransferTelemetry` ghi tín hiệu lỗi dù business transaction rollback. Counter terminal ghi sau commit T2, gồm lần resume có T2 thật, và tách với reconciliation. `TransferPendingMetrics` refresh từ primary trong transaction read-only có timeout, gồm MANUAL và công bố freshness/availability.
- `TransferSettlementCandidate.NotificationPorts` nối `TransferNotifications` vào pha terminal, trước flush và audit. Candidate có route policy khai báo qua port, kiểm recipient thuộc hai bên, gộp route trùng customer/channel cho một loại outcome và từ chối cấu hình template mâu thuẫn. Batch immutable chỉ mang recipient được chọn, facts đã mask, outcome và correlation. Fixture PostgreSQL dùng outbox synthetic cùng transaction để kiểm chứng rollback. Tfail, public DTO/adapter của Notification và policy thật vẫn Blocked.
- [Dashboard và rules candidate](ops/README.md) dùng bộ tên Micrometer hiện có và nêu các signal còn chờ owner. [Evidence](evidence/BE-TRF-07-candidate.md) ghi phạm vi kiểm thử và giới hạn.

## 3. Đầu việc và deliverable

| ID | Kết quả cần bàn giao |
|---|---|
| TRF-07.1 | Matrix context cho HTTP/T0/T1/T2/T3/recovery/replay; adapter dùng Common, bind/cleanup context qua thread/job. |
| TRF-07.2 | Security integration và allowlist dữ liệu; C1/C2 JOIN, C3 fallback/alert; test quyền/IDOR/CSRF, leak và injection. |
| TRF-07.3 | Business meters, dashboard và alert rules với bounded labels; định nghĩa attempt/commit, freshness và owner. |
| TRF-07.4 | Mapping facts Transfer → Notification public API, outbox atomic và delivery sau commit; recipient/template/channel/dedupe matrix được review. |
| TRF-07.5 | Consumer contract suite, PostgreSQL/provider integration, failure/restart/multi-worker tests và evidence; runbook drill với Ops. |

## 4. Context và correlation xuyên luồng

| Ranh giới | Quy tắc và oracle |
|---|---|
| HTTP vào | Dùng Common filter; thiếu correlation thì sinh mới, hợp lệ thì dùng lại, sai/trùng thì 400 với correlation an toàn do server sinh. Không đưa raw header lỗi vào log/Problem. Actor/customer/permission lấy từ principal/session tin cậy, không lấy từ body/header tự khai. |
| T0 challenge | Truyền context đã chọn vào Identity. Challenge lưu correlation lúc tạo; các request verify/resend có request ID riêng. Retry có correlation mới liên kết bằng challenge reference/hash binding nội bộ, không sửa correlation lịch sử và không dùng correlation làm khóa idempotency. |
| T1/T2 | Persist correlation của intent và chuyển facts đó sang Ledger, Audit, Notification qua DTO provider. Request ID biểu thị lần thực thi hiện tại; tách khỏi correlation bền của intent. Không ghi đè correlation của intent khi retry/recovery. C1/C2 mang actor và linkage đúng event; C3 gắn context của request bị từ chối. |
| HTTP replay | Theo DEC-TRF-004: body/status gốc được giữ nguyên, gồm correlation gốc; `X-Correlation-ID` là correlation request hiện tại; thêm `Idempotent-Replayed: true`. Không tạo ledger/audit completion/outbox mới. Log replay có thể liên kết hai correlation đã validate, không chứa raw key/hash/body. |
| T3 / recovery | Persist/copy context tối thiểu trước async; worker dùng SYSTEM actor và ID thực thi mới, correlation gốc của event/transfer. Không copy session/CSRF/credential hay toàn bộ principal/MDC. Bind context trong scope và cleanup trong `finally`, cả nhánh exception. Đọc record kế tiếp không được thấy MDC/actor của record trước. |

Correlation/request ID là dữ liệu truy vết, không là bằng chứng quyền, tiền đã commit hay gateway đã gửi. Nếu provider cần field chưa có chỗ lưu, hai owner bổ sung DTO/schema có review; không tự giả định `request_id` hoặc trace column đã tồn tại. Distributed tracing nếu được bật dùng cùng allowlist; không tạo dependency tracing mới để hoàn thành AC correlation.

## 5. Security và audit integration

### 5.1. Authorization và bảo vệ dữ liệu

- Kiểm chứng session thật, hết hạn/revoke, CSRF và service permission của BE-TRF-03. POST chỉ CUSTOMER có `TRANSFER_CREATE` và sở hữu nguồn; GET CUSTOMER có `TRANSACTION_READ_SELF` và sở hữu source hoặc destination; staff có `TRANSACTION_READ` và scope server-side. Fraud flags chỉ theo `FRAUD_READ`/`FRAUD_REVIEW`. Test cả IDOR, actor giả và retry khác actor; lookup idempotency vẫn tuân thứ tự BE-TRF-04 sau authentication/permission boundary.
- Dùng quota theo DEC-TRF-012: POST 30 request/phút/customer, shared atomic khi nhiều instance; 429 có `Retry-After`, quota service lỗi fail-closed 503. Limiter in-process không đủ evidence toàn cụm. Không dùng telemetry failure để bypass quota/security.
- Response tài chính và lỗi liên quan có `Cache-Control: no-store`, `Pragma: no-cache`; Problem chỉ field catalog, không SQL/stack/provider response. Receipt/response đã mask trước khi lưu replay. Không serialize entity hoặc dùng `toString()` command/request làm log.
- Logs/errors/audit/outbox/traces/test artifact loại password, PIN, OTP, cookie/session/access token, CSRF token/hash, raw key/request hash, full account/phone/email và raw body/description. Outbox chỉ facts allowlist cần template; ID recipient nội bộ ở metadata được kiểm soát, destination thực không persist. Audit dùng allowlist riêng đúng action, không copy technical log hoặc state nguyên khối.
- Input tự do, User-Agent, header, lỗi provider và description phải qua validation/structured encoding; không nội suy CR/LF/control characters thành dòng log hoặc field tự khai. Test bằng canary synthetic cả success, deny, replay, rollback, timeout và worker. Nếu policy phát hiện PII trong description/key chưa chốt thì AC tương ứng **Blocked**, không tự thêm regex như policy production.

### 5.2. Audit và đường lỗi

| Event / tình huống | Transaction / xử lý |
|---|---|
| `TRANSFER_CREATED` | C1 JOIN/MANDATORY T1; lỗi rollback transfer/key/reservation/challenge consume của T1. |
| `FRAUD_FLAG_CREATED` rồi `TRANSFER_COMPLETED/FAILED` | C2 flag ID tăng rồi C1 ở pha audit cuối T2, sau toàn bộ business flush/outbox/status/response. Bất kỳ C2/C1 lỗi rollback toàn T2; lỗi audit không thuộc fraud fail-open. Không business SQL/ORM deferred flush/network sau audit lock. |
| `TRANSFER_RECONCILED` | C1 SYSTEM JOIN transaction recovery sau business writes; fail-closed theo BE-TRF-06, không re-post tiền. |
| `SECURITY_ACCESS_DENIED`, `SECURITY_IDEMPOTENCY_CONFLICT` | C3 standalone qua TRFC-AU-02, best-effort. Không có audit ref hoặc có exception → security log allowlist + bounded fallback counter + alert; response deny/conflict giữ nguyên, không lộ chủ key hoặc dữ liệu resource khác. |
| T1/T2 lỗi hoặc commit chưa rõ | Log/metric lỗi có mã an toàn; nếu rollback được xác nhận thì giữ kết quả theo BE-TRF-05/06. Unknown commit phải xác minh primary; không suy COMPLETED/FAILED hoặc retry tiền từ metric/exception. |

Audit C1/C2 là dữ liệu bắt buộc; lỗi instrumentation/logging thông thường không được đổi quyết định nghiệp vụ hoặc tạo response lỗi sau commit. Đường cảnh báo audit phải vẫn quan sát được sau rollback, không phụ thuộc một callback chỉ chạy khi commit. Không gọi network alert dưới money/audit lock; dùng cơ chế giám sát/log/metric ngoài transaction nghiệp vụ.

## 6. Metrics, dashboard và alerts

### 6.1. Quy tắc đo

Business counter phân biệt **attempt** với **commit**: T1 created chỉ đếm sau commit T1; COMPLETED/FAILED chỉ sau commit terminal, không đếm HTTP replay, T2 rollback hoặc reconciliation như debit mới. Duration của T1/T2 và retry/error đo từng attempt với outcome kỹ thuật riêng. Counter process có thể mất giữa DB commit và callback/crash; trạng thái DB/projection owner là nguồn đối chiếu, metric không chứng minh exactly-once hay invariant. Không scan DB nặng hoặc gọi provider trong scrape request.

Gauge pending/backlog lấy snapshot có giới hạn từ primary qua projection/API của write-owner, refresh theo lịch và publish tuổi dữ liệu/lỗi collector. Tính oldest pending bằng clock primary; tập rỗng có age/count 0, lỗi đọc giữ dấu stale/unavailable, không biến lỗi thành backlog 0. Không cộng oldest-age của các instance; dashboard tổng hợp max theo môi trường. Không cộng nhiều snapshot toàn DB như các partition độc lập.

Tên dưới đây là **baseline thiết kế** từ `design/02` §13 và Transfer design §6. Cột mapping thực tế của candidate nằm ở [dashboard](ops/README.md). Common/Ops chốt Micrometer → tên scrape, histogram buckets và recording rules; tái sử dụng HTTP/pool/provider meters, tránh đếm cùng signal hai lần.

| Signal / tên tham chiếu | Loại / label allowlist | Điểm đo / owner |
|---|---|---|
| `banking_transfer_total` | Counter; `status=CREATED/COMPLETED/FAILED`, `failure_code=NONE/<catalog hữu hạn>/OTHER` | Sau commit, Transfer. Nếu dùng các counter `transfer_created/completed/failed_total` tương đương thì dashboard chọn một bộ. |
| `transfer_pending_count`, `transfer_pending_age_seconds` | Gauge; không label định danh | Snapshot primary gồm cả MANUAL vẫn PENDING; collector Transfer. |
| `transfer_t1_duration_seconds`, `transfer_t2_duration_seconds`, HTTP duration | Timer/histogram; `outcome` tập cố định, HTTP route template | T1/T2 Transfer; HTTP Common. Tách latency request/replay khỏi latency money attempt. |
| Idempotency new/replay/conflict/in-progress | Counter; `outcome` hữu hạn, `reason` allowlist | Transfer; một quyết định/request. Không tag key, fingerprint hoặc raw hash. |
| `transfer_lock_wait_seconds`, `transfer_retry_total`, deadlock/timeout | Timer/counter; `lock_type=CUSTOMER_GUARD/TRANSFER/ACCOUNT/USAGE/AUDIT`, reason allowlist | Vị trí lock thực tế hoặc provider instrumentation; không suy thời gian lock từ toàn bộ T2. |
| Audit latency/failure, C3 fallback | Timer/counter; `class=C1/C2/C3`, action/outcome allowlist | Audit/Common sở hữu write signal; Transfer bổ sung fallback/propagation gap khi cần. Kiểm mapping counter hiện có `transfer.security.deny.fallback`. |
| Recovery/purge result, backlog/manual, job heartbeat | Counter/gauge/timer; result/reason hữu hạn | BE-TRF-06; outcome phản ánh transaction đã hoàn tất, unknown/manual tách riêng, không dùng lease token làm tag. |
| Notification backlog/oldest age/retry/delivery/stale claim | Gauge/counter/timer; status/channel/result hữu hạn | Notification qua metrics/projection của owner; reuse `banking.notification.outbox.claimed/delivery`, bổ sung signal còn thiếu cùng owner. |

Không dùng user/customer/account/transfer/notification ID, reference, correlation/request ID, key/hash/fingerprint, destination, exception message, URL động hoặc rule version không giới hạn làm label. Unknown code map `OTHER`; kiểm số series không tăng theo số transfer. Endpoint metrics/dashboard chỉ cho mạng/quyền vận hành đã cấu hình, không chứa payload/secret.

### 6.2. Alert và runbook

Mỗi rule cần severity, environment, ngưỡng/window, owner trực, runbook link và test fire/resolve. Các ngưỡng dưới đây từ `08` §9.4 và `design/02` §13.3 còn **Proposed**, cần Ops xác nhận trước activation; không tự đổi thành SLA. Khi nguồn khác ngưỡng, dùng nguồn cấp trên và ghi chênh lệch cho Ops.

| Alert | Trigger / trạng thái | Owner và xử lý |
|---|---|---|
| Required audit failure / C3 fallback | C1/C2 thất bại: High theo `08`; C3 fallback phát hiện bằng counter/log, window/severity cụ thể chờ Security/Ops | Audit + Transfer + Ops; `08` R-AUD-01/R-CON-05. Không bypass audit; giữ evidence và xác minh transaction. |
| PENDING quá tuổi / recovery MANUAL | Oldest age >300s (Proposed); manual/unknown/fence unsafe phải được phát hiện theo BE-TRF-06, không chờ retry hết | Transfer + Ops; R-CON-01/R-CON-02 và BE-TRF-06 §10. Không sửa ledger/terminal từ alert. |
| Deadlock / lock wait | >5 deadlock/phút hoặc p95 lock wait >500ms trong 5 phút (Proposed) | Transfer + DBA + owner lock; R-CON-03, kiểm lock order và retry bounded. |
| Notification backlog / exhausted | Backlog >100 hoặc oldest >600s (Proposed); retry exhausted/permanent failure có signal riêng | Notification + Ops; R-CON-06, kiểm lease/provider/resolver, không rollback money. |
| Collector/job stale | Không có snapshot/heartbeat mới; threshold theo interval triển khai, chờ Ops chốt | Owner collector/job + Ops; điều tra scrape/DB/scheduler, không coi dữ liệu thiếu là khỏe. |

Dashboard liên kết HTTP p95/p99/error, terminal outcomes, pending/manual age, idempotency, T1/T2 duration, lock/retry, audit, notification và DB/pool. D5 giữ mục tiêu DEC-TRF-010/`09`: 20 RPS ×10 phút, p95 ≤2s, p99 ≤5s, lỗi <1% cùng invariant; task này chuẩn bị instrumentation, kết quả load thuộc BE-TRF-08/09.

## 7. Notification: facts, atomicity và delivery

### 7.1. Mapping Transfer → outbox

TRFC-NO-01 và phương án adapter ở Modules contract §11.3.2 là baseline phối hợp. Transfer chỉ truyền intent/terminal facts immutable: transfer ID/reference, recipient customer ID, outcome/failure code an toàn, amount/currency, masked account refs, template/channel và correlation. Notification sở hữu DTO validation, payload allowlist, insert và delivery state. Không truyền entity, description tự do, raw destination hoặc secret; không gọi gateway/Customer contact resolver trong T2.

Trước integration phải có matrix được Transfer/Notification/BA/Security review cho: COMPLETED và FAILED sau T1, sender/receiver của từng loại own-account/intra-bank, account cùng customer, channel/template và consent. Deduplicate recipient cùng customer/type/channel; không tự gửi outcome thất bại cho bên nhận nếu policy chưa cho phép. Reject trước T1/428, replay và lỗi kỹ thuật rollback không phải terminal event mới. Recipient/template policy thiếu: test độc lập bằng fixture có policy khai báo, AC production tương ứng **Blocked**.

1. Trong T2 hoặc Tfail an toàn, tạo outbox theo terminal transition cùng status/idempotency/response/settlement. Flush toàn bộ business/outbox trước audit cuối. Enqueue/validation/persist lỗi phải lan ra để rollback T2; không nuốt lỗi thành notification best-effort khi event chưa bền.
2. Unique baseline DEC-TRF-011: `(transfer, customer, notification_type, channel)`; constraint/migration do Notification/DB owner cung cấp. Unique collision phải kiểm cùng facts, không `ON CONFLICT DO NOTHING` mù. HTTP replay không enqueue. Recovery chỉ đảm bảo event nếu protocol và cùng facts đã được chứng minh; mismatch/không có proof → incident, không tạo event đoán.
3. T2 rollback không có event mới và không gửi ra ngoài. Sau T2 commit, delivery không được phụ thuộc HTTP response hoặc in-memory callback duy nhất; worker đọc outbox bền. Terminal thất bại và COMPLETED đều theo matrix policy; lỗi T3 không đổi tiền, usage, ledger hoặc response replay.

### 7.2. T3 và giới hạn guarantee

Theo DEC-TRF-011: poll 2 giây, batch 50; transaction claim ngắn `FOR UPDATE SKIP LOCKED`, lease 30 giây, token/owner bền; commit claim trước gateway. Resolve destination qua TRFC-CU-02 với SYSTEM/purpose `TRANSFER_OUTCOME` sau commit, ngay trước gửi; phân biệt Unavailable với DB error và không persist/cache/log destination. Thiếu resolver/provider thật không thay bằng masked phone/email để gửi.

Gateway ngoài transaction/row lock; persist ACK trong transaction riêng bằng CAS đúng token/owner để stale worker không ghi đè claim mới. Retry tối đa 5 lần, backoff có jitter, hết giới hạn → FAILED/alert; Ops replay cần quyền và audit. Lịch backoff cụ thể, nghĩa `retry_count` (attempt hay retry), timeout so với lease, permanent failure, policy contact/consent đổi và thao tác replay vẫn cần Notification/Ops/Security chốt. Không dùng lịch recovery Transfer 30/60/120/240s làm lịch notification mặc định.

Delivery at-least-once; notification ID opaque ổn định là provider idempotency key nếu provider hỗ trợ. Crash sau send trước lưu ACK có thể gửi trùng; provider dedupe window phải được đo/test và ghi giới hạn, không tuyên bố exactly-once. Lease expiry chỉ cho reclaim theo protocol Notification, không chứng minh gateway cũ chưa gửi. Không thêm PROCESSING/business status hay lease column chỉ vì pseudocode; schema/migration owner phải khớp `04` và quyết định đã ghi.

OTP thuộc Identity/T0 theo DEC-TRF-011 và Modules contract §11.3.1: plaintext chỉ tạm thời trong memory, không qua persistent outcome outbox; dispatch/generation/expiry không do Transfer worker quyết định.

## 8. Kiểm thử và bằng chứng

| Nhóm / ID local | Thiết lập và oracle tối thiểu |
|---|---|
| `TRF07-CTX-01` / AC-022 | HTTP thiếu/có/sai/trùng correlation; trace qua challenge/intent/ledger/audit/outbox/response. Verify/resend/retry với ID mới vẫn link đúng lịch sử; 201/422 replay giữ body gốc/header hiện tại và không enqueue/complete lại. |
| `TRF07-CTX-02` | Hai job/request dùng cùng thread pool với success/exception; capture/bind context tối thiểu, SYSTEM worker và request ID mới; không MDC/actor leak qua record tiếp theo. |
| `TRF07-SEC-01` / AC-024, AC-030, AC-033 | Session expiry/revoke, CSRF, permission/role/ownership, GET staff scope/fraud flags, actor giả và conflict khác customer; deny không có money side effect, C3 đúng; shared quota race nhiều instance. |
| `TRF07-PII-01` / AC-023 | Canary synthetic secret/full PII/raw key, CR/LF/control chars trong header/body/description/User-Agent/provider exception; quét HTTP, persisted response, captured logs/traces, audit/outbox và artifact ở cả lỗi/worker/replay. Kiểm masking và bounded field size, không chỉ happy path. |
| `TRF07-AUD-01` / T-AUD-06/07, AC-034 | PostgreSQL/provider thật: fail CREATED, từng C2 và C1, outbox flush, concurrent audit chain; rollback đúng T1/T2 và không business SQL sau audit lock. C3 exception **và empty** đều fallback/log/metric/alert nhưng deny giữ nguyên. Unknown commit xử lý như BE-TRF-06. |
| `TRF07-MET-01` | Registry test + scrape thật: commit vs rollback/replay/recovery, timer/label/histogram, failed collector/staleness, hai instance snapshot, empty pending; nhiều ID không làm series tăng. Metric/log failure không thay outcome/audit policy. |
| `TRF07-OPS-01` | Inject audit/PENDING/manual/deadlock/backlog/stale collector; rule fire và resolve đúng, route đến owner và runbook, không PII trong alert. Xác nhận dashboard/metrics access; lưu drill evidence. |
| `TRF07-OUT-01` / AC-025 | T2 terminal/outbox/audit/response cùng commit; enqueue lỗi và audit lỗi rollback toàn bộ. Replay 201/422 không event mới; own-account recipient không trùng, channel/template/policy đúng, duplicate facts mismatch không bị nuốt. |
| `TRF07-OUT-02` / T-OUT-01/02 | PostgreSQL nhiều connection/worker: crash trước/sau claim commit, trước gửi, sau send trước ACK, stale ACK, lease reclaim; gateway timeout/permanent/DB update failure, retry/backoff/exhaustion/restart. Không mất event bền hoặc đổi transfer; duplicate window được ghi, provider key ổn định. |
| `TRF07-CON-01` | Consumer fake đúng Modules contract cho Audit/Notification/Customer/Identity còn thiếu; field/nullability/outcome/exception/masking mapping và no network trong T2. Rerun cùng vectors với provider thật; ArchUnit cấm import private/domain provider và cycle. |

ID `TRF07-*` là vector cục bộ của task, không thay test ID chung. Khi thiếu provider, chỉ mock trong test/dev theo Modules contract; mock không chứng minh JOIN/rollback, lock/lease, gateway dedupe hay quota toàn cụm. Bằng chứng invariant cần PostgreSQL thật, migration/FK/provider thật, connection độc lập/barrier và failure injection; assert balance/movement, ledger, usage/reservation, transfer/idempotency/response, audit và outbox, không chỉ HTTP/log.

Evidence lưu theo `09` §10: revision/working-tree baseline, test ID, lệnh, môi trường/config/migration, kết quả/artifact synthetic, mock disclosure, owner/reviewer và blocker. Chạy checks phù hợp (`./gradlew check`, ArchUnit, integration/alert drill khi đã có); chỉ ghi pass sau khi chạy và đọc output. Không nhận D3/D4/D5 hoặc release từ component evidence.

## 9. Blocker, vận hành và điều kiện đóng

| Owner | Gate trước activation/nghiệm thu |
|---|---|
| Transfer + owner BE-TRF-03–06 | Wiring thật, transaction/fence/unknown commit proof; event chỉ từ terminal transition đã chứng minh; policy PII và staff scope hoàn chỉnh. |
| Notification + Transfer + DB | Public API DTO và mapping khác biệt, recipient/template/channel matrix, unique/lease production migration, retry semantics và worker multi-instance. Không ghép bằng import `notification.domain`. |
| Customer + Notification + Security | Resolver SYSTEM/purpose/consent và failure semantics, adapter/provider thật, timeout/dedupe window; chỉ masked projection chưa đủ. |
| Audit + Common + Identity | Session/CSRF thật, C1/C2 JOIN và C3 fallback kể cả empty result, actor/context propagation, log/metric isolation, quota shared. |
| Ops + QA + Security | Metric naming/aggregation/freshness, thresholds/window/rotation/retention, alert routing/runbook và drill, quyền Ops replay/rearm có audit, leak review và integration evidence. |

Khi incident: xác nhận môi trường/primary và signal có mới; giữ evidence qua kênh có quyền; dùng reference/correlation an toàn để điều tra, không đưa key/token/response/PII vào ticket. Audit/PENDING/unknown theo BE-TRF-06 và R-AUD-01/R-CON-01; backlog theo R-CON-06 qua Notification, không sửa kết quả tiền. Không dùng log/metric làm proof tự chạy lại transfer. Nếu provider chưa sẵn sàng, giữ integration/activation Blocked và chỉ kiểm thử phần độc lập; không bean fake production.

**Điều kiện đóng:** cả ba nhóm AC của [backlog](README.md) Verified bằng provider/migration thật và evidence tái lập; security/context/leak suite, audit/outbox atomicity, telemetry/alert và multi-worker delivery đạt; owner Transfer/Notification/Audit/Common/Customer/Identity cùng QA/Security/Ops review phần liên quan. Chưa có review hoặc dependency/evidence thì giữ `[ ]` và ghi Blocked; hoàn tất tài liệu không đồng nghĩa hoàn tất BE-TRF-07.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.1 | 17/11/2026 | Triển khai candidate phía Transfer, ghi giới hạn provider/policy và liên kết evidence/rules. |
| v1.0 | 17/11/2026 | Đặc tả context/security/audit, meters/alerts, notification T2/T3, mapping provider, test vectors và gate nghiệm thu; chưa triển khai hoặc sign-off. |
