# BE-LED-01 — Chốt boundary, contract và quyết định mở của Ledger

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-LEDGER-01`
> **Ưu tiên / mốc:** P0 / W4 dự kiến
> **Trạng thái:** Baseline thiết kế đã ghi; chờ review liên module và nghiệm thu
> **Owner:** Phương — Ledger
> **Phối hợp / review:** Transfer, Account, DB, Architect, API/FE, BA/Product, Identity, Limit, Audit, Security, QA và Ops theo từng đầu việc
> **Phụ thuộc:** các nguồn chính thức, quyết định Transfer và contract trong `design/05`; provider và evidence thật theo từng AC

## 1. Mục tiêu và phạm vi

Tạo hồ sơ có thể review để các owner thống nhất quyền sở hữu, giao tiếp, transaction và oracle của Ledger trước khi ghép money path và history. Đầu ra gồm matrix contract, danh mục quyết định/rủi ro, vector kiểm thử và kế hoạch integration. Baseline phát triển độc lập được ghi trong DEC-LED-001–003. Xem §8 để biết điều kiện nghiệm thu liên module.

Phạm vi là `postDoubleEntry`, evidence/reconciliation, ranh giới với Account và Transfer, read composition history, quyền truy cập, schema và opening balance. Idempotency, khóa số dư, limit, audit và recovery thuộc owner tương ứng; task này làm rõ contract của Ledger với họ, không nhận ownership thay. Đầu ra của task gồm thiết kế và fixture contract test-only. BE-LED-02–09 triển khai và kiểm chứng schema, provider, API và vận hành.

## 2. Nguồn và hiện trạng

- [[digitalbanking-docs/docs-common/03. SRS.md]] FR-015–022, FR-027–030, FR-034/038/039, AC-006–013/015/016/018/023; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.3–4.4; [[digitalbanking-docs/docs-common/06. System architecture.md]] §6–8.
- [[digitalbanking-docs/docs-common/07. API contracts.md]] §5, §8.4 và [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]; [[digitalbanking-docs/docs-common/09. Test plan.md]] D1–D3/D5.
- [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §3, §7–9, §12.3, §13–14; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]]; [thiết kế Ledger](../design.md) và [backlog](README.md).
- [[DECISIONS.md]] DEC-TRF-003/004/006–009/014/015 và các bổ sung mới nhất. Các quyết định này là baseline thiết kế có thẩm quyền trong phạm vi đã ghi, không thay review/evidence của owner.

Thứ tự nguồn `docs-common/01–11` → `design/` → `docs-backend/`; `07` là nguồn ngữ nghĩa API. Nếu các nguồn cùng cấp mâu thuẫn, ghi rõ và chuyển owner phân xử, không tự suy ra sign-off. `04` §4.4.2 là baseline schema. Đã bỏ hai cột `reference` và `counterparty_account_id` khỏi overview; history lấy qua Transfer projection và Account facts.

Rà soát working tree ngày 18/11/2026: provider posting `banking.ledger` (`LedgerPosting`, `LedgerPostingService`, `LedgerEntryPersistenceAdapter`) và candidate schema `db/ledger-candidate/V2__ledger_entry.sql` đã được triển khai và kiểm chứng độc lập ([BE-LED-02](BE-LED-02.md), [BE-LED-03](BE-LED-03.md), `DEC-LED-005`); `banking.account` chưa có source production; `TransferSettlementCandidate.postAndVerifyBalancedLedger` là seam consumer candidate bên phía Transfer, chờ đấu nối với `LedgerPosting` trong `BE-LED-04`. `SecurityFilterChainConfig` đã có matcher GET transactions nhưng matcher không chứng minh ownership. Migration production chuẩn hóa theo chuỗi `V1`–`V10` (`DEC-COM-001`), trong đó `ledger_entry` mang phiên bản `V8` sau `account` (`V6`) và `transfer` (`V7`).

## 3. Đầu việc và tiêu chí nghiệm thu

| ID | Đầu việc | Đầu ra và AC cần xác nhận |
|---|---|---|
| LED-01.1 | Đối chiếu yêu cầu và phân quyền write-owner. | Matrix §4 có owner, consumer, source và mục tiêu FR/AC; Ledger chỉ ghi ledger, Account ghi balance, Transfer điều phối. Owner/Architect review ranh giới và dependency graph không cycle. |
| LED-01.2 | Chốt posting contract và invariant. | Provider/consumer review input/output/nullability, precondition, transaction, lỗi, correlation và `balanceAfter`; chứng minh oracle đúng 2 entry, không thừa/lệch, kiểm đồng bộ trước audit theo DEC-TRF-009; không diễn giải unique là idempotency. |
| LED-01.3 | Chốt schema, lifecycle và migration dependencies. | DB/Account/Transfer review FK, kiểu tiền, seq/type, constraints, index, append-only và version/order migration; ghi drift `04`/overview; không chọn deferred constraint trigger MVP theo DEC-TRF-009. |
| LED-01.4 | Chốt history composition và authorization. | API/Transfer/Account/Ledger/Security chọn owner, projection, scope, masking, stable pagination/count và freshness; API-TXN-01/02 trả các trạng thái trong `07`, kiểm INTERNAL và cross-customer. Cập nhật contract sau quyết định. |
| LED-01.5 | Chốt opening balance và reconciliation. | Account/Ledger/DB/BA xác nhận nguồn hình thành balance, baseline, snapshot, backfill/seed và evidence; xác định ảnh hưởng `transfer_id NOT NULL`, audit/migration và công thức đối soát. Không khẳng định tổng bằng 0 khi thiếu baseline. |
| LED-01.6 | Review failure, idempotency, recovery và audit. | Transfer/Account/Ledger/Limit/Audit/QA/Ops thống nhất vector trước/sau T1, failure trong T2, unknown commit và per-transfer evidence theo DEC-TRF-003/004/006–009/014; có owner và gate khi thiếu proof. |
| LED-01.7 | Lập contract suite, integration plan và handoff. | Test vectors §7 được provider/consumer review; phân biệt fake test-only và provider/PostgreSQL thật, chỉ rõ lệnh/evidence, owner, blocker và task downstream. Quyết định được người có thẩm quyền đưa vào `DECISIONS.md`, nguồn liên quan đồng bộ. |

## 4. Matrix giao tiếp cần review

Các port dưới đây dùng baseline thiết kế v1 tại `05` §7–9/§12.3/§13.4. Chữ ký Java và wiring chờ review theo từng owner. Value object/DTO immutable thuộc provider; không đưa entity, connection hay raw secret qua boundary.

| ID / provider → consumer | Input → output tối thiểu | Transaction, quyền và lỗi | Cần chốt / evidence |
|---|---|---|---|
| TRFC-LE-01 Ledger → Transfer | `PostingCommand(transferId, sourceId, destinationId, amount, currency, sourceBalanceAfter, destinationBalanceAfter, correlationId)` → debit/credit entry ID | JOIN T2, cùng datasource/transaction manager; fail fast khi không có TX; 2 entry seq 1 DEBIT nguồn, seq 2 CREDIT đích, cùng amount/currency. Duplicate/partial/mismatch hoặc DB error lan ra rollback; không tự ghi Account. | Provider/consumer duyệt DTO, validation, exception taxonomy, propagation và kiểm đồng bộ trước audit; test rollback thật. |
| TRFC-LE-02 Ledger → Transfer recovery | `transferId` + SYSTEM context → `Optional<LedgerEvidence>`, từng entry immutable gồm entryId, seq, type, accountId, amount, currency, balanceAfter và correlationId | Read primary trong recovery transaction theo §13.4. Empty sau đọc thành công khác exception; thiếu/thừa/lệch là incident khi đối chiếu intent. Không sửa ledger. | Chốt interface và linkage/correlation cần đối chiếu với intent, Account và Audit; test absent/partial/duplicate/mismatch/error. |
| TRFC-AC-02–05 Account → Transfer / composition | Ownership/masked projection; 2 IDs → snapshots đã lock; debit/credit → balance sau | Read ownership theo scope; money JOIN T2, lock account ID ASC; conditional debit kiểm affected rows và balance khả dụng. Status/currency/ownership kiểm lại dưới lock; technical failure rollback. | Account/Transfer chốt kết quả balance và transaction binding, không cache snapshot sau commit; test real multi-connection. |
| History v1 (đề xuất) Transfer → HTTP adapter | `VerifiedScope`, filters/page hoặc reference → projection theo §12.3 | Primary, scope trước paging/count; gồm PENDING/FAILED/COMPLETED; Account cấp masked view và ownership facts. | DEC-LED-002; Account projection, staff scope và semantics staff cần owner review. |
| Opening balance v1 (đề xuất) Account → reconciliation | accountId + SYSTEM context → opening amount/currency/effectiveAt/evidenceId | Read primary trong snapshot dùng cho đối soát, baseline có provenance trước bút toán đầu tiên. | DEC-LED-003; Account/DB/Audit/BA duyệt evidence, storage và chính sách legacy/seed. |
| Identity/Common → HTTP | Principal server-side, permission, correlation và Problem | Session/permission tại security chain và object scope tại service/read; không nhận actor tùy ý từ body. | Identity/Security/API xác nhận staff scope, denial và test IDOR. |
| Limit/Audit → Transfer | Reservation/usage evidence và C1/C2 audit/evidence theo `05` §10–11/§13.4 | JOIN khi T2 cần; audit C2 rồi C1 ở pha cuối sau business flush theo DEC-TRF-007; lỗi audit bắt buộc rollback. | Review lock order, rollback, version và bằng chứng per-transfer; Ledger không gọi thay orchestrator. |

JOIN là tham gia transaction của caller, không `REQUIRES_NEW` hoặc commit riêng. Không network dưới lock; không import entity/repository private hoặc tạo vòng module. DTO tiền dùng `BigDecimal` tối đa 2 chữ số thập phân và 17 chữ số nguyên, VND; ID/Instant/actor theo `05` §3/§13.4. Thay đổi signature hoặc semantics sau khi chốt phải theo quy trình compatibility §13.

## 5. Quyết định, rủi ro và gate

| ID | Nội dung, phương án / trạng thái | Đầu mối | Ảnh hưởng |
|---|---|---|---|
| LED-OI-01 / MOD-OI-08 | **Baseline DEC-LED-002:** Transfer-owned projection trên primary, Account cung cấp facts và masked DTO, scope trước count/page. Xem `05` §12.3 cho sort/tie-break, snapshot/asOf và staff. | Transfer, Account, API, Security, DB, Architect | BE-LED-05 chờ provider, scope policy và integration review. |
| LED-OI-02 | **Baseline DEC-LED-003:** Account cung cấp opening balance bất biến có provenance trước bút toán đầu tiên. Seed/legacy thiếu evidence là incident, không suy từ số dư hiện tại. | Account, Ledger, DB, Audit, BA | BE-LED-06 chờ provider, nguồn evidence và bằng chứng trên snapshot thật. |
| LED-OI-03 | **Đã có lựa chọn thiết kế, chờ review/evidence:** DEC-TRF-009 kiểm đủ cặp đồng bộ trước audit; không chọn deferred constraint trigger MVP. Quyền/trigger chặn UPDATE/DELETE là cơ chế append-only khác, cần DB review. | Ledger, Transfer, DB, Audit | BE-LED-02–04; chứng minh invariant và audit-lock order trên PostgreSQL. |
| LED-OI-04 / MOD-OI-02–04 | DEC-TRF-003/004/006 chọn semantics failure/replay/recovery; source drift và review/evidence provider còn mở. Chưa biết commit hoặc proof thiếu: giữ nguyên trạng thái thực tế, alert/manual. | Transfer, Account, Ledger, Audit, QA, Ops | Block release money path cho đến khi protocol và test được chấp nhận. |
| LED-OI-05 | Thứ tự migration/FK và DB roles phải phối hợp; hai migration V2 đang hiện diện cần DB owner phân xử, không tự sửa migration owner khác. | DB, Account, Transfer, Identity, Audit | Block migration Ledger khi Flyway chain và version chưa được xử lý. |
| LED-OI-06 / MOD-OI-09 | Baseline v1 DEC-LED-001 giữ TRFC-LE-01/02, chốt validation, binding, correlation và đọc evidence. Error nội bộ phân biệt invalid input/transaction, invariant và persistence. | Ledger, Transfer, Architect, QA | Chữ ký Java, rollback và compatibility chờ review và provider thật. |

Khi có quyết định, ghi ngày, người quyết định, phạm vi, lựa chọn và phương án thay thế, lý do, nguồn, ảnh hưởng và review còn thiếu vào `DECISIONS.md`; đồng bộ `05` và nguồn chính thức theo thẩm quyền. Chưa có câu trả lời thì giữ Open, không ghi quyết định giả hoặc đánh dấu reviewer đã duyệt.

## 6. Trình tự thực hiện và bàn giao

Baseline rà soát tại `dev2@9d96c96` và working tree ngày 17/11/2026. Provider Ledger/Account và FK production tương ứng chưa có. DB owner xử lý duplicate V2 bằng migration plan có review về schema history trên clean install và upgrade, sau đó phối hợp Account/Transfer chọn version cho BE-LED-02. Thiết kế schema giữ FK RESTRICT, unique transfer/seq, CHECK seq/type và money/currency, cùng indexes theo `04`. BE-LED-02 kiểm quyền role runtime và cơ chế append-only trên PostgreSQL.

| Bàn giao | Owner tham gia | Điều kiện và evidence |
|---|---|---|
| BE-LED-02 → 03 | Ledger, DB, Account, Transfer | Sau FK và chain Flyway được review, chạy clean/upgrade + role test; chạy lại vector TRFC-LE-01 với provider trả ID và kiểm cặp trước audit. |
| BE-LED-04 → 06/08 | Ledger, Transfer, Account, Limit, Audit, QA, Ops | Chứng minh cùng datasource/manager, rollback/cạnh tranh và fence; inject lỗi sau từng bước, đối chiếu đầy đủ evidence và không re-post. BE-LED-06 xác minh opening provenance và snapshot. |
| BE-LED-05/07 | Transfer, Account, Identity/Common, API, Security, Ops | Scope/masking/provider projection, query plan/count, HTTP, C3 và observability; thiếu dependency dùng fake test-only. BE-LED-05 phối hợp với Transfer theo DEC-LED-002. |
| BE-LED-09 | QA, Security, Ops và các owner | Review AC và evidence D1–D5 theo `09` trước nghiệm thu. |

Trace: FR-019/020, AC-007/008/012 → TRFC-LE-01, T-CON/D1 và rollback D3. FR-022, AC-013 → TRFC-LE-02 và T-REC/D3. FR-027–030/039, AC-015/016/023 → API-TXN, scope và masked view. FR-034/038, AC-018/022 → audit C1/C3, trace và failure injection. D2 dùng Transfer idempotency theo DEC-TRF-004.

## 7. Oracle kiểm thử và bằng chứng cần thu

| Vector | Kỳ vọng và mức kiểm chứng |
|---|---|
| Posting hợp lệ | Một transfer → đúng DEBIT nguồn seq 1 và CREDIT đích seq 2; cùng amount/currency, balanceAfter đúng kết quả Account và correlation theo contract. Fake kiểm shape; PostgreSQL/provider thật kiểm cùng T2. |
| Sai input / không có transaction | Reject ID trùng, amount không hợp lệ, currency/scale sai hoặc thiếu field; JOIN fail fast ngoài T2, không insert. Contract test và integration provider. |
| Duplicate / một entry / lỗi insert thứ hai | Không biến duplicate thành replay thành công; rollback cả hai entry và thay đổi tiền, usage, terminal/outbox; test failure injection PostgreSQL với provider thật. |
| Cạnh tranh và audit | Hai lệnh cùng nguồn quá số dư, A→B/B→A, status thay đổi sau preflight; nhiều connection kiểm không âm/bảo toàn tổng; audit C1/C2 fail rollback và không business SQL sau audit lock. |
| Idempotency / crash | Cùng actor/key/hash terminal replay; khác actor/hash conflict; nonterminal 409; crash/unknown commit không debit/post hai lần. Kiểm DB, response và fence theo DEC-TRF-004/014. |
| Recovery | Empty sau đọc thành công khác DB error; partial/extra/wrong seq/account/amount/currency/balance là incident. Đủ evidence Account/Ledger/Limit/Audit và response mới xử lý theo DEC-TRF-006; thiếu/lệch giữ nguyên, alert, không post lại. |
| History và bảo mật | PENDING/FAILED vẫn hiện dù không có entry; INTERNAL một dòng; source/destination customer, staff ngoài scope, filter/time/page/count, masking và IDOR theo `07` §8.4. |
| Opening/reconciliation | Đối soát với opening/baseline được duyệt và snapshot nhất quán; kiểm seed khác 0, account không có entry, sai lệch và DB error, không tự chữa số dư. Oracle cuối phụ thuộc LED-OI-02. |

Evidence tối thiểu: revision/branch, tên môi trường và PostgreSQL/role, command, test ID, kết quả, log/artifact đã loại PII/secret, owner và reviewer. Test fake chỉ chứng minh consumer contract; nhiều connection PostgreSQL, provider thật và failure injection mới chứng minh lock/rollback/integration. Không tuyên bố suite đã chạy từ tài liệu thiết kế.

## 8. Thực thi và điều kiện nghiệm thu

Bộ vector nằm tại `digitalbanking-backend/src/test/java/com/simulatedbank/banking/ledger/contract/`. Chạy từ `digitalbanking-backend` với Java 21.

```sh
./gradlew test --tests 'com.simulatedbank.banking.ledger.contract.*' \
  --tests '*TransferSettlementCandidateTest' --tests '*ModuleBoundaryArchTest' \
  --tests '*TransferSettlementCandidatePostgresTest' --tests '*TransferRecoveryCandidatePostgresTest'
```

Fixture proof chỉ dùng trong test. Xem [kết quả kiểm chứng](evidence/BE-LED-01.md) để biết suite, môi trường và các gate còn lại.

- [ ] Matrix §4 có contract version, provider/consumer, API/PR, reviewers và outcome/error/transaction được review.
- [x] LED-OI-01/02 có baseline thiết kế trong DEC-LED-002/003 và contract §9/§12.3; các issue khác có owner và gate theo §5.
- [ ] Oracle §7 được QA và owner duyệt; có kế hoạch integration thật, mock disclosure và liên kết task BE-LED-02–08.
- [ ] Trace FR/AC, evidence, revision và phê duyệt theo từng AC có thể kiểm tra; không dùng việc hoàn thành tài liệu thay cho sign-off.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 17/11/2026 | Tạo task chi tiết, matrix, quyết định mở, oracle và gate; chưa nghiệm thu. |
| v1.1 | 17/11/2026 | Ghi baseline DEC-LED-001–003, executable vectors, owner và bàn giao; chờ nghiệm thu. |
