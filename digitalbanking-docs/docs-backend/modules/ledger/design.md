# Thiết kế chi tiết module `ledger`

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-MOD-LEDGER-01
> **Phiên bản:** v1.1 — 17/11/2026
> **Trạng thái:** Draft — chờ review; không phải phê duyệt triển khai
> **Owner:** Phương; **Reviewer:** Transfer, Account, DB, API, Audit, Security, QA, Ops

## 1. Phạm vi, nguồn và hiện trạng

Ledger là write-owner `banking.ledger_entry`, ghi cặp bút toán bất biến trong T2 và trả evidence phục vụ reconciliation. Account sở hữu account, ownership, trạng thái và materialized balances; Transfer sở hữu intent, idempotency, lifecycle, recovery và điều phối T0–T3. Ledger không tự debit/credit, quyết định limit, tạo transfer hay gửi notification. DEC-LED-002 chọn Transfer làm owner projection history, dùng Account facts và Common/Identity scope; wiring chờ review.

Nguồn ưu tiên: [[digitalbanking-docs/docs-common/03. SRS.md]] FR-015–022, FR-027–030, FR-034/038/039, AC-006–013/015/016/018/023; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.3–4.4; [[digitalbanking-docs/docs-common/07. API contracts.md]] §5, §8.4; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §7–9, §12.3, §13.4; [[digitalbanking-docs/docs-common/09. Test plan.md]]; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]]. `docs-common/01–11` ưu tiên hơn `design/`, sau đó `docs-backend/`. Quyết định Transfer liên quan ở [[DECISIONS.md]] DEC-TRF-003/004/006/008/014/015; review và evidence liên module vẫn cần thiết.

Rà soát working tree 17/11: provider posting `banking.ledger` đã triển khai (BE-LED-03/DEC-LED-005) với api/domain/application/adapter và evidence candidate LED-POST-01–14; `banking.account` chưa có source production; Flyway production chưa có migration hai bảng này (duplicate V2 chờ DB owner). Service/adapter Ledger đăng ký tường minh, chưa kích hoạt production. `transfercandidate` có orchestration T2 và PostgreSQL test với bảng/provider synthetic, chưa được wiring làm money path; không xem test đó là bằng chứng Ledger thật. Common đã có security matcher cho transaction GET; tái sử dụng, vẫn phải kiểm tra object authorization. Trước implementation rà lại working tree/migration để tránh trùng công việc hoặc xung đột version.

## 2. Phối hợp và contract

| Provider → consumer | Hợp đồng và trách nhiệm | Tình trạng / kiểm chứng |
|---|---|---|
| Ledger → Transfer | `postDoubleEntry(transferId, sourceId, destinationId, amount, currency, sourceBalanceAfter, destinationBalanceAfter, correlationId)` → hai entry ID; JOIN T2, fail fast nếu thiếu transaction; không commit riêng. `findEvidence(transferId)` trả từng entry, phân biệt vắng/thiếu/lệch/lỗi. | Baseline v1 §9/§13.4; chưa có provider thật. |
| Account → Transfer/read composition | Resolve/ownership/masked view; lock account tăng dần, debit có điều kiện, credit và trả `ledgerBalanceAfter` trong T2. | Account owner; kiểm lại dưới lock; không đọc repository private. |
| Transfer → read composition | Intent/status/reference/description và source/destination cho history, kể cả PENDING/FAILED; nhận scope server-side. | Baseline §12.3 và DEC-LED-002; scope/projection provider và wiring chờ review. |
| Account → reconciliation | Opening balance bất biến có provenance và số dư materialized trong snapshot primary. | Job đối soát Ledger dùng dependency đề xuất tại `05` §9 (DEC-LED-003); dùng fake test-only đến khi provider sẵn sàng. |
| Identity/Common → HTTP | Session, permission, actor context, error, correlation và security filter. | Dùng SPI/filter hiện có; không tin actor từ client. |
| Limit, Audit, Fraud, Notification → Transfer | Limit guard/usage; C1 audit trong T1/T2; fraud theo policy; outbox T2, delivery T3. | Các owner và Transfer chịu trách nhiệm; Ledger test rollback khi tích hợp. |
| Ledger/Account/Audit/Limit → Transfer recovery | Evidence per-transfer đủ đối chiếu, đọc primary trong transaction recovery. | §13.4; thiếu/lệch/lỗi giữ nguyên và alert/manual; không tự post lại. |

Chỉ gọi qua `provider.api`, DTO immutable, không truyền entity, không tạo vòng phụ thuộc Ledger → Transfer → Ledger. Khi provider chưa có, fake theo contract chỉ trong test; không đăng ký fake production, không lấy test fake làm bằng chứng atomicity hoặc integration. Tất cả provider JOIN dùng cùng datasource/transaction manager; không network dưới lock.

## 3. Mô hình dữ liệu và bất biến

Theo `04` §4.4.2: `entry_id BIGINT IDENTITY`, `transfer_id` và `account_id` FK, `entry_seq SMALLINT`, `entry_type`, `amount NUMERIC(19,2)`, `currency CHAR(3) VND`, `balance_after NUMERIC(19,2)`, `correlation_id`, `created_at TIMESTAMPTZ`. Amount > 0, balance_after ≥ 0; dùng `BigDecimal`, không `double`. Unique `(transfer_id, entry_seq)`; index account/time, transfer và correlation theo `04`. Migration forward-only, thứ tự sau migration Account/Transfer, phối hợp DB owner về version/FK/roles và schema validation.

Mỗi COMPLETED có đúng 2 entry cùng transfer, amount, currency: seq 1 DEBIT source, seq 2 CREDIT destination, tổng có dấu bằng 0; PENDING/FAILED không có entry ở trạng thái nhất quán. Constraint unique chỉ ngăn trùng seq, không tự chứng minh đủ cặp, đúng account, amount hay status. Theo DEC-TRF-009, kiểm đồng bộ toàn bộ cặp trước pha audit cuối T2; reconciliation phát hiện sai lệch không thay kiểm tra đồng bộ. Quyết định này không chọn deferred constraint trigger cho MVP; cân nhắc sau benchmark và review. SQL candidate [BE-LED-02](tasks/BE-LED-02.md) cấp runtime SELECT/INSERT và có trigger chặn UPDATE/DELETE/TRUNCATE. [Evidence PostgreSQL](tasks/evidence/BE-LED-02.md) dùng fixture FK Account/Transfer; kiểm lại quyền, thứ tự và version trên chain thật trước triển khai. Không sửa/xóa bút toán để chữa sai lệch.

Schema baseline `04` §4.4.2 dùng transfer ID và account ID; reference/counterparty đến từ projection history. DEC-LED-003 chọn baseline mở sổ thuộc Account, có amount/currency, effectiveAt trước bút toán đầu tiên và provenance ID. Đối soát trên cùng snapshot primary dùng `opening + SUM(CREDIT) − SUM(DEBIT)` với 0 cho tổng trên tập bút toán rỗng. Opening bằng 0 vẫn cần evidence. Thiếu hoặc không xác minh được baseline, seed, snapshot hoặc kỳ vọng thì giữ dữ liệu và báo incident. Không suy opening từ balance hiện tại. Opening journal entry là phương án cần thiết kế riêng do `transfer_id NOT NULL`.

## 4. Workflow chuyển tiền và failure

1. **Preflight/T0:** Transfer xác thực, phân quyền, kiểm input, tra global idempotency key trước các preflight thay đổi; cùng actor/hash terminal replay status/body đã lưu, nonterminal trả 409 + Retry-After theo DEC-TRF-004, khác actor/hash conflict. Nếu cần step-up trả 428 trước tạo intent; không ghi ledger. Preflight balance là snapshot, không thay kiểm tra T2.
2. **T1:** Transfer tạo PENDING và idempotency, consume challenge/reservation nếu áp dụng, audit CREATED trong transaction riêng; unique race rollback cả T1 rồi đọc lại primary. Không có transaction ngoài bao trùm T1 và T2.
3. **T2:** Transfer giữ guard customer → lock transfer/fence → Account lock hai ID tăng dần → Limit lock customer/account; revalidate ownership, status, currency, amount, available balance và limit dưới lock. Conditional debit phải kiểm affected rows; credit cùng transaction. Account trả `ledgerBalanceAfter` cho hai phía, Transfer gọi Ledger post đúng cặp trong JOIN/MANDATORY. Settle usage/reservation đúng một lần, terminal/idempotency response, outbox, flush business, sau đó C1/C2 audit theo thứ tự contract, tất cả commit/rollback cùng nhau. Isolation READ COMMITTED; lock timeout/deadlock retry có giới hạn toàn transaction mới khi protocol chứng minh an toàn; không retry statement trong transaction lỗi.
4. **Failure:** business rejection trước T1 không tạo intent; điều kiện thay đổi sau T1 xử lý FAILED theo DEC-TRF-003. Lỗi credit sau debit chỉ ghi FAILED cùng T2 sau rollback savepoint đã chứng minh toàn bộ money/ledger và persistence context an toàn; Tfail riêng chỉ khi rollback, fence và evidence đủ. Lỗi ledger, audit hoặc invariant propagate rollback T2; duplicate/partial posting là incident, không chuyển unique violation thành success/replay. Commit chưa rõ: đọc primary, kiểm fence/terminal và evidence; không suy đoán rollback hoặc chạy lại. Recovery theo DEC-TRF-006/014 cần đủ evidence Account/Ledger/Limit/Audit và response; thiếu/lệch giữ nguyên, alert/manual, không sửa ledger.
5. **T3:** Notification gửi sau commit, retry/dedupe không thay kết quả tiền. Correlation xuyên suốt, không log raw idempotency key hay PII.

Idempotency thuộc Transfer; unique ledger là lớp bảo vệ bổ sung, không thay thế replay hoặc fencing. Audit failure không được fail-open; fraud fail-open chỉ theo savepoint/policy riêng đã được chứng minh.

## 5. API, bảo mật và vận hành

`GET /api/v1/transactions` (API-TXN-01) và `GET /api/v1/transactions/{reference}` (API-TXN-02) theo `07` §8.4 và OpenAPI: customer self/participant, staff `TRANSACTION_READ` trong scope server-side. List lọc `customerId`, `accountId`, status PENDING/COMPLETED/FAILED, direction (cần accountId), from inclusive mặc định now−30d, to exclusive mặc định now, min/max amount, page/size/sort whitelist theo §2.5. `INTERNAL` khi customer sở hữu cả hai phía; staff với accountId tính theo account đó. Một transfer chỉ xuất hiện một lần khi cùng customer sở hữu hai account. Detail theo reference, mask counterparty, không lộ `fraudFlags`, balance của account người khác, số tài khoản đầy đủ hoặc entity. 400/401/403/404/500/503 và Problem theo `07`; customerId khác mình bị 403. Chốt sự khác biệt contract/YAML với API owner, không tự đổi response.

Phân quyền ở service và SQL/read scope trước pagination/count, tránh IDOR và rò tổng số. Identity session và permission không thay ownership; staff scope cần provider review. Dùng DTO records, `BigDecimal`/decimal string, `Instant` UTC, schema validation, sort whitelist, common exception/security/correlation và `no-store` theo conventions. C3 deny audit theo contract Audit; C1 fail-closed ở money path. Log/metrics không chứa secrets, raw key hay PII; alert mismatch, partial pair, unknown commit, stuck PENDING và reconciliation failures; không tự sửa dữ liệu tài chính.

## 6. Thiết kế read và các quyết định mở

DEC-LED-002 dùng Transfer intent làm nguồn duy nhất cho list/detail để gồm cả PENDING/FAILED. Transfer áp dụng facts ownership do Account công bố và staff scope server-side trước count/page. Masked views lấy theo batch, cùng snapshot primary trong một request; `asOf` không cố định các request phân trang sau. Dùng sort theo `07` và tie-break theo transfer ID. Xem contract `05` §12.3. Ledger cung cấp evidence cho recovery, tách khỏi history.

| Điểm mở / owner | Ảnh hưởng |
|---|---|
| MOD-OI-08: API/Transfer/Account/Security/DB review Account projection, staff scope và query plan | Blocked history integration/authorization khi thiếu provider thật. |
| Account/Ledger/DB/Audit/BA xác nhận opening provenance, storage và dữ liệu seed | Blocked chứng minh balance toàn hệ thống; không tự tạo entry không có transfer. |
| MOD-OI-02/03/04, DEC-TRF-003/004/006: BA/Transfer/Ledger/Audit/Ops review failure, recovery và replay | Blocked release money path khi chưa chứng minh transaction/fence/evidence. |
| DB/Account/Transfer chốt migration order, FK, quyền/trigger và evidence per-transfer | Blocked migration/integration; không sửa migration của owner khác. |

## 7. Kiểm thử và nghiệm thu

Theo `09` và `00` §11: unit/contract test validation, pair, DTO và lỗi; PostgreSQL thật với migration production, user/role thật và nhiều connection cho constraint, append-only, rollback sau từng insert, audit failure, same source, A→B/B→A, insufficient và deadlock/timeout. D2 test same key/payload, conflict, replay và crash; D3 test PENDING/partial/unknown commit, fence và recovery, evidence đủ/thiếu/lệch; test security IDOR, staff scope, masking và pagination của PENDING/FAILED/INTERNAL. Assert cả transfer, account, ledger, limit, audit, idempotency và outbox, bao gồm tổng tiền và snapshot. D5 theo `09` có mục tiêu 20 RPS × 10 phút; chỉ công bố sau đo trên môi trường phù hợp. Test fake chỉ chứng minh consumer contract; integration thật cần provider, datasource, transaction và PostgreSQL thật, migration sạch/nâng cấp, QA/Security/Ops evidence và review.
