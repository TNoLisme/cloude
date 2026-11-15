# BE-TRF-01 — Kế hoạch contract suite và integration Transfer

> **Trạng thái:** Draft / Planned, chưa chạy, chưa được provider/consumer hoặc QA duyệt. Các test đòi hỏi module nghiệp vụ hoặc quyết định chưa có đều **Blocked**. Người theo dõi: Transfer (Vũ); reviewer và owner thực thi theo bảng dưới. Không coi tài liệu này là evidence thực thi hay sign-off.

## 1. Căn cứ và điều kiện chạy

Nguồn có thẩm quyền: [[digitalbanking-docs/docs-common/03. SRS.md]] (đặc biệt AC-006, AC-012, AC-033–034), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4, [[digitalbanking-docs/docs-common/07. API contracts.md]] §5–§6, §8.3, §10 và [[digitalbanking-docs/docs-common/09. Test plan.md]] §4–§7, §10. Chi tiết transaction/contract theo [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5–§10, §14 và [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §3–§14; theo thứ tự nguồn của [thiết kế Transfer](../../design.md), nguồn `01–11` ưu tiên hơn thiết kế chi tiết. Các điểm mâu thuẫn phải chuyển owner phân xử, không lấy kết quả fake làm quyết định.

Tại thời điểm rà soát, backend chỉ có common và migration tạo schema; chưa có implementation Transfer hoặc provider nghiệp vụ. Common có JCS, SHA-256, error, context, `Clock` và session SPI; chưa có identity adapter thật. Các tên operation trong `05` là đề xuất, cần provider/consumer review. Không khai báo test double thành bean production.

- **Tầng nhanh:** unit và consumer contract với fake chỉ ở test/dev, dùng cùng dữ liệu và oracle với provider contract suite. Kiểm tra input/output, nullability, lỗi, PII, binding và lời gọi; fake không chứng minh transaction.
- **Tầng bắt buộc cho invariant:** PostgreSQL thật (baseline Testcontainers PostgreSQL 16), migration và provider thật; connection/transaction độc lập, barrier/latch có timeout, failure injection có điểm dừng xác định. Kiểm tra HTTP **và** snapshot DB sau commit/rollback; không dùng H2 hay mock để chứng minh lock, unique, savepoint hoặc atomicity. Không gọi network/broker trong T2.
- **Khởi chạy khi đủ implementation:** từ `digitalbanking-backend`, Java 21 và Docker: `./gradlew clean check --offline --console=plain` (bỏ `--offline` nếu cần tải dependency). Lệnh là quality gate hiện có, **không** chứng minh test Transfer tồn tại; khi suite được tạo phải ghi thêm lệnh lọc test chính xác và report của từng ID. Xác nhận môi trường và kết quả thực tế, kể cả test bị skip.
- Dữ liệu synthetic cô lập, số tài khoản/ID không phải dữ liệu thật, `Clock` cố định khi thử expiry/date. Các giá trị tiền, thời gian và ngưỡng dưới đây chỉ là fixture, không phải policy. Mỗi case kiểm tra bất biến `09` §5.1: không âm; debit/credit và ledger cân bằng; terminal bất biến; một side effect/key; audit bắt buộc; phân quyền và masking.

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

Dùng key synthetic `trf-test-01`, actor A, V1; với fixture không cần step-up và balance/limit đủ, POST thành công `201`, `Location` và body `COMPLETED` như `07` §8.3. Retry cùng actor/key/hash sau terminal phải trả **cùng status/body** và `Idempotent-Replayed: true`, không thêm transfer/debit/ledger. Tạo fixture business failure **được phép và được quyết định sau T1**: lưu response `422`, retry phải replay đúng `422` và body; không biến thành `200`. Dùng cùng key đổi hash hoặc actor B → `409 IDEMPOTENCY_CONFLICT`, không tiết lộ body/reference của A, không side effect tiền mới và có security audit theo contract. Giữ transfer đang xử lý bằng latch/lock: cùng key/hash → `409 IDEMPOTENCY_IN_PROGRESS` + `Retry-After`; sau khi rảnh xử lý stale theo protocol được duyệt. Đối chiếu correlation header/body theo `OI-CON-13`; chưa áp đặt oracle header mới khi policy chưa chốt. 202 là phương án async tùy chọn, không là baseline synchronous. YAML/overview hiện lệch mô tả replay: API owner/FE/QA cần đồng bộ với `07` trước khi dùng làm oracle máy đọc.

## 3. Contract suite theo provider

Tất cả dòng **Planned / Blocked** đến khi provider và consumer review signature, phiên bản, nullability, lỗi, vector và implementation; tên dùng theo `05` §5–§12, không khẳng định API đã tồn tại.

| Provider → consumer / owner phối hợp | Vector và assertion fake + provider dùng chung | Integration thật bổ sung |
|---|---|---|
| Identity → Transfer / Đạt, Vũ | Session hợp lệ/cấm/hết hạn/revoked; request challenge không lộ OTP; sai user/purpose/hash/key hoặc hết hạn không consume. Cùng challenge VERIFIED consume tối đa một lần. | Hai transaction đua consume; rollback T1 khôi phục consume; adapter session thật, permission và CSRF. 428 trước T1 không có transfer/idempotency/ledger. Race tổng ngày chờ §5. |
| Account/Customer → Transfer / Đạt, Vũ | Resolve ID hoặc số tài khoản, not found/inactive; source khác owner bị từ chối; projection masked, không log PII. Debit đủ/thiếu, credit bất thường phân biệt business/technical theo `05` §7. | Blind index và IDOR thật; lock hai account ID tăng dần, revalidate dưới lock; inject lỗi sau debit, sau credit; rollback T2 cả hai balance. |
| Ledger → Transfer / Phương, Vũ | Post đúng một cặp debit/credit cùng transfer, amount/currency; reject mismatch/duplicate; query phân biệt absent, đủ, partial/mismatch và lỗi đọc. | Cùng T2/connection với Account; đúng 2 entry cân bằng và append-only; rollback sau post; unique/constraint thực trên PostgreSQL. |
| Limit → Transfer / Vũ | Dùng cấu hình fixture: dưới/bằng/vượt limit, currency và date; check không increment nếu bị từ chối. | Cùng T2, lock CUSTOMER trước ACCOUNT, increment/rollback; 2 key đồng thời không vượt hạn mức; config version/date còn chờ owner. |
| Audit → Transfer / Thịnh, Vũ | C1 CREATED ở T1, COMPLETED/FAILED ở T2; C3 conflict theo policy; C2 fraud flag bắt buộc. Correlation và payload allowlist an toàn. | Inject C1 T1/T2 và C2 riêng; rollback, audit lock cuối và chain trên DB; C2+C1 staging/order chờ chốt. |
| Fraud → Transfer / Thịnh, Vũ | Engine trả allow/flag/auto-block theo fixture cấu hình; exception engine fail-open, nhưng lỗi mandatory audit không được nuốt. | Savepoint thật không làm transaction rollback-only; flag/audit atomic, không ledger/usage khi block theo policy; count window/version chờ owner. |
| Notification → Transfer / Thịnh, Vũ | Enqueue outbox với payload masked/correlation; gateway fail chỉ tác động delivery sau commit. | Outbox atomic với terminal; claim/lease, crash/retry và duplicate delivery; test dedupe theo policy đã duyệt. |
| Common → Transfer / Phương, Vũ | V1 hash qua common JCS/SHA, Problem code/status, Clock/context, masking; không đưa raw key, OTP, token hoặc PII vào logs/evidence. | Tích hợp consumer thật và identity adapter thật; đối chiếu body/header và correlation, kiểm tra log/response/outbox. |

Transfer giữ write ownership transfer/idempotency; Account, Ledger, Limit, Audit và Notification tự ghi bảng của mình. Dùng public API thay repository/bảng nội bộ của provider. Với port chưa có, vector là đề xuất review, không coi fake là provider sẵn sàng.

Với retry chưa terminal, `07` §5.4/§8.3 mô tả 409 còn `04` §7.7.3 mô tả retry chạy lại T2. Hai nguồn này cần API/DB/BA phân xử; vector live/stale không tự chọn một quy tắc chung cho mọi trường hợp.

## 4. Ma trận tích hợp và failure injection

| ID / owner thực thi và review | Fixture, thao tác và oracle | Trạng thái / phụ thuộc |
|---|---|---|
| `T-CON-01–04` / Transfer + Account + Ledger, QA | PostgreSQL, độc lập connection và barrier: 20 lệnh cùng nguồn ban đầu 1.000.000, mỗi lệnh 100.000, key riêng, đủ limit và không step-up; song song A→B/B→A và các cặp độc lập. Không âm, tổng balance bảo toàn, mỗi COMPLETED có đúng 2 ledger cân bằng; lock/retry bounded. Ví dụ 10 thành công là kỳ vọng khi mọi fixture khác cho phép và oracle thất bại sau T1 được duyệt, không tự khẳng định trạng thái của 10 lệnh còn lại. | **Blocked** module thật và phân xử AC-006/AC-012. |
| `T-IDEM-01–06`, `CT-API-007–009` / Transfer + API + QA | 10 request cùng key/V1 qua barrier: tối đa một transfer và một debit, hai ledger nếu COMPLETED, 1 idempotency record; những request khác replay hoặc 409. Đổi payload, actor; retry 201/422; giữ lock live; crash sau T1 và sau commit T2 trước response. Xác nhận request thua unique rollback transfer tạm, không orphan. | **Blocked** implementation; stale/resume, 422 sau T1, TTL/correlation theo quyết định liên quan. |
| `CT-API-010`, `T-STEP-01–02` / Identity + Limit + Transfer + QA | Challenge VERIFIED đúng actor/purpose/hash/key; expired, mismatch, 2 consumer đồng thời; T1 rollback phục hồi consume; T1 commit rồi T2 fail không tiêu thụ lại. 428 không transfer/idempotency/ledger; verify + retry cùng key/payload theo contract. | **Blocked** Identity và policy/protocol race tổng ngày (§5). |
| `T-AUD-06/07` / Audit + Transfer + QA | Fail CREATED: rollback T1, không transfer/idempotency/consume. Fail COMPLETED/FAILED hoặc mandatory C2: rollback T2, giữ neo T1 PENDING, không balance/ledger/usage/outbox từ T2; 500 audit failure, không false success. Inject lỗi engine Fraud riêng: tiếp tục theo fail-open khi audit bắt buộc không lỗi. | **Blocked** Audit/Fraud và protocol C2+C1. |
| `T-REC-01–04` / Transfer + Ledger + Audit + Ops, QA | Crash sau T1, retry; mô phỏng PENDING với evidence đủ, thiếu audit, partial hoặc mismatch bằng fixture test-only. Hai worker claim cùng candidate, crash trong xử lý. Không debit/ledger kép; thiếu/lệch giữ nguyên + alert/manual; đủ evidence chỉ terminal khi protocol và đồng bộ response/idempotency được chấp thuận, nếu chưa thì giữ nguyên + alert/manual. Không tự sửa ledger. | **Blocked** implementation và recovery protocol; đủ evidence phải hoàn tất không duplicate khi protocol đã duyệt. |
| `T-OUT-01/02` / Notification + Transfer + Ops, QA | Commit transfer, gateway lỗi, worker chết sau claim/lease và sau send trước ack; assert terminal/balance/ledger giữ nguyên, outbox được retry, thử duplicate và dedupe theo policy. | **Blocked** outbox/worker và policy delivery/lease. |
| TTL/purge / Transfer + Product/BA + Security + Ops | Dùng Clock cố định: record terminal trước/sau TTL được duyệt; PENDING quá TTL không purge; sau đó cho PENDING quá TTL chuyển terminal và kiểm thời điểm được phép purge theo mốc chính sách đã duyệt. Không giả định có thêm 24h sau terminal khi expiry tính từ created_at. Sau purge thử reuse key trên dữ liệu synthetic; client query reference theo hướng dẫn được duyệt. | **Blocked** retention, mốc tính TTL và retry/query policy; 24h chỉ đề xuất. |
| Access/masking / Identity + Account + Transfer + Security, QA | Customer tham gia/không tham gia, staff có/thiếu scope, session/CSRF sai; GET/POST không lộ transfer hoặc PII trái quyền; scan response, log, audit và outbox cho dữ liệu synthetic. | **Blocked** provider thật và review scope. |

Mọi kết quả thất bại và test skipped phải ghi rõ. D1–D5, hiệu năng và Cloud/release cần evidence riêng theo `09`; test ở đây không thay thế các cổng đó.

## 5. Oracle cần quyết định trước nghiệm thu

1. **Step-up theo tổng ngày — P0:** [DEC-TRF-001/002](../../../../../../DECISIONS.md) chọn cộng các lệnh đang xử lý với usage đã hoàn tất và lệnh đang xét; mặc định đơn lệnh ≥ 10 triệu VND hoặc tổng dự kiến > 20 triệu VND/ngày. Identity, Limit, Architect, BA/Product còn xác định scope, ngày và protocol. Với dữ liệu hợp lệ và trigger risk khác không kích hoạt: `9.999.999,99` không kích hoạt điều kiện đơn lệnh, `10.000.000,00` kích hoạt; tổng `20.000.000,00` không kích hoạt điều kiện tổng ngày, `20.000.000,01` kích hoạt, mỗi lệnh xét phải dưới 10 triệu để cô lập hai điều kiện. Kiểm từng nhánh `428` trước T1, không transfer/idempotency/ledger do nhánh 428 tạo, binding và rollback. Dựng hai key khác nhau; từng request riêng dưới ngưỡng nhưng tổng vượt ngưỡng. Theo phương án reservation `05` §10.1, thêm race hai request cùng key khi một T1 commit lúc request kia đợi guard; recheck phải tránh 428 hoặc consume/reserve thừa; test deadlock giữa guard và idempotency/transfer, Tfail/recovery settlement per-transfer và rollback audit. Test thêm request A quyết 428, dừng trước response và request B cùng key commit T1; oracle về challenge và trạng thái quan sát còn cần owner phân xử. Config/consumer thực tế chưa đồng bộ; chặn release money path đến khi protocol được chứng minh.
2. **AC-006 / AC-012:** BA, QA, Architect, Account, Transfer thống nhất kết quả khi lỗi tồn tại ở preflight, thay đổi sau T1 và credit technical sau debit; đồng bộ nguồn trước nghiệm thu. Trước T1 theo AC-006 không tạo transfer đối với inactive/thiếu tiền đã biết. Technical credit lỗi phải rollback debit; không khẳng định trạng thái cuối đã giải quyết AC-012.
3. **Recovery/TTL:** Transfer, Ledger, Audit, Ops, Product/BA và Security quyết định proof đủ, thao tác atomic status/idempotency/response, retry/escalation, retention và hướng dẫn client. `T-REC-02` là yêu cầu hoàn tất không duplicate khi đủ evidence theo protocol được duyệt; thiếu protocol/evidence hoặc mismatch giữ nguyên và alert/manual.
4. **C2/C1, OI-CON-02–13, MOD-OI-01–09:** owner theo `05` §14 và `design/02` §16.2 chốt các phần liên quan, gồm trạng thái live/stale, config/date/version, fraud window, audit lock, multi-instance, delivery và correlation. Không coi cách fake xử lý là policy.

## 6. Evidence và bàn giao

Mỗi lần chạy lưu record theo `09` §10: trạng thái, phạm vi/loại test và test ID, requirement/AC, revision (commit và nếu có diff), hệ điều hành/JDK/PostgreSQL/profile/cấu hình không chứa secret, lệnh chính xác, steps/fixture, expected và actual, số passed/failed/skipped, report gốc/đường dẫn artifact, owner/reviewer, giới hạn/blocker. Lưu snapshot response và truy vấn DB synthetic trước/sau với mask, correlation an toàn; ghi lại injection point, barrier/timeout và transaction topology cho race. Không lưu token, OTP, raw key, PII, mật khẩu hoặc secrets. Link report gốc với summary và liên kết quyết định có thẩm quyền trong `DECISIONS.md`; không ghi quyết định khi chưa có người có thẩm quyền duyệt.

Provider và consumer review cùng vector/oracle; QA review traceability và kết quả; Security review access/masking/step-up; Ops review recovery/outbox/runbook. Đóng riêng từng blocker với evidence thật và reviewer xác nhận. Tài liệu này chỉ là kế hoạch và không thay điều kiện đóng BE-TRF-01 tại [task](../BE-TRF-01.md).
