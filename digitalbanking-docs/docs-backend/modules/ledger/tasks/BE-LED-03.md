# BE-LED-03 — Posting port, domain và persistence

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-LEDGER-03`
> **Ưu tiên / mốc:** P0 / W4 dự kiến
> **Trạng thái:** Provider đã triển khai + evidence candidate (LED-POST-01–14); tích hợp production và nghiệm thu còn Blocked
> **Owner:** Phương — Ledger
> **Phối hợp / review:** Transfer, Account, DB/Architect, Common, Audit, Security và QA theo phạm vi
> **Phụ thuộc:** BE-LED-01–02; TRFC-LE-01 và TRFC-AC-03–05; cùng datasource/transaction manager của T2

## 1. Mục tiêu và phạm vi

Triển khai `ledger.api.LedgerPosting.postDoubleEntry` để Transfer ghi đúng cặp bút toán trong transaction T2 đang mở. Provider kiểm input, ghi DEBIT nguồn seq 1 và CREDIT đích seq 2, đọc lại toàn bộ cặp từ database và chỉ trả hai entry ID khi tất cả invariant khớp. Bất kỳ lỗi ghi hoặc kiểm cặp nào phải làm rollback T2.

Đầu ra gồm immutable API/DTO, domain value objects và rule kiểm cặp, application service có propagation MANDATORY, persistence adapter/mapping và bộ kiểm thử unit/contract/PostgreSQL. Ledger chỉ ghi `banking.ledger_entry`; Account sở hữu số dư và Transfer sở hữu intent, fence, idempotency, terminal state, outbox và điều phối audit. `PostingRef` xác nhận pha posting thành công trong transaction, chưa xác nhận commit hoặc transfer COMPLETED.

Ghép money path, cạnh tranh giữa transfer và rollback toàn bộ provider thật thuộc BE-LED-04/08. Public `LedgerQuery.findEvidence`, recovery và đối soát thuộc BE-LED-06; truy vấn nội bộ kiểm cặp trong task này không thay API evidence đó. History thuộc Transfer theo DEC-LED-002. Task không thêm REST endpoint, production stub, migration Account/Transfer hoặc cơ chế sửa/xóa bút toán.

## 2. Nguồn và hiện trạng

- [[digitalbanking-docs/docs-common/03. SRS.md]] FR-019/020, DIR-005/006, AC-007/008/012/034; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4.2: schema và invariant.
- [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §3–4, §7–9, §13.3–13.4: TRFC-LE-01, facts Account và JOIN; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]: T2, failure và audit cuối.
- [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §3–6, §8, §11; [[digitalbanking-docs/docs-common/07. API contracts.md]] §5/§8: error mapping do caller/Common sở hữu; [[digitalbanking-docs/docs-common/09. Test plan.md]] D1–D3 và yêu cầu evidence.
- [[DECISIONS.md]] DEC-LED-001/004, DEC-TRF-003/006/007/009/014/015; [thiết kế Ledger](../design.md), [BE-LED-01](BE-LED-01.md), [BE-LED-02](BE-LED-02.md) và [evidence schema](evidence/BE-LED-02.md). Thứ tự thẩm quyền: `docs-common/01–11` → `design/` → `docs-backend/`.

**Rà soát working tree 17/11/2026:** chưa có provider Java production Ledger/Account. Đã có `LedgerContractOracle` trong test và SQL `db/ledger-candidate/V2__create_ledger_entry.sql`; SQL dùng fixture FK Account/Transfer và chưa nằm trong chain production. Transfer có seam `TransferSettlementCandidate.Ports.postAndVerifyBalancedLedger`, chưa phải adapter gọi Ledger thật. Trước implementation phải rà lại source, migration và wiring để tránh ghi đè công việc đang có.

BE-LED-02 đã ghi nhận PostgreSQL ép `1.235` thành `1.24` khi insert vào `NUMERIC(19,2)`, và schema cho phép commit một entry hoặc cặp lệch amount nếu từng dòng hợp lệ. Vì vậy validation trước ghi và kiểm toàn bộ cặp sau ghi là hai phần bắt buộc của task; không lấy schema test thay provider test.

## 3. Dependency và cách tiếp tục độc lập

| Dependency / owner | Facts cần bàn giao | Phương án kiểm thử và gate |
|---|---|---|
| BE-LED-01 / Ledger, Transfer, Architect | Baseline TRFC-LE-01, validation, transaction/error và kiểm cặp. | Dùng vector hiện có làm oracle; chữ ký Java và adapter consumer cần review trước ghép. |
| BE-LED-02 / DB | DDL, generated IDs, quyền runtime SELECT/INSERT, trigger append-only và chain migration. | Dùng chính SQL candidate với fixture FK test-only trên PostgreSQL 16; production fresh/upgrade vẫn Blocked đến khi chain thật sẵn sàng. |
| Account → Transfer (`05` §7/§13.3) | Account đã lock, debit/credit đã áp dụng, hai `ledgerBalanceAfter` trong cùng T2. | Fixture test-only truyền đúng các facts qua `PostingCommand`; Ledger không gọi Account hoặc tính lại số dư. Full rollback money cần provider thật ở BE-LED-04. |
| Transfer → Ledger (`05` §8–9) | Intent đã lock/fence: IDs, amount, currency và correlation bền; caller mở T2. | Consumer fixture mở transaction thật rồi gọi Spring bean Ledger qua proxy; chưa có Transfer thật thì chỉ công bố component evidence. |
| Common / DB | Cùng primary datasource và transaction manager, mapping lỗi và correlation convention. | Test đúng manager và runtime role; không tạo transaction manager riêng cho Ledger hoặc dùng migrator để chạy posting. |
| Audit/Limit → Transfer (`05` §10–11) | Business flush trước audit cuối, rollback chung khi lỗi. | Inject lỗi caller sau posting để kiểm Ledger rollback; audit/usage fixture không thay integration provider thật. |

Task dùng dependency đã mô tả trong Modules contract, không thêm provider call từ Ledger. Thiếu module thì dùng mock/fake **chỉ trong test** theo contract. Không đăng ký fake thành bean production hoặc bỏ FK để chạy được. Nếu phát hiện dependency/semantics chưa có trong tài liệu, nêu phương án qua API của owner, hỏi người điều hành khi chưa rõ, ghi quyết định vào `DECISIONS.md` và bổ sung `05` trước khi ghép theo AGENTS.md.

## 4. Đầu việc và đầu ra

| ID | Công việc | Đầu ra / tiêu chí |
|---|---|---|
| LED-03.1 | Rà contract và hiện trạng. | Chốt danh sách field, nguồn snapshots, propagation, manager và consumer adapter; giữ gate production của BE-LED-02. |
| LED-03.2 | Tạo API và domain. | Interface/records immutable; domain thuần Java kiểm tiền, IDs, correlation và pair; không lộ JPA entity/repository. |
| LED-03.3 | Tạo application service. | Implements port, MANDATORY qua Spring proxy, validate trước ghi, điều phối insert/flush/read/verify; lỗi propagate rollback. |
| LED-03.4 | Tạo persistence adapter/mapping. | Khớp DDL candidate, identity IDs, append-only, đọc đầy đủ pair trên cùng transaction; Hibernate validate với mapping thật. |
| LED-03.5 | Kiểm unit, contract và PostgreSQL. | LED-POST-01–14 ở §9 có kết quả và giới hạn; không dùng H2/mock để chứng minh rollback hoặc propagation. |
| LED-03.6 | Bàn giao. | API/provider diff, test report, mapping/schema evidence và consumer mapping cho BE-LED-04; reviewer và blocker theo từng AC. |

## 5. Hợp đồng posting

### 5.1. Interface và DTO

Hình dạng Java dưới đây hiện thực baseline v1 tại `05` §9; vị trí DTO trong `ledger.api.dto` theo conventions. Review signature với Transfer trước khi wiring, không đổi semantics hoặc thêm field bắt caller tự suy ra.

```java
public interface LedgerPosting {
    PostingRef postDoubleEntry(PostingCommand command);
}

public record PostingCommand(
        long transferId,
        long sourceId,
        long destinationId,
        BigDecimal amount,
        String currency,
        BigDecimal sourceBalanceAfter,
        BigDecimal destinationBalanceAfter,
        String correlationId) {}

public record PostingRef(long debitEntryId, long creditEntryId) {}
```

Tất cả field bắt buộc; `command == null` bị từ chối. `PostingRef` chỉ chứa hai ID dương, khác nhau và ánh xạ đúng entry DEBIT/CREDIT đã đọc lại. Không trả entity, lazy proxy, connection hoặc boolean thành công thiếu định danh. Bean Validation nếu dùng chỉ hỗ trợ kiểm hình thức; lời gọi nội bộ vẫn phải thực thi domain validation.

### 5.2. Validation trước mọi INSERT

| Input | Rule và biên |
|---|---|
| `transferId`, `sourceId`, `destinationId` | Java `long > 0`; source khác destination. Ledger không dùng ID dương làm bằng chứng ownership, status hoặc fence. |
| `amount` | `BigDecimal` không null, `signum() > 0`, `scale() <= 2`, tối đa 17 chữ số nguyên; lớn nhất `99999999999999999.99`. Không `double`/`float`. |
| Hai `balanceAfter` | Cùng quy tắc biểu diễn tiền, nhưng cho phép 0; không âm. Đây là **ledger balance sau** Account trả, không phải available balance, opening balance hoặc số dư đọc ở preflight. |
| `currency` | Chính xác `VND`, case-sensitive. Không tự upper-case, trim hoặc mặc định khi caller gửi null. |
| `correlationId` | `[A-Za-z0-9._:-]{1,64}`; dùng correlation đã lưu cùng intent. Không tự sinh lại từ request retry/recovery. |

Theo baseline scale ≤ 2, `1`, `1.0`, `1.00` đều hợp lệ; `1.000` và `1.235` đều bị từ chối **trước** chuẩn hóa/ghi, kể cả có thể bỏ trailing zero mà không đổi giá trị. Với biểu diễn số mũ, kiểm số chữ số nguyên bằng phép tính an toàn tương đương `(long) precision - scale <= 17`, không chỉ `precision <= 19`. Sau validation có thể chuẩn hóa lưu ở scale 2 chính xác, không rounding; so sánh tiền bằng `compareTo`, không `equals`. Không dùng `stripTrailingZeros` hoặc `setScale` để làm input sai trở nên hợp lệ trước validation.

Ledger kiểm shape và đối chiếu dữ kiện được truyền; không tự xác minh actor/ownership hoặc truy vấn private intent/Account. Transfer phải lấy facts từ intent và hai kết quả Account trong cùng T2 theo §3. Sai facts có thể tạo cặp tự cân bằng nhưng sai nghiệp vụ; BE-LED-04 phải chứng minh mapping consumer với provider thật.

## 6. Package và persistence

| Package dưới `com.simulatedbank.banking.ledger` | Thành phần dự kiến / trách nhiệm |
|---|---|
| `api`, `api.dto` | `LedgerPosting`, `PostingCommand`, `PostingRef`; immutable, không annotation persistence. |
| `domain` | Value objects tiền/entry và rule kiểm cặp; Java thuần, không Spring/JPA hoặc SQL. Không import test oracle vào production. |
| `application` | `LedgerPostingService` implements API, transaction expectation và điều phối domain/persistence. |
| `application.port` | Port persistence nội bộ cho append, flush và đọc cặp; không phải public cross-module API. |
| `adapter.out.persistence` | Entity/mapping, Spring Data repository và adapter; SQL/native projection nếu cần nằm tại đây. Không cung cấp API sửa/xóa entry. |

Tên class nội bộ có thể điều chỉnh khi implementation; ranh giới 5 lớp và dependency theo conventions giữ nguyên. Mọi module khác chỉ import `ledger.api.*`; Ledger không import `transfer.api`, Account repository hoặc entity của provider khác. Không cần HTTP controller cho task này.

Mapping phải khớp toàn bộ cột trong BE-LED-02 §5: `entry_id` dùng IDENTITY, không tự cấp ID hay đòi hai ID liên tục; `transfer_id`/`account_id` là scalar IDs, không quan hệ JPA cross-module. `entry_seq` ánh xạ SMALLINT; type lưu chuỗi DEBIT/CREDIT; tiền precision 19, scale 2; currency CHAR(3); correlation VARCHAR(64). `created_at` dùng TIMESTAMPTZ/`Instant` với default DB `now()`, không thêm `updated_at`, version hoặc soft delete. ID/timestamp không thay bằng chứng thứ tự commit.

App chỉ SELECT/INSERT theo BE-LED-02. Entity/repository nằm trong adapter, không expose `save`/`delete` ra boundary nghiệp vụ; không dùng merge/upsert có thể sửa entry đã có. Nếu dùng JDBC/native cho đường đặc thù phải giữ cùng transaction-bound connection của Spring và mapping thật theo conventions; không mở connection autocommit độc lập. Hibernate `ddl-auto=validate` phải chạy với mapping Ledger thật và schema Flyway; không dùng `create`/`update`. Kết quả validate trên candidate chỉ chứng minh mapping candidate, chưa đóng gate chain production.

## 7. Transaction và thuật toán

### 7.1. Điều kiện transaction

Application service khai báo `@Transactional(propagation = Propagation.MANDATORY)` với transaction manager của T2. Gọi từ consumer qua Spring proxy; không self-invocation hoặc tự `new` service để kiểm propagation. Không có transaction phải fail trước SQL ghi. Caller phải cung cấp transaction read-write trên primary; read-only hoặc manager khác không phải ngữ cảnh posting hợp lệ và cần negative test, không để mở transaction mới ngầm.

Ledger tham gia T2 hiện có, không commit, không `REQUIRES_NEW`, không async, không retry từng insert và không network. Không tạo thêm khóa Account/Transfer hoặc gọi Audit. Transfer chịu trách nhiệm order guard → transfer/fence → accounts ASC → usage; sau posting tiếp tục business writes/flush rồi audit cuối theo DEC-TRF-007. MANDATORY chứng minh có transaction, tự nó không chứng minh caller đã lấy đúng lock/fence hoặc đang ở đúng pha T2.

### 7.2. Trình tự bắt buộc

1. Kiểm transaction expectation và toàn bộ command; tạo domain representation hợp lệ trước INSERT đầu tiên.
2. Append DEBIT: transferId, accountId = sourceId, seq 1, type DEBIT, amount/currency, `balance_after = sourceBalanceAfter`, correlation của intent; nhận generated ID.
3. Append CREDIT: cùng transferId/amount/currency/correlation, accountId = destinationId, seq 2, type CREDIT, `balance_after = destinationBalanceAfter`; nhận generated ID.
4. Flush để cả hai INSERT và constraint đã chạy trong transaction hiện tại. JPA IDENTITY có thể insert sớm; không giả định chỉ `flush()` mới ghi SQL.
5. Đọc **tất cả** entries của transfer từ database trên cùng transaction, `ORDER BY entry_seq, entry_id`. Không chỉ lấy hai ID vừa tạo, lọc bỏ dòng lệch, `LIMIT 2`, dùng count/sum đơn lẻ hoặc kiểm lại hai object đang giữ trong persistence context. Projection phải quan sát dữ liệu database sau flush, không query cache/replica.
6. Chạy rule §7.3 trên kết quả đọc và generated IDs. Nếu khớp mới trả `PostingRef`; nếu không, ném lỗi invariant làm rollback transaction.

Không cần “select rồi insert” để coi cặp tồn tại là replay. Nếu transfer đã có bất kỳ entry, insert trùng seq sẽ fail hoặc kiểm cặp phát hiện bất thường; không lấy ID cũ trả success. Unique là lớp bảo vệ, idempotency/replay/fence vẫn thuộc Transfer.

### 7.3. Rule kiểm cặp sau ghi

- Có đúng 2 entries, cùng transferId của command; hai entry ID dương, khác nhau và khớp generated IDs ở đúng phía.
- Entry đầu seq 1/type DEBIT/sourceId; entry sau seq 2/type CREDIT/destinationId, source khác destination.
- Amount mỗi entry hợp lệ và bằng command; currency mỗi entry chính xác VND và bằng command. Tổng có dấu `−DEBIT + CREDIT = 0`.
- Hai `balance_after` hợp lệ và bằng snapshot ledger balance tương ứng trong command; correlation mỗi entry bằng correlation intent đã truyền.
- Thiếu, thừa, trùng, đổi phía, ID sai hoặc field mismatch đều fail. Count bằng 2 hoặc tổng bằng 0 riêng lẻ không đủ.

Kiểm cặp phải xong trước khi provider trả và trước pha audit cuối; không để Ledger còn deferred write lúc audit lấy advisory lock. Provider không chứng minh terminal status bằng cách đọc bảng Transfer. Invariant COMPLETED ↔ pair và rollback toàn money path được ghép/kiểm ở BE-LED-04/08.

## 8. Lỗi và rollback

| Tình huống | Hành vi bắt buộc |
|---|---|
| Command/field không hợp lệ | Reject trước INSERT, không rounding hoặc default âm thầm. Đây là vi phạm contract nội bộ khi caller đã validate HTTP; không tự tạo public error code mới hoặc blanket-map thành lỗi client. |
| Thiếu transaction / transaction không phù hợp | Fail trước ghi hoặc fail khi xác minh transaction configuration; không fallback mở transaction riêng. |
| Duplicate transfer/seq, partial sẵn có hoặc cặp đọc lại lệch | Invariant/system error, propagate rollback T2; không replay, không upsert, không xóa partial rồi post lại. Unique `23505` của Ledger không phải idempotency conflict 409. |
| FK/CHECK, insert thứ hai, flush hoặc read-back lỗi | Propagate rollback; không trả `PostingRef`, không catch để tiếp tục usage/audit/terminal trong transaction lỗi. |
| Deadlock/lock timeout/statement timeout | Propagate đến caller; retry toàn transaction mới chỉ do Transfer theo protocol an toàn và có giới hạn. Không retry statement trong TX đã aborted. |
| Caller/audit thất bại sau khi Ledger trả | Hai entries rollback cùng T2; không bù bằng DELETE/UPDATE. Terminal state và response do Transfer xử lý. |
| Commit outcome chưa rõ | Ledger không suy ra rollback hoặc re-post. Transfer/recovery đọc primary và evidence theo `05` §13.4, giữ thực trạng/alert khi chưa đủ proof. |

Phân biệt invalid-contract, transaction, invariant và persistence trong exception/cause để caller quan sát được; chọn kiểu exception cụ thể phù hợp Common khi implementation. Mọi lỗi posting/invariant phải làm transaction rollback-only khi đi qua service proxy, kể cả mismatch phát hiện thuần Java sau hai insert. Dùng unchecked exception hoặc rollback rule tường minh; không trả failure result khiến caller vô tình commit. Test cả trường hợp caller catch lỗi rồi cố commit. Với partial đã commit từ trước trong fixture, rollback không xóa dữ liệu lịch sử đó; chỉ bảo đảm không có thay đổi mới được commit.

Reuse Common error mapper và `07`; lỗi invariant/unique Ledger là hệ thống (`500 INTERNAL_ERROR`), lỗi khả dụng/timeout theo mapping 503 đúng ngữ cảnh. Provider không dựng HTTP response. Không log toàn command, entity, số dư, full account, raw key hoặc SQL/secret; dùng correlation và mã lỗi an toàn theo conventions. Audit tài chính do Transfer điều phối, không ghi audit độc lập để che rollback posting.

## 9. Oracle kiểm thử và evidence

Reuse vectors của `LedgerContractOracle` bằng test adapter gọi provider thật; fixture oracle vẫn là test-only. Chia bằng chứng: unit/domain và mock kiểm shape/điều phối; Spring + PostgreSQL 16 kiểm propagation, mapping và rollback; provider thật liên module kiểm full T2 ở BE-LED-04/08. Test PostgreSQL dùng migrator cho DDL và `banking_app` cho posting, chính SQL BE-LED-02 và fixture FK khi predecessor chưa có.

| ID | Kịch bản | Assert / mức evidence |
|---|---|---|
| LED-POST-01 | Command hợp lệ, commit caller. | Đúng 2 rows đầy đủ field §7.3, IDs trả đúng phía; tiền round-trip chính xác, timestamp có giá trị; PostgreSQL/provider. |
| LED-POST-02 | IDs 0/âm, source=destination; command/field null. | Reject trước INSERT; spy kiểm không ghi và PostgreSQL không có entry mới. |
| LED-POST-03 | Amount 0/âm, balance âm; biên 0.01/0, max `99999999999999999.99`, vượt 17 chữ số nguyên. | Amount phải dương, balance 0 hợp lệ; overflow reject trước ghi; unit và provider PostgreSQL. |
| LED-POST-04 | Scale 0/1/2, `1.000`, `1.235`, balance scale >2, biểu diễn `1E+17`. | Chấp nhận đúng baseline; scale >2/overflow reject trước ghi, không DB rounding. Compare `1.0` với `1.00` theo giá trị. |
| LED-POST-05 | Currency null/lowercase/khác VND; correlation rỗng, 64/65 ký tự, ký tự sai. | Positive/negative boundaries; không tự sửa input, không INSERT khi sai. |
| LED-POST-06 | Gọi Spring bean không có transaction; caller read-only; cấu hình manager sai. | MANDATORY fail ngoài TX; ngữ cảnh không phù hợp không commit entry/không tạo TX riêng; test wiring thật. |
| LED-POST-07 | Rollback/throw caller sau posting thành công; inject lỗi sau INSERT thứ nhất và sau INSERT thứ hai. | Sau transaction kết thúc, kết nối mới thấy 0 entries mới ở cả ba điểm; không chỉ dựa vào rollback tự động của test. |
| LED-POST-08 | CREDIT insert/flush/read-back lỗi (FK/CHECK hoặc fault injection test-only). | Exception propagate, DEBIT không commit, caller không nhận PostingRef; PostgreSQL. Fault hook nằm trong test. |
| LED-POST-09 | Post lại transfer đã có cặp; fixture có riêng seq 1 hoặc seq 2. | Fail/rollback, không replay hoặc sửa partial; cặp/dòng đã commit trước giữ nguyên, không thêm entry mới. |
| LED-POST-10 | Read-back thiếu/thừa/trùng, ID không dương/trùng/sai generated ID, seq/type/account sai. | Domain từ chối từng mutation riêng; kiểm rollback sau hai INSERT bằng adapter fault injection + PostgreSQL. Schema không cho tạo corruption nào thì mock projection, ghi rõ giới hạn. |
| LED-POST-11 | Hai rows lệch amount/currency/snapshot/correlation/transferId nhưng count hoặc tổng vẫn hợp lệ. | Rule đầy đủ phát hiện từng mismatch; không chỉ kiểm số lượng/tổng. Provider rollback kể cả caller catch exception rồi cố commit. |
| LED-POST-12 | Hai connection post cùng transfer, writer đầu commit hoặc rollback. | Unique serialize đúng: không có hai cặp; writer sau fail nếu cặp đầu commit, chỉ có thể ghi khi writer đầu đã rollback. Dùng barrier/latch, assert DB sau commit; không coi đây là proof fencing T2 thật. |
| LED-POST-13 | Mapping/roles và append-only. | Hibernate validate Ledger thật trên candidate; runtime insert/select được, UPDATE/DELETE/TRUNCATE vẫn bị chặn; chạy lại schema suite. Production mapping/chain vẫn có gate riêng. |
| LED-POST-14 | Consumer truyền snapshots và audit failure sau posting. | Component fixture kiểm mapping intent/Account → command và rollback khi lỗi caller. BE-LED-04/08 chạy lại với Transfer/Account/Limit/Audit thật, assert balances/usage/status/idempotency/outbox/audit cùng entries. |

Test transaction phải đọc kết quả cuối qua connection/transaction mới sau commit/rollback. Kiểm lỗi sau insert bằng fault injection đồng bộ; không dùng sleep để đoán interleaving. Identity có thể bỏ số sau rollback nên không assert IDs liên tục. Mock không chứng minh propagation, PostgreSQL aborted transaction, unique blocking hoặc rollback.

**Suite hiện có, không phải evidence provider BE-LED-03:** từ `digitalbanking-backend`, Java 21 và Docker sẵn sàng:

```sh
./gradlew test --tests 'com.simulatedbank.banking.ledger.contract.*' \
  --tests '*LedgerCandidateMigrationPostgresTest' --tests '*ModuleBoundaryArchTest'
```

Khi implementation có, bổ sung test class cụ thể cho LED-POST-01–14 và lệnh tái lập vào report `tasks/evidence/BE-LED-03.md`; không coi lệnh trên là đủ kiểm provider. Report phải ghi revision/working-tree scope, Java/PostgreSQL, role/location/manager, command, số tests pass/fail/skip, mapping vector → test, artifacts synthetic và giới hạn fixture. Chưa chạy hoặc thiếu dependency thì ghi Not run/Blocked; không chép kết quả của BE-LED-01/02 thành kết quả task này.

## 10. Nghiệm thu và bàn giao

- [ ] API immutable, domain thuần Java, dependency chỉ qua provider API; Transfer/Ledger review DTO/error/transaction và snapshots.
- [ ] Validation toàn bộ command đạt biên §5; không rounding trước validation, không export entity hoặc tự sinh lại correlation.
- [ ] MANDATORY qua proxy cùng manager/datasource; thiếu TX fail, không commit riêng; generated IDs và mapping khớp DDL.
- [ ] Đúng DEBIT seq 1/CREDIT seq 2; flush/read-back database/kiểm đầy đủ cặp trước trả PostingRef và audit cuối.
- [ ] Duplicate/partial/mismatch/persistence error rollback; rollback sau từng insert và khi caller/audit lỗi có evidence PostgreSQL, kể cả caller catch rồi cố commit.
- [ ] LED-POST-01–14 có evidence và disclosure về fake/fixture/provider thật; mapping candidate validate và BE-LED-02 regression đạt. Chain production chưa có thì gate tương ứng vẫn Blocked.
- [ ] Code review theo conventions/Common, reviewer Ledger/Transfer/DB/QA và Security/Audit theo phạm vi; không dùng việc viết đặc tả để đánh dấu task hoàn thành.

| Bàn giao | Nội dung / điều kiện |
|---|---|
| BE-LED-02 / DB | Mapping thật, identity/role output, Hibernate validate trên candidate; chạy lại chain production fresh/upgrade khi prerequisites được review. |
| BE-LED-04 / Transfer, Account, Limit, Audit | Provider API/bean/manager, field mapping từ intent và ledger snapshots, exception propagation, flush/read-back và failure vectors. Adapter seam Transfer gọi `LedgerPosting`, không callback Ledger → Transfer. |
| BE-LED-06 | Internal pair representation/rules có thể reuse nhưng public evidence phải giữ nguyên partial/mismatch để recovery phân loại; không reuse posting reject rule để giấu dữ liệu lệch. |
| BE-LED-07/08 / QA, Security, Ops | Failure taxonomy, log policy, component evidence và danh sách còn thiếu: money atomicity/fence, provider thật, migration production, D1–D3 và owner review. |

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 17/11/2026 | Viết chi tiết posting contract, domain/persistence, transaction/rollback, LED-POST-01–14 và gate bàn giao. |
| v1.1 | 17/11/2026 | Triển khai provider (api/domain/application/adapter) theo DEC-LED-005; 103 test mới LED-POST-01–14 PASS trên PostgreSQL 16 candidate; regression BE-LED-01/02 + ArchUnit + Transfer candidate xanh. Evidence tại [evidence/BE-LED-03.md](evidence/BE-LED-03.md). Chain production, ghép T2 thật và owner review vẫn Blocked. |
