# BE-TRF-01 — Kế hoạch contract suite và integration Transfer

> **Trạng thái:** Baseline oracle v1; provider/consumer và QA chưa review, kết quả candidate đã có theo evidence được dẫn. Các test đòi hỏi module nghiệp vụ hoặc quyết định chưa có đều **Blocked**. Người theo dõi: Transfer (Vũ); reviewer và owner thực thi theo bảng dưới. Không coi tài liệu này là evidence thực thi hay sign-off.

## 1. Căn cứ và điều kiện chạy

Nguồn có thẩm quyền: [[digitalbanking-docs/docs-common/03. SRS.md]] (đặc biệt AC-006, AC-012, AC-033–034), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4, [[digitalbanking-docs/docs-common/07. API contracts.md]] §5–§6, §8.3, §10 và [[digitalbanking-docs/docs-common/09. Test plan.md]] §4–§7, §10. Chi tiết transaction/contract theo [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5–§10, §14 và [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §3–§14; theo thứ tự nguồn của [thiết kế Transfer](../../design.md), nguồn `01–11` ưu tiên hơn thiết kế chi tiết. Các điểm mâu thuẫn phải chuyển owner phân xử, không lấy kết quả fake làm quyết định.

Transfer có các candidate và test với fake/fixture, phạm vi chạy tại [BE-TRF-04/05](BE-TRF-04-05-candidate.md) và [BE-TRF-02](BE-TRF-02-candidate-verification.md); không coi chúng là provider thật, API production hay integration. Common có JCS, SHA-256, error, context, `Clock` và session SPI; chưa có identity adapter thật. Contract dữ kiện/outcome v1 đã chọn tại `05` §13.4 theo DEC-TRF-015, provider runtime và review còn thiếu. Không khai báo test double thành bean production.

- **Tầng nhanh:** unit và consumer contract với fake chỉ ở test/dev, dùng cùng dữ liệu và oracle với provider contract suite. Kiểm tra input/output, nullability, lỗi, PII, binding và lời gọi; fake không chứng minh transaction.
- **Tầng bắt buộc cho invariant:** PostgreSQL thật (baseline Testcontainers PostgreSQL 16), migration và provider thật; connection/transaction độc lập, barrier/latch có timeout, failure injection có điểm dừng xác định. Kiểm tra HTTP **và** snapshot DB sau commit/rollback; không dùng H2 hay mock để chứng minh lock, unique, savepoint hoặc atomicity. Không gọi network/broker trong T2.
- **Khởi chạy khi đủ implementation:** từ `digitalbanking-backend`, Java 21 và Docker: `./gradlew clean check --offline --console=plain` (bỏ `--offline` nếu cần tải dependency). Lệnh là quality gate hiện có, **không** chứng minh test Transfer tồn tại; khi suite được tạo phải ghi thêm lệnh lọc test chính xác và report của từng ID. Xác nhận môi trường và kết quả thực tế, kể cả test bị skip.
- Dữ liệu synthetic cô lập, số tài khoản/ID không phải dữ liệu thật, `Clock` cố định khi thử expiry/date. Các giá trị fixture không phải policy, trừ ngưỡng/TTL được chốt trong DEC-TRF-001–012. Mỗi case kiểm tra bất biến `09` §5.1: không âm; debit/credit và ledger cân bằng; terminal bất biến; một side effect/key; audit bắt buộc; phân quyền và masking.

## 2. Vector API và canonicalization dùng chung

### V1 — hash payload

Session synthetic `customerId=2001`, request (customerId lấy từ session, không nhận từ body):

```json
{"sourceAccountId":"3001","destination":{"accountNumber":"0123456789"},"amount":"150000.00","currency":"VND","description":"Chuyển tiền tháng 11"}
```

Sau chuẩn hóa theo `07` §5.2, JCS UTF-8 **không có newline cuối**, 187 byte:

```json
{"amount":"150000.00","currency":"VND","customerId":"2001","description":"Chuyển tiền tháng 11","destination":{"type":"ACCOUNT_NUMBER","value":"0123456789"},"sourceAccountId":"3001"}
```

SHA-256 lowercase dự kiến `b03c53146d9feb3f9b22374ba6bd91d98171e3061f3f5ce4b01fb482e0f5839a`. Kiểm tra độc lập fixture tại `digitalbanking-backend/src/test/resources/crypto/transfer.json` sau khi loại **chỉ newline cuối**; fixture JCS không thay thế test chuẩn hóa request của Transfer. Header/key, correlation, timestamp và device không vào hash. Hai request hợp lệ có thứ tự JSON khác và description có khoảng trắng ở hai đầu tạo cùng hash sau trim; `null` và `""` description cùng canonicalize `""`; currency thiếu mặc định VND. Đổi amount từ `150000.00` sang `150001.00` hoặc đổi customer trong session tạo hash khác. `ACCOUNT_ID` và `ACCOUNT_NUMBER` là type khác nhau, không tự gộp dù resolve cùng account. Kiểm tra giữ số 0 đầu của account number. Xác minh regex key 1–255 ký tự, ký tự không hợp lệ và key thiếu → 400 không side effect; số tiền >2 chữ số thập phân bị từ chối.

### V2 — response và retry

Dùng key synthetic `trf-test-01`, actor A và V1. Với balance/limit đủ, POST thành công `201` + Location. Retry cùng actor/key/hash terminal replay đúng status/body gốc (`201` hoặc `422`) và `Idempotent-Replayed: true`; body giữ correlation gốc, header mang correlation request mới. Khác payload/actor → `409 IDEMPOTENCY_CONFLICT`, không lộ kết quả, không side effect tiền mới. Bản ghi nonterminal → `409 IDEMPOTENCY_IN_PROGRESS` + Retry-After; worker nội bộ phục hồi theo DEC-TRF-004, HTTP không resume. Kiểm 10 request cùng key, crash sau T1 và sau commit T2, replay FAILED, unique rollback không orphan. `202` chỉ tùy chọn async. TTL theo DEC-TRF-005, không purge PENDING; kiểm record đã expired nhưng chưa purge vẫn replay và reuse sau purge. Các oracle này chưa có kết quả test thực thi.

## 3. Contract suite theo provider

Các dòng mô tả oracle provider và integration; baseline dữ kiện/outcome đã chọn tại `05` §13.4, test fake có thể chạy ngay. Chỉ provider/consumer review và test provider thật mới xác nhận integration; không khẳng định API đã tồn tại.

| Provider → consumer / owner phối hợp | Vector và assertion fake + provider dùng chung | Integration thật bổ sung |
|---|---|---|
| Identity → Transfer / Đạt, Vũ | Session hợp lệ/cấm/hết hạn/revoked; request challenge không lộ OTP; sai user/purpose/hash/key hoặc hết hạn không consume. Cùng challenge VERIFIED consume tối đa một lần. | Hai transaction đua consume; rollback T1 khôi phục consume; adapter session thật, permission và CSRF. 428 trước T1 không có transfer/idempotency/ledger. Race tổng ngày chờ §5. |
| Account/Customer → Transfer / Đạt, Vũ | Resolve ID hoặc số tài khoản, not found/inactive; source khác owner bị từ chối; projection masked, không log PII. Debit đủ/thiếu, credit bất thường phân biệt business/technical theo `05` §7. | Blind index và IDOR thật; lock hai account ID tăng dần, revalidate dưới lock; inject lỗi sau debit, sau credit; rollback T2 cả hai balance. |
| Ledger → Transfer / Phương, Vũ | Post đúng một cặp debit/credit cùng transfer, amount/currency; reject mismatch/duplicate; query phân biệt absent, đủ, partial/mismatch và lỗi đọc. | Cùng T2/connection với Account; đúng 2 entry cân bằng và append-only; rollback sau post; unique/constraint thực trên PostgreSQL. |
| Limit → Transfer / Vũ | Dùng cấu hình fixture: dưới/bằng/vượt limit, currency/date, reservation/evidence theo transfer; check không increment nếu bị từ chối. | Guard customer trước transfer, sau đó usage CUSTOMER trước ACCOUNT; increment/rollback; PENDING qua nửa đêm, hai key đồng thời, config version/date và settlement. |
| Audit → Transfer / Thịnh, Vũ | C1 CREATED ở T1, COMPLETED/FAILED ở T2; C3 conflict theo policy; C2 fraud flag bắt buộc. Correlation và payload allowlist an toàn. | Inject C1 T1/T2 và C2 riêng; business flush rồi C2 theo flag ID tăng, C1 cuối; rollback, audit chain và lock trên DB theo DEC-TRF-007. |
| Fraud → Transfer / Thịnh, Vũ | Engine trả allow/flag/auto-block theo fixture cấu hình; exception engine fail-open, nhưng lỗi mandatory audit không được nuốt. | Savepoint thật không làm transaction rollback-only; flag/audit atomic, không ledger/usage khi block theo policy; count window/version chờ owner. |
| Notification → Transfer / Thịnh, Vũ | Enqueue outbox với payload masked/correlation; gateway fail chỉ tác động delivery sau commit. | Outbox atomic với terminal; claim/lease, crash/retry và duplicate delivery; test dedupe theo policy đã duyệt. |
| Common → Transfer / Phương, Vũ | V1 hash qua common JCS/SHA, Problem code/status, Clock/context, masking; không đưa raw key, OTP, token hoặc PII vào logs/evidence. | Tích hợp consumer thật và identity adapter thật; đối chiếu body/header và correlation, kiểm tra log/response/outbox. |

Transfer giữ write ownership transfer/idempotency; Account, Ledger, Limit, Audit và Notification tự ghi bảng của mình. Dùng public API thay repository/bảng nội bộ của provider. Với port chưa có, vector là đề xuất review, không coi fake là provider sẵn sàng.

DEC-TRF-004 phân biệt HTTP 409 nonterminal với worker nội bộ xử lý PENDING; test từng actor, crash và terminal replay trên provider thật.

## 4. Ma trận tích hợp và failure injection

| ID / owner thực thi và review | Fixture, thao tác và oracle | Trạng thái / phụ thuộc |
|---|---|---|
| `T-CON-01–04` / Transfer + Account + Ledger, QA | PostgreSQL, nhiều connection/barrier: nguồn 1.000.000, 20 lệnh × 100.000, đủ limit/no step-up; A→B/B→A và cặp độc lập. Không âm, tổng balance bảo toàn, mỗi COMPLETED đúng 2 ledger; điều kiện thay đổi sau T1 → FAILED, không debit/ledger/usage theo DEC-TRF-003. | **Blocked** provider thật và evidence. |
| `T-IDEM-01–06`, `CT-API-007–009` / Transfer + API + QA | 10 request cùng key qua barrier: tối đa 1 transfer/debit; terminal replay 201/422 kể cả sau khi destination inactive hoặc balance thay đổi. B đọc key vắng, A commit trước khi B preflight reject → B đọc lại primary và replay/409/conflict theo record; nếu key vẫn vắng, lỗi preflight tuyến tính hóa tại lần đọc đó. Nonterminal HTTP 409 + Retry-After; khác actor/hash conflict, kể cả hai user cùng customer/key/hash; unique thua rollback T1 và đọc lại ngoài TX; body correlation gốc/header mới. Crash trước/sau commit, không duplicate. | **Blocked** implementation/provider và evidence. |
| `CT-API-010`, `T-STEP-01–02` / Identity + Limit + Transfer + QA | Challenge bind user/purpose/hash/key, expired/mismatch, 2 consume, T1 rollback; expired row chiếm unique active được Identity đánh dấu EXPIRED trước reissue, 2 request reissue chỉ một active, challenge cũ không consume. Request trả 428 không tự tạo transfer/idempotency/ledger/reservation. Cùng key request B có thể commit trong khi A chờ 428; A retry replay/409, không consume/reserve lần hai. | **Blocked** Identity, Limit và race/lock proof. |
| `T-AUD-06/07` / Audit + Transfer + QA | Fail CREATED rollback T1. Fail C2 bất kỳ sau business flush hoặc C1 sau nhiều C2 → rollback cả T2, không false success; DEC-TRF-007 C2 flag ID ASC rồi C1, audit lock cuối. Engine lỗi rollback savepoint, loại descriptor, chỉ fail-open khi persistence context dùng được; test JPA deferred flush/trigger và chain 2 connection. | **Blocked** Audit/Fraud provider và PostgreSQL evidence. |
| `T-REC-01–04` / Transfer + Account + Ledger + Limit + Audit + Ops, QA | T-REC-02: đủ evidence per-transfer tiền/ledger/usage/settlement/audit/mode và response an toàn → atomic COMPLETED/idempotency/audit, không post lại tiền; thiếu/partial/lệch/DB error/commit unknown → không đổi trạng thái thực tế, alert/manual. Test 2 worker, crash/rollback, response gốc, reservation mode. | **Blocked** provider/evidence PostgreSQL. |
| `T-OUT-01/02` / Notification + Transfer + Ops, QA | Commit transfer, gateway lỗi, worker chết sau claim/lease và sau send trước ack; assert terminal/balance/ledger giữ nguyên, outbox được retry, thử duplicate và dedupe theo policy. | **Blocked** outbox/worker và policy delivery/lease. |
| TTL/purge / Transfer + Product/BA + Security + Ops | DEC-TRF-005: T1 expiry = created+TTL_T1, terminal expiry = max(old, completed_at+TTL_terminal), cả hai snapshot ≥24h; test cấu hình 48→24h, 24→48h, invalid/unavailable fail closed/version. PENDING >24h không purge, terminal giữ ít nhất 24h từ completed_at. Expired chưa purge vẫn replay/conflict; sau purge reuse key có thể thành lệnh mới; concurrent retry/purge và query reference/Ops. | **Blocked** implementation/migration, provider review và evidence. |
| Access/masking / Identity + Account + Transfer + Security, QA | Customer tham gia/không tham gia, staff có/thiếu scope, session/CSRF sai; GET/POST không lộ transfer hoặc PII trái quyền; scan response, log, audit và outbox cho dữ liệu synthetic. | **Blocked** provider thật và review scope. |

Mọi kết quả thất bại và test skipped phải ghi rõ. D1–D5, hiệu năng và Cloud/release cần evidence riêng theo `09`; test ở đây không thay thế các cổng đó.

## 5. Oracle đã chọn và kiểm chứng còn thiếu

1. **Step-up — DEC-TRF-001/002/008:** mặc định amount ≥10 triệu hoặc tổng dự kiến >20 triệu VND; ranh giới `9.999.999,99`/`10.000.000,00`, tổng `20.000.000,00`/`20.000.000,01` với amount <10 triệu và không risk khác. Guard theo customer; tổng gồm completed ngày hiện tại + mọi PENDING kể cả từ ngày trước + request mới. Test qua nửa đêm, chuyển PENDING sang usage ngày hoàn tất atomically, không đếm trùng: `terminal_at` lấy một lần từ primary sau guard/transfer/account, trước lock usage; giữ barrier cho timestamp trước 00:00 nhưng commit sau 00:00, assert usage thuộc ngày timestamp cũ, PENDING được tính đến commit. Test timestamp sau 00:00, `terminal_at < created_at` phải rollback/alert, config đổi, 428/unique/key race và lock timeout/deadlock.
2. **AC-006/012 — DEC-TRF-003:** lỗi biết trước T1 không intent; thay đổi sau T1 → FAILED atomic không money/ledger/usage. Credit failure chỉ finalize FAILED sau rollback được chứng minh trong savepoint còn lock hoặc Tfail có durable fencing; không suy đoán commit/PENDING khi timeout, crash hay thiếu proof. Test failure giữa debit/credit, savepoint rollback-only, crash trước Tfail, competing worker và audit failure.
3. **Retry/TTL/recovery — DEC-TRF-004/005/006:** nonterminal HTTP 409; worker nội bộ phục hồi, terminal replay gốc; TTL 24h từ completed_at, không purge PENDING. Đủ evidence được định nghĩa ở DEC-TRF-006 → T-REC-02 atomically hoàn tất không duplicate; thiếu/lệch/DB error/response gốc không thể chứng minh → giữ nguyên, alert/manual. Test per-transfer Account/Limit evidence, replay 201/422, crash, concurrent purge và nhiều worker.
4. **Audit — DEC-TRF-007:** sau business flush, các C2 flag ID tăng rồi C1 cùng transaction, không business SQL sau audit lock; bất kỳ audit lỗi rollback toàn T2; fail-open chỉ cho engine đã rollback savepoint và persistence context an toàn. Test concurrent chain, deferred flush/trigger và failure injection.

Các lựa chọn trên cần provider/consumer/QA review và evidence thực tế; các quyết định OI còn lại theo DEC-TRF-009–012 và risk register. Không coi fake là bằng chứng transaction hay lock.

5. **Limit/fraud/ledger — DEC-TRF-009:** đồng thời thay config, kiểm snapshot/version; fraud count 5/6 tại hai biên 600s, FAILED/PENDING, current cũ và retry; tiêm entry thiếu/thừa/sai để chứng minh rollback trước audit.
6. **Recovery/notification/OTP — DEC-TRF-010/011:** test hai worker, lease hết hạn và ACK cũ; crash trước/sau gateway, giới hạn 5 attempts/15 phút, backoff, thiếu evidence thì alert/manual; OTP timeout, resend generation, race verify/resend và scan secret.
7. **Security/boundary — DEC-TRF-012:** quota chung nhiều instance và store lỗi, key cross-actor, IDOR staff/customer và fraud permission; negative fixture import nội bộ, contract suite với fake và provider thật.

## 6. Evidence và bàn giao

Mỗi lần chạy lưu record theo `09` §10: trạng thái, phạm vi/loại test và test ID, requirement/AC, revision (commit và nếu có diff), hệ điều hành/JDK/PostgreSQL/profile/cấu hình không chứa secret, lệnh chính xác, steps/fixture, expected và actual, số passed/failed/skipped, report gốc/đường dẫn artifact, owner/reviewer, giới hạn/blocker. Lưu snapshot response và truy vấn DB synthetic trước/sau với mask, correlation an toàn; ghi lại injection point, barrier/timeout và transaction topology cho race. Không lưu token, OTP, raw key, PII, mật khẩu hoặc secrets. Link report gốc với summary và liên kết quyết định có thẩm quyền trong `DECISIONS.md`; không ghi quyết định khi chưa có người có thẩm quyền duyệt.

Provider và consumer review cùng vector/oracle; QA review traceability và kết quả; Security review access/masking/step-up; Ops review recovery/outbox/runbook. Đóng riêng từng blocker với evidence thật và reviewer xác nhận. Tài liệu này chỉ là kế hoạch và không thay điều kiện đóng BE-TRF-01 tại [task](../BE-TRF-01.md).

## Baseline v1: điều kiện contract test dùng chung

Provider suite và fake suite dùng cùng vector §2–§5, bao gồm null/absent khác DB error; step-up guard CUSTOMER và trạng thái reservation; settlement COMPLETED tăng usage mỗi scope đúng một lần và FAILED không tăng; debit/credit và cặp ledger cân bằng; audit lỗi fail-closed; replay và binding; recovery per-transfer evidence đủ/thiếu/lệch. Baseline dữ kiện là `05` §13.4, không tự tạo bộ contract khác. Tên test candidate, command và kết quả cụ thể xem [BE-TRF-04/05](BE-TRF-04-05-candidate.md); proof provider thật và PostgreSQL sau ghép vẫn chưa có.
