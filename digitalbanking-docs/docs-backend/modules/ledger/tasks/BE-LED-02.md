# BE-LED-02 — Migration, DB constraints và append-only

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-LEDGER-02`
> **Ưu tiên / mốc:** P0 / W4 dự kiến
> **Trạng thái:** Đã triển khai schema candidate; tích hợp production chờ migration Account/Transfer, xử lý trùng V2 và review
> **Owner:** Phương — Ledger
> **Phối hợp / review:** DB/Architect, Account, Transfer, Identity, Audit, Security, QA và Ops theo phạm vi
> **Phụ thuộc:** BE-LED-01; migration Account/Transfer; Flyway chain và DB roles đã được phối hợp

## 1. Mục tiêu và phạm vi

Tạo schema `banking.ledger_entry` theo `04` §4.4.2 bằng migration forward-only; bảo vệ từng dòng bằng PK/FK/CHECK/UNIQUE, index phục vụ truy vấn và quyền/trigger append-only. Đầu ra phải đủ để BE-LED-03 xây provider posting và BE-LED-04 ghép T2 mà không thay đổi ownership của Account hoặc Transfer.

Task bao gồm SQL migration, phân quyền cho đối tượng Ledger, kiểm thử PostgreSQL, kế hoạch fresh/upgrade và hồ sơ bàn giao. Ledger không tạo bảng production thay Account/Transfer, cập nhật số dư, triển khai history API, recovery, opening balance hoặc seed tiền. Posting service, kiểm đủ cặp và transaction liên module thuộc BE-LED-03–04; evidence/reconciliation thuộc BE-LED-06. Test schema ở đây không thay bằng chứng money path của các task đó.

## 2. Nguồn và hiện trạng

- [[digitalbanking-docs/docs-common/03. SRS.md]] FR-019/020, DIR-005/006, AC-012; [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §3.7, §4.1, §4.3.1, §4.4.1–2, §7–§9: kiểu dữ liệu, FK, constraint, index và tính bất biến.
- [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] §8.1; [[digitalbanking-docs/docs-common/design/04. security.md]] §6.5; [cấu hình triển khai](../../../deployment/configuration.md) §6: Flyway, role và trigger.
- [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §7–9, §13.4; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]; [[DECISIONS.md]] DEC-TRF-007/009/015 và DEC-LED-001: JOIN T2, kiểm cặp trước audit và evidence.
- [BE-LED-01](BE-LED-01.md) LED-OI-03/05, [thiết kế Ledger](../design.md), [backlog](README.md), [BE-TRF-02](../../transfer/tasks/BE-TRF-02.md) và [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]]. Nguồn ưu tiên: `docs-common/01–11` → `design/` → `docs-backend/`.

**Snapshot working tree ngày 17/11/2026:** production location `src/main/resources/db/migration` có V1, hai file V2 khác tên (`create_audit_log`, `create_identity_and_rbac_tables`), V3 và V4. Chưa có migration production Account/Transfer/Ledger hoặc Java provider Account/Ledger. `db/transfer-candidate/V2__transfer.sql` nằm ngoài location Flyway mặc định; version đó chỉ phục vụ candidate, không phải prerequisite production đã được nghiệm thu. Cần rà lại source và `flyway_schema_history` của môi trường đích trước implementation; không dùng snapshot này để chọn số version mới.

`docker/postgres/init/01-create-roles.sql` đã tách `banking_app`/`banking_migrator`, nhưng default privileges hiện cấp `SELECT, INSERT, UPDATE, DELETE` trên bảng tương lai cho app. V1 chỉ tạo schema. Vì vậy, việc có hai credential chưa chứng minh Ledger append-only: migration Ledger cần xử lý quyền hiệu lực trên bảng vừa tạo và kiểm lại sau toàn bộ migration/bootstrap.

## 3. Dependency và cách làm khi chưa có provider

| Dependency / owner | Cần bàn giao | Cách tiếp tục và gate |
|---|---|---|
| BE-LED-01 / Ledger, DB, Transfer | Baseline schema, TRFC-LE-01/02, kiểm cặp trước audit, role/trigger được review. | Chuẩn bị SQL/test theo baseline; chưa coi tài liệu hoặc fixture là sign-off. |
| Account / DB | Migration thật tạo `banking.account(account_id BIGINT PK)` và policy giữ lịch sử. | FK Ledger bắt buộc và RESTRICT. Khi thiếu, fixture test-only theo `04` §4.3.1/`05` §7; không đưa bảng giả vào production. |
| Transfer / DB | Migration thật tạo `banking.transfer(transfer_id BIGINT PK)`, sau prerequisites của Transfer. | FK Ledger bắt buộc và RESTRICT. Dùng fixture test-only theo `04` §4.4.1/`05` §8 nếu thiếu; candidate không thay chain production. |
| DB, Identity, Audit, Account, Transfer | Danh mục version duy nhất, lịch sử đã áp dụng, thứ tự merge/migrate, role owner và quyền hiệu lực. | Duplicate V2 và thiếu predecessor chặn fresh/upgrade production. DB owner lập phương án theo history thực tế; Ledger không tự đổi số/checksum migration owner khác. |
| Security / Ops | Bootstrap role, secret references, hạn chế DDL/role membership, backup/restore và rollout. | Test bằng credential runtime thật; không dùng migrator làm datasource ứng dụng. Không ghi password vào migration/evidence. |
| BE-LED-03 / Ledger | Mapping persistence khớp DDL và vector posting. | Có thể chuẩn bị mapping song song sau review schema; chỉ ghi Hibernate validation Ledger đạt khi mapping thật được kiểm. |

Các dependency giao tiếp đã có trong `05` §7–9/§13.4; task này không thêm public API hoặc thay contract. Nếu phát hiện dependency chưa được mô tả, nêu phương án qua API/DTO của owner và ghi vào `05` để review trước khi ghép. Theo AGENTS.md, thiếu rõ ràng về yêu cầu thì hỏi người điều hành và ghi quyết định vào `DECISIONS.md`; không tự phân xử history/version hoặc ghi reviewer đã duyệt.

## 4. Đầu việc và tiêu chí nghiệm thu

| ID | Công việc | Đầu ra / AC |
|---|---|---|
| LED-02.1 | Đối chiếu schema và migration prerequisites. | Matrix §5 khớp `04`; xác nhận FK targets, phiên bản, ownership, dữ liệu hiện hữu và issue sai lệch với owner. |
| LED-02.2 | Viết migration forward-only cho Ledger. | Version duy nhất sau predecessor thật; tạo bảng, PK/FK/CHECK/UNIQUE/index và hardening theo kế hoạch được review. Không bỏ FK để migration chạy được. |
| LED-02.3 | Bảo vệ append-only bằng quyền runtime và trigger. | App đọc/insert được; UPDATE/DELETE/TRUNCATE và DDL bị từ chối; trigger được kiểm độc lập với ACL. Default/broad grants không mở lại quyền cấm. |
| LED-02.4 | Kiểm schema trên PostgreSQL 16. | Test positive/negative §8, catalog và quyền hiệu lực; các constraint có tên rõ, lỗi không để lại dòng ngoài ý muốn. |
| LED-02.5 | Kiểm migration fresh/upgrade/restart và mapping. | Flyway validate/migrate đạt trên chain production thật; upgrade giữ nguyên dữ liệu; Hibernate `ddl-auto=validate` đạt với mapping được ghép. Không dùng repair/clean để che lỗi. |
| LED-02.6 | Bàn giao và thu evidence. | Report tái lập, SQL/revision, role/trigger output, giới hạn fixture, rollout/khôi phục và owner review; BE-LED-03–04 nhận rõ phần invariant còn ở application. |

## 5. Baseline schema cần triển khai

### 5.1. Cột và kiểu dữ liệu

Theo `04` §4.1/§4.4.2; tất cả cột dưới đây đều `NOT NULL`. Không thêm `reference`, `counterparty_account_id`, `updated_at`, soft delete hoặc cột opening balance vào bảng Ledger.

| Cột | Kiểu / default | Quy tắc |
|---|---|---|
| `entry_id` | `BIGINT GENERATED ALWAYS AS IDENTITY` | PK, DB cấp ID. Không yêu cầu ID liên tục để nhận diện cặp. |
| `transfer_id` | `BIGINT` | FK → `banking.transfer(transfer_id)`. |
| `account_id` | `BIGINT` | FK → `banking.account(account_id)`. |
| `entry_seq` | `SMALLINT` | Chỉ 1 hoặc 2; kết hợp với type theo §5.2. |
| `entry_type` | `VARCHAR(8)` | Chỉ `DEBIT` hoặc `CREDIT`; không dùng PostgreSQL enum. |
| `amount` | `NUMERIC(19,2)` | Lớn hơn 0; không có default. |
| `currency` | `CHAR(3) DEFAULT 'VND'` | Chỉ `VND`. |
| `balance_after` | `NUMERIC(19,2)` | Không âm; snapshot `ledgerBalanceAfter` do Account trả trong T2, không có default. |
| `correlation_id` | `VARCHAR(64)` | Correlation của intent theo `05` §9, không tự sinh correlation mới khi post. |
| `created_at` | `TIMESTAMPTZ DEFAULT now()` | Thời điểm post; ứng dụng dùng `Instant`/UTC. Không suy thứ tự commit từ timestamp hoặc ID. |

Tiền dùng `BigDecimal`, tối đa 17 chữ số nguyên và 2 chữ số thập phân. Validation scale/precision, ID dương và correlation `[A-Za-z0-9._:-]{1,64}` thuộc contract BE-LED-03; kiểm kiểu `NUMERIC(19,2)` không thay việc reject input trước ghi. Đặc biệt, test giá trị nhiều hơn 2 chữ số thập phân phải phân biệt hành vi ép kiểu của DB với yêu cầu application từ chối làm tròn ngầm.

### 5.2. Constraint và index

Tên PK/FK/CHECK bên dưới là tên đề xuất để review SQL; tên index theo `04` được giữ nguyên. Hai FK dùng `ON DELETE RESTRICT ON UPDATE RESTRICT`, không cascade, không nullable và phải được validate trước nghiệm thu.

| Đối tượng | Định nghĩa cần kiểm | Bảo đảm / giới hạn |
|---|---|---|
| PK `ledger_entry` | `PRIMARY KEY (entry_id)` | Mỗi entry có định danh duy nhất. |
| FK transfer | `transfer_id` → `banking.transfer.transfer_id` | Không orphan; không chứng minh status hay amount khớp intent. |
| FK account | `account_id` → `banking.account.account_id` | Không orphan; không chứng minh source/destination, ownership hoặc ACTIVE. |
| `ck_ledger_entry_seq` | `entry_seq IN (1,2)` | Giới hạn số seq. |
| `ck_ledger_entry_type` | `entry_type IN ('DEBIT','CREDIT')` | Giới hạn type. |
| `ck_ledger_entry_seq_type` | `(entry_seq=1 AND entry_type='DEBIT') OR (entry_seq=2 AND entry_type='CREDIT')` | Ràng buộc mapping TRFC-LE-01; cần DB review cùng baseline BE-LED-01. |
| `ck_ledger_entry_amount` | `amount > 0` | Giá trị dương sau chuyển kiểu. |
| `ck_ledger_entry_currency` | `currency='VND'` | Tiền tệ MVP. |
| `ck_ledger_entry_balance` | `balance_after >= 0` | Không âm; không kiểm đúng số dư Account. |
| `uq_ledger_transfer_seq` | `UNIQUE (transfer_id, entry_seq)` | Tối đa hai seq khác nhau; **không bảo đảm tồn tại đủ hai dòng**. |
| `ix_ledger_account_time` | `(account_id, created_at DESC)` | Đọc bút toán theo account/thời gian. |
| `ix_ledger_transfer` | `(transfer_id)` | Evidence per-transfer theo baseline. |
| `ix_ledger_correlation` | `(correlation_id)` | Truy vết theo correlation. |

UNIQUE tạo cấu trúc index phục vụ chính constraint; không tạo thêm index unique trùng. Dù index transfer có phần truy cập trùng với tiền tố unique, giữ baseline `04`; nếu muốn bỏ phải có query-plan evidence và review/cập nhật nguồn. Không thêm index history vào Ledger: history do Transfer sở hữu theo DEC-LED-002.

### 5.3. Invariant còn thuộc transaction/application

DB constraints trên không chứng minh COMPLETED có đủ cặp, PENDING/FAILED không có entry, hai dòng cùng amount/currency, đúng source/destination, đúng balance snapshot hoặc tổng debit bằng credit. Ví dụ, SQL hợp lệ về FK/CHECK có thể commit chỉ seq 1; test schema phải ghi rõ giới hạn đó. Fixture lỗi này chỉ nằm trong database dùng một lần, không tạo trên dữ liệu vận hành.

Theo DEC-TRF-009 và DEC-LED-001, BE-LED-03 kiểm toàn bộ cặp đã ghi trước khi trả thành công; Transfer ghép vào cùng T2 ở BE-LED-04, flush business trước audit cuối theo DEC-TRF-007. Lỗi entry/constraint/invariant lan ra rollback, không được đổi duplicate thành replay thành công. BE-LED-02 không thêm deferred constraint trigger kiểm cặp; trigger append-only ở §6 có mục đích riêng.

## 6. Quyền DB và trigger append-only

### 6.1. Quyền hiệu lực của runtime

- Bảng/function/trigger do migration owner quản lý; `banking_app` không là owner, superuser hoặc thành viên có thể chuyển sang role migrator/owner. Kiểm cả quyền trực tiếp, quyền qua role membership, `PUBLIC` và default privileges.
- Trên `banking.ledger_entry`, chỉ cấp `SELECT, INSERT` cho app; thu hồi `UPDATE, DELETE, TRUNCATE` và grant option không cần thiết. Giữ `USAGE` trên schema và quyền sequence tối thiểu cần cho đường insert identity; test insert thật và không cho app reset/set giá trị sequence.
- Runtime không có CREATE/ALTER/DROP, quyền disable trigger hoặc thay function. Kiểm các quyền này bằng kết nối runtime, không suy từ nội dung GRANT/REVOKE trong file.
- Áp dụng hardening sau khi tạo bảng và sau grant rộng liên quan, trước khi mở traffic. Script init hiện chỉ chạy khi khởi tạo volume; upgrade phải có bước migration/bootstrap được quản lý, không trông chờ init chạy lại. Sau toàn bộ chain, kiểm lại quyền để phát hiện một migration sau cấp rộng trở lại.
- Không sửa quyền hoặc trigger của `audit_log` trong task Ledger; phối hợp DB/Audit nếu thay shared bootstrap/default privileges. Không cấp quyền tạm cho runtime trên môi trường thật để test trigger.

### 6.2. Trigger bảo vệ bất biến

Triển khai trigger chặn `UPDATE`, `DELETE`, `TRUNCATE` theo deployment §6.2 và security design, sau DB/Security review SQL. Function/trigger thuộc Ledger hoặc dùng helper chung đã được owner công bố; không phụ thuộc ngầm vào helper private của Audit. Function chỉ báo lỗi ổn định, không network, không cập nhật account/audit hoặc lấy thêm business lock; tránh quyền nâng cao không cần thiết.

Kiểm trigger độc lập với ACL bằng role test có quyền DML cần thiết trong PostgreSQL disposable, nhưng không sở hữu bảng/DDL và không thể tắt trigger. Assert thao tác có dòng đích bị từ chối, dữ liệu giữ nguyên; `TRUNCATE` bị chặn cả khi bảng rỗng. Runtime thực tế vẫn phải bị ACL từ chối. Ghi SQLSTATE/message do implementation chọn vào report để tái lập, không coi một lỗi permission là bằng chứng trigger đã chạy.

Owner/superuser có khả năng thay đổi cấu trúc là ranh giới quản trị cần Ops kiểm soát; trigger không thay phân tách credential. Không mở đường sửa/xóa Ledger hoặc purge/retention trong task này. Sửa sai nghiệp vụ bằng mô hình điều chỉnh đã được thiết kế riêng, ngoài MVP.

## 7. Trình tự migration và triển khai

1. **Inventory:** chụp revision, danh sách migration/location và schema history/checksum thực tế. DB owner xác định file nào đã phát hành/áp dụng, giải quyết duplicate V2 với Identity/Audit và lập plan fresh/upgrade. Không tự renumber, xóa file, chạy `repair`, sửa history hoặc đặt `outOfOrder` để lách gate.
2. **Prerequisites:** Account/Transfer bàn giao migration thật và PK/FK/delete policy; schema/roles sẵn sàng. Chọn version duy nhất sau predecessor đã thống nhất. Tên dạng `V<N>__create_ledger_entry.sql` chỉ là placeholder, chưa chốt số N hoặc thứ tự theo tên module.
3. **SQL review:** tạo bảng và constraints/index §5, trigger và grants §6 trong thứ tự không để lộ quyền sửa Ledger khi runtime hoạt động. Nếu tách nhiều migration, giữ traffic tắt đến khi toàn bộ hardening hoàn tất. Review lock impact, transaction mode và tương thích với application đang chạy; không dùng `IF NOT EXISTS` để che object sai schema.
4. **Fresh:** migrate database rỗng bằng credential migrator trên PostgreSQL 16, sau đó Flyway validate, catalog/ACL/trigger checks và runtime smoke. Không có seed tiền tự động trong migration versioned.
5. **Upgrade:** migrate từ predecessor thật có dữ liệu synthetic đại diện của các module; kiểm row count/nội dung dữ liệu cũ, FK, quyền, checksum và restart/no-op migrate. Nếu môi trường đã có bảng Ledger ngoài Flyway hoặc dữ liệu legacy, dừng để DB/Ops lập migration/backfill có provenance; không drop/recreate hoặc tự suy opening balance.
6. **Mapping:** chạy Hibernate `ddl-auto=validate` với mapping persistence Ledger được ghép từ BE-LED-03; giữ datasource app và Flyway credential tách biệt. Catalog tests vẫn bắt buộc vì Hibernate validate không thay test trigger/ACL/index/CHECK.
7. **Rollout:** có backup đã kiểm restore theo runbook và owner DB/Ops; triển khai khi các gate đạt. Migration lỗi thì dừng release, giữ log/history và đánh giá trạng thái thực tế. Khôi phục bằng forward migration đã review hoặc restore nhất quán theo runbook; không sửa migration đã chạy, truncate hay chỉnh dữ liệu tài chính để startup thành công.

**Khi prerequisites chưa có:** vẫn viết được test schema độc lập với fixture Account/Transfer theo tài liệu. Dùng test location/harness tách biệt; SQL Ledger ứng viên chưa phát hành phải ngoài Flyway production cho đến khi chain được phối hợp. Chạy chính SQL Ledger được review, không chép một DDL khác vào test rồi coi đó là proof migration. Fixture chỉ tạo facts/PK cần cho FK và seed synthetic; không đăng ký fake production, không bỏ FK. Báo kết quả là component/candidate; fresh/upgrade production và provider integration vẫn Blocked. Khi provider có thật, chạy lại trên chain thật.

### 7.1. Candidate và cách chạy

SQL Ledger nằm ở `src/main/resources/db/ledger-candidate/V2__create_ledger_entry.sql`. `V2` chỉ là version của chain candidate; version production sẽ theo history và predecessor do DB owner xác nhận. `LedgerCandidateMigrationPostgresTest` chọn tường minh các location `classpath:db/ledger-fixture` và `classpath:db/ledger-candidate` trên PostgreSQL 16. Fixture V1 tạo đúng hai PK mục tiêu với ID tổng hợp. Harness dùng script bootstrap role của repo, kết nối riêng cho migrator, runtime và role thử trigger.

SQL gói DDL, trigger và ACL vào cùng migration. Trigger mức statement trả SQLSTATE `55000` với message `ledger_entry is append-only` cho UPDATE/DELETE/TRUNCATE. Identity insert qua bảng hoạt động với SELECT/INSERT và không cần quyền truy cập sequence trực tiếp. Kiểm quyền hiệu lực sau GRANT/REVOKE cũng bắt cấu hình role kế thừa quyền cấm. Hai CHECK tiền từ chối thêm PostgreSQL `NaN`.

## 8. Oracle kiểm thử PostgreSQL

Chạy trên PostgreSQL 16 thật bằng Testcontainers hoặc database disposable; không thay bằng H2. Mỗi ca lỗi dùng transaction mới hoặc savepoint test được quản lý để không assert trên transaction đã abort. Đọc lại bằng connection khác sau commit/rollback. Chạy `./gradlew test --tests "*LedgerCandidateMigrationPostgresTest"` từ backend và xem [báo cáo](evidence/BE-LED-02.md) để đối chiếu kết quả theo từng oracle.

| ID | Thiết lập / thao tác | Kỳ vọng và evidence |
|---|---|---|
| LED-DB-01 | Catalog sau migration. | Đủ cột/type/precision/scale/null/default, identity PK, FK RESTRICT đã validate, CHECK, unique, index và trigger enabled; owner đúng. |
| LED-DB-02 | Insert cặp hợp lệ bằng `banking_app`, dùng synthetic transfer/accounts có thật; thử balance 0 và amount 0,01. | Hai ID dương khác nhau, seq/type và snapshot/correlation đúng input, default VND/time hoạt động; SELECT được sau commit, không yêu cầu ID liền nhau. |
| LED-DB-03 | Lần lượt NULL từng cột bắt buộc không có default; amount 0/âm, balance âm, currency khác VND, seq 0/3, type sai, seq 1 CREDIT hoặc seq 2 DEBIT. | Từng trường hợp bị constraint tương ứng từ chối; tách test bỏ cột có default với truyền NULL tường minh; không để lại entry lỗi. |
| LED-DB-04 | Money tại giới hạn `NUMERIC(19,2)`, vượt 17 chữ số nguyên, input scale >2; correlation 64/65 ký tự. | Giá trị hợp lệ round-trip chính xác, overflow/length sai bị phát hiện; ghi rõ hành vi DB với scale và chuyển vector reject-before-write cho BE-LED-03. Không khẳng định CHECK chứng minh validation contract. |
| LED-DB-05 | Transfer/account không tồn tại; thử xóa/đổi PK parent có entry bằng role fixture được phép. | FK từ chối orphan và RESTRICT giữ parent/entry; không lẫn lỗi quyền hoặc trigger parent với proof FK. |
| LED-DB-06 | Insert lại cùng `(transfer_id, entry_seq)`, cả một transaction và hai connection có barrier/timeout. | Duplicate bị từ chối; ca winner commit chỉ có một dòng cho seq đó; loser rollback. Thêm ca winner rollback để kiểm kết quả waiter. Không map unique violation thành replay. |
| LED-DB-07 | Insert entry 1 rồi inject lỗi entry 2 hoặc rollback toàn transaction; thử insert đủ hai rồi rollback. | Không có entry của transaction sau rollback; entry đã commit của fixture khác giữ nguyên. Đây chỉ là proof transaction SQL, chưa chứng minh rollback Account/Limit/Audit. |
| LED-DB-08 | App chạy UPDATE/DELETE/TRUNCATE, ALTER/DROP bảng, disable/replace trigger, reset sequence hoặc chuyển sang migrator. | Bị từ chối; dữ liệu/sequence/object không bị thay đổi bởi thao tác cấm. Report có current/session user và kiểm quyền hiệu lực, không lộ credential. |
| LED-DB-09 | Role test có DML nhưng không DDL chạy UPDATE/DELETE trên entry và TRUNCATE trên bảng có/rỗng dữ liệu. | Trigger báo lỗi đã định nghĩa; rows và cấu trúc giữ nguyên. Evidence tách khỏi lỗi ACL của LED-DB-08. |
| LED-DB-10 | Qua toàn bộ bootstrap/migration, restart; kiểm default privileges và role membership. | Không cấp lại UPDATE/DELETE/TRUNCATE/DDL cho app; insert/select vẫn hoạt động; migration owner và runtime tiếp tục tách biệt. |
| LED-DB-11 | Fresh, upgrade từ predecessor có dữ liệu, validate, migrate lại và startup với mapping thật. | Chain duy nhất, checksum hợp lệ, dữ liệu cũ giữ nguyên, no-op lần sau và `ddl-auto=validate` đạt; fixture chain được ghi riêng nếu provider chưa có. |
| LED-DB-12 | Trong DB disposable, commit một seq hợp lệ hoặc cặp có amount lệch nhưng mỗi dòng hợp lệ. | Ghi nhận giới hạn schema: không tự chứng minh đủ cặp/cân bằng/status. Tái sử dụng vector BE-LED-01 tại BE-LED-03–04 để provider/T2 từ chối và rollback các tình huống này. |

Với index, thu catalog definition và query plan cho evidence theo transfer/account-time/correlation trên dữ liệu đại diện. Không bắt buộc planner chọn index với fixture rất nhỏ; không dùng kết quả này để tuyên bố D5 hoặc tối ưu history đã đạt.

## 9. Evidence và bàn giao

Đầu ra implementation dự kiến gồm migration Ledger được review, hardening SQL/bootstrap đúng ownership, PostgreSQL tests và report `tasks/evidence/BE-LED-02.md`. Report chỉ tạo khi có lần chạy thực; ghi test ID, revision/working-tree scope, PostgreSQL version, Flyway locations/versions/checksum, role, command và output, pass/fail/skipped, fixture disclosure, blocker và reviewer. Không ghi PASS nếu test bị skip do thiếu Docker/provider; không dùng report BE-LED-01 thay cho test schema.

| Nhận bàn giao | Nội dung |
|---|---|
| BE-LED-03 | DDL/constraint names, generated IDs, validation boundaries, error propagation và SQL rollback evidence; kiểm cặp trước trả success vẫn bắt buộc. |
| BE-LED-04/08, Transfer, Account, Limit, Audit | Migration chain thật và role tests; chạy lại posting/T2 nhiều connection, audit failure và rollback toàn money path với providers thật. |
| BE-LED-06 | FK/index/correlation và tính bất biến phục vụ evidence; không suy empty ledger là proof rollback, không tạo opening baseline từ migration Ledger. |
| DB/Security/Ops | ACL/trigger/catalog output, history trước/sau, rủi ro lock, backup/restore references và rollout gates. |

## 10. Điều kiện đóng task

- [ ] DB/Account/Transfer xác nhận PK/FK, version/order duy nhất và phương án xử lý duplicate V2 theo history thực tế; prerequisite production đã có.
- [ ] Review schema/index §5 trên chain thật. Candidate đã triển khai và kiểm catalog trên fixture.
- [ ] Xác nhận quyền runtime hiệu lực trên chain thật và môi trường đích. Candidate đã kiểm ACL, sequence, DDL/role isolation và trigger bằng các kết nối riêng trên PostgreSQL.
- [ ] Fresh/upgrade/restart, Flyway validate và Hibernate validate với mapping Ledger thật đạt; không sửa migration đã áp dụng hoặc mất dữ liệu predecessor.
- [ ] LED-DB-01–12 có evidence, reviewer và giới hạn rõ; fixture không thay integration production. Thiếu provider thì giữ AC tương ứng Blocked.
- [ ] BE-LED-03–04/06/08 nhận handoff; DB/Security/QA/Ops review phần thuộc phạm vi. Task không được đánh dấu hoàn thành chỉ vì đặc tả đã viết.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 17/11/2026 | Viết đặc tả schema, hardening, migration gates, test oracle và handoff BE-LED-02. |
| v1.1 | 17/11/2026 | Thêm migration candidate, harness PostgreSQL và liên kết evidence; giữ gate production. |
