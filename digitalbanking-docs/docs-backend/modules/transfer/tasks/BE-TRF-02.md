# BE-TRF-02 — Schema, migration và state domain

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-TRANSFER-02`
> **Ưu tiên / kế hoạch:** P0 / W4
> **Trạng thái:** Blocked — có domain, migration và JPA ứng viên; provider, migration production, review và bằng chứng liên module chưa hoàn tất
> **Owner:** Vũ
> **Phối hợp / review:** Backend Lead, DBA/Architect, Customer, Account, Identity, Limit, QA, Security và Ops theo phạm vi.

## 1. Mục tiêu và phạm vi

Chuẩn bị schema bền vững cho transfer và idempotency, migration Flyway forward-only và domain biểu diễn trạng thái/giá trị để các task tiếp theo có thể xây dựng T1/T2. Task bao gồm thiết kế, triển khai và kiểm chứng khi các dependency sẵn sàng; hiện trạng và giới hạn evidence được ghi bên dưới.

Transfer là write-owner của `transfer` và `idempotency_record`; không tạo hoặc sửa bảng do Customer, Account, Identity, Limit hay module khác sở hữu. Không triển khai API, tiền/ledger, step-up hay recovery worker trong task này. Chuẩn bị test consumer bằng mock/fake theo hợp đồng nếu provider chưa có; mock không thay được PostgreSQL thật và migration/provider thật để nghiệm thu FK, transaction hoặc integration.

**Hiện trạng 16/11/2026:** Flyway production hiện chỉ có `V1__init_schema.sql` tạo schema `banking`; migration Customer/Account/Identity thật cần cho FK chưa có trong source. Transfer có domain/value objects và [evidence unit](evidence/BE-TRF-02-domain-verification.md). Theo DEC-TRF-013 có SQL ứng viên `digitalbanking-backend/src/main/resources/db/transfer-candidate/V12__transfer.sql` (đổi từ V6 bởi DEC-ADM-016) ngoài location Flyway mặc định; version được chọn khi tích hợp ngày 17/11/2026 (sau Identity `V2`–`V4`, Customer `V5`), chưa thể phát hành hay chứng minh tương thích Account provider. JPA mapping và terminal adapter (`com.simulatedbank.transfercandidate.persistence`) cùng JDBC primitives là candidate, không component-scan/đăng ký runtime; terminal update dùng `MANDATORY`, throw khi lệch trạng thái/fence để rollback và chỉ refresh entity Transfer, không detach entity provider khác. [Evidence candidate](evidence/BE-TRF-02-candidate-verification.md) ghi kết quả PostgreSQL (migration, JPA `ddl-auto=validate`, race/rollback) với provider fixture synthetic, ArchUnit boundary có fixture âm và giới hạn. `ddl-auto=validate` đã cấu hình; validation ứng dụng với migration provider/Transfer production vẫn cần chứng minh. [BE-TRF-01](BE-TRF-01.md) còn Blocked do review/evidence liên module chưa xong.

## 2. Nguồn và dependency

- [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.4.1, §4.4.3, §5, §7–§8: baseline kiểu, ràng buộc, FK, index và lifecycle; [[digitalbanking-docs/docs-common/03. SRS.md]] §2.4, §2.13: yêu cầu nghiệp vụ.
- [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5–§7, §10, §12: T1/T2, state, lock, race và migration; [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §4–§5, §7–§10, §13–§14: boundary, transaction, hợp đồng provider và điểm còn mở.
- [Thiết kế Transfer](../design.md) §1.3–§1.4, §2–§3, §5; [backlog](README.md) §2; [DECISIONS.md](../../../../../DECISIONS.md) DEC-TRF-003–006, 008, 010, 013; [[digitalbanking-docs/docs-common/09. Test plan.md]] §7–§10: oracle và evidence.
- `docs-common/01–11` ưu tiên hơn `docs-common/design/` và tài liệu backend theo quy tắc tại BE-TRF-01. Khi phát hiện mâu thuẫn hoặc chi tiết chưa được quyết định, ghi blocker và chuyển đúng owner/điều hành phân xử; cập nhật nguồn có thẩm quyền sau quyết định. Không coi quyết định thiết kế là sign-off hoặc bằng chứng runtime.

| Dependency | Cần cho BE-TRF-02 | Trạng thái / cách xử lý |
|---|---|---|
| Customer, Identity | Migration `customer`, `app_user`, `auth_challenge`; xác nhận PK, FK, thứ tự và policy xóa; challenge nullable, duy nhất khi có. | Chưa có migration thật; phối hợp owner, test logic độc lập với fake contract. FK thật và nâng cấp tích hợp **Blocked**. |
| Account | Migration `account`, PK và FK source/destination; ownership/status do Account API kiểm tra, không suy ra từ FK. | Chưa có migration thật; không tạo bảng production thay owner. FK/integration **Blocked**. |
| Limit, Identity, Audit | Guard/reservation, consume challenge và audit CREATED tham gia T1 đúng transaction theo `05` §5, §10.1, §11.1. | Test consumer bằng fake theo contract và vector; atomicity, lock order và rollback liên module chỉ xác nhận bằng provider thật + PostgreSQL. |
| BE-TRF-01, DBA/Architect, Ops | Review contract, schema lưu TTL version, recovery/fencing và kế hoạch migration/khôi phục. | DEC-TRF-013 chọn cột và đồng bộ `04`; candidate ngoài Flyway production. Review owner, provider và evidence còn thiếu; không coi ủy quyền chọn thiết kế là sign-off. |

Các giao tiếp đã được mô tả trong `05`; tên operation là contract thiết kế, không khẳng định provider đã tồn tại. Nếu phát hiện dependency giao tiếp chưa có trong `05`, đề xuất phương án qua public API/DTO của owner, ghi vào `05` để các bên review, rồi test fake theo hợp đồng đó; không truy cập repository/entity nội bộ module khác.

## 3. Đầu việc và tiêu chí nghiệm thu

| ID | Công việc | Đầu ra / điều kiện nghiệm thu |
|---|---|---|
| TRF-02.1 | Đối chiếu baseline §4 và matrix FK/index với `04`, quyết định mới và migration thật. | Có mapping cột, null/default/check, PK/FK/delete rule, index, trigger, owner và truy vấn. Sai lệch nguồn hoặc schema bổ sung có issue, owner và review; không âm thầm đổi baseline. |
| TRF-02.2 | Phối hợp thứ tự migration, tạo version mới cho schema thuộc Transfer. | Flyway forward-only, version duy nhất sau các prerequisite thực tế; không sửa V1 hay migration đã áp dụng. Khởi tạo sạch và upgrade từ V1/predecessor có dữ liệu đại diện đều chạy được trên PostgreSQL; `flyway validate` và Hibernate `ddl-auto=validate` đạt. |
| TRF-02.3 | Xây state/value objects và mapping persistence. | Dùng decimal chính xác, status enum có tên lưu ổn định, chuyển trạng thái hợp lệ và dữ liệu terminal đầy đủ; không dùng floating point hay xuất JPA entity ra API. Application validation xử lý precision/scale trước khi ghi, không dựa vào phép làm tròn của DB. |
| TRF-02.4 | Bảo vệ invariant ở DB và transaction boundary. | Check/unique/FK/index đúng §4; trigger chặn sửa status, amount, accounts, reference sau terminal; update có điều kiện PENDING và xử lý race; T1 thua unique rollback toàn bộ trước khi đọc lại, không orphan. |
| TRF-02.5 | Rà soát lifecycle TTL và dependency schema mới. | Đặc tả storage/version TTL, recovery attempt và fence theo §6 cùng owner; bổ sung migration chỉ sau review và đồng bộ `04`. Không purge PENDING hoặc kích hoạt recovery tự động khi thiếu proof/fence. |
| TRF-02.6 | Viết và chạy test, chuẩn bị rollout/khôi phục. | Có test và oracle §7, migration plan §8, evidence tái lập và review DBA/owner/QA; thiếu provider hoặc môi trường ghi Blocked, không đánh dấu Verified bằng mock. |

## 4. Baseline schema cần triển khai

Các bảng sau tóm tắt để lập kế hoạch; `04` §4.4.1/§4.4.3 là danh mục đầy đủ và có thẩm quyền khi review migration. Không tự diễn giải shorthand quan hệ 1:1 thành yêu cầu giữ idempotency vĩnh viễn: một record tham chiếu đúng một transfer và mỗi transfer có tối đa một record, nhưng record terminal có thể được purge còn transfer vẫn tồn tại.

### 4.1. `banking.transfer`

| Nhóm | Cột và quy tắc |
|---|---|
| Định danh | `transfer_id BIGINT` identity PK; `reference_no VARCHAR(32)` NOT NULL, dài 1–32, unique và immutable. |
| Owner/actor/account | `customer_id`, `initiated_by_user_id`, `source_account_id`, `destination_account_id` BIGINT NOT NULL FK tương ứng `customer`, `app_user`, `account`, `account`; hai account phải khác nhau. Dùng scalar ID, không ghép JPA entity của provider. |
| Giá trị | `amount NUMERIC(19,2)` NOT NULL > 0; `currency CHAR(3)` NOT NULL mặc định `VND`, chỉ nhận VND; `description VARCHAR(255)` nullable, không chứa dữ liệu nhạy cảm. |
| State/trace | `status VARCHAR(16)` NOT NULL mặc định `PENDING`, chỉ `PENDING`, `COMPLETED`, `FAILED`; `failure_code VARCHAR(32)`, `failure_message VARCHAR(255)` nullable, thông điệp an toàn; `correlation_id VARCHAR(64)` NOT NULL. |
| Step-up/thời gian | `step_up_challenge_id BIGINT` nullable FK `auth_challenge`, partial UNIQUE khi khác NULL; `created_at`, `updated_at TIMESTAMPTZ` NOT NULL mặc định `now()`; `completed_at`, `failed_at TIMESTAMPTZ` nullable. |

PENDING không có `completed_at`/`failed_at`; COMPLETED có `completed_at`, không có `failed_at`; FAILED có `failed_at` và `failure_code`, không có `completed_at`. Trigger bảo vệ status/amount/accounts/reference sau terminal theo `04`. FK theo chính sách `RESTRICT` của `04` §7.6; xác nhận tương thích với migration provider. FK không thay authorization hay kiểm tra account/customer.

Index theo `04`: unique `uq_transfer_reference`; `ix_transfer_customer_time (customer_id, created_at DESC)`, `ix_transfer_source_time (source_account_id, created_at DESC)`, `ix_transfer_destination_time (destination_account_id, created_at DESC)`, `ix_transfer_status_time (status, created_at)` cho quét PENDING, `ix_transfer_correlation (correlation_id)` và partial unique `uq_transfer_step_up_challenge`. Không tạo index trùng chức năng của unique constraint; kiểm tra index thực tế và query plan với dữ liệu đại diện.

### 4.2. `banking.idempotency_record`

| Nhóm | Cột và quy tắc |
|---|---|
| Định danh | `idempotency_key VARCHAR(255)` PK global, dài 1–255; `request_hash CHAR(64)` NOT NULL là SHA-256 của payload chuẩn hóa; `transfer_id BIGINT` NOT NULL, unique FK tới transfer. |
| State/response | `status VARCHAR(16)` NOT NULL mặc định `RECEIVED`, nhận `RECEIVED`, `IN_PROGRESS`, `COMPLETED`, `FAILED`; `response_status SMALLINT` nullable trong khoảng 100–599; `response_body JSONB` nullable, chỉ lưu response an toàn đã mask. Terminal phải có `completed_at` và `response_status` theo DB baseline; để replay terminal, application còn phải lưu body gốc an toàn cùng status trong transaction terminal. |
| Thời gian | `created_at TIMESTAMPTZ` NOT NULL mặc định `now()`, `completed_at TIMESTAMPTZ` nullable, `expires_at TIMESTAMPTZ` NOT NULL và > `created_at`. TTL/version theo §6; `completed_at` của record là mốc terminal cho cả COMPLETED và FAILED, khác `transfer.completed_at` chỉ dùng cho COMPLETED. Không xem expiry đơn lẻ là điều kiện purge nonterminal. |

Unique `uq_idempotency_transfer`, `ix_idempotency_expires (expires_at)` và `ix_idempotency_status (status)` theo `04` §4.4.3; PK đã phục vụ lookup key. `request_hash` và key không được log raw. Không thay PK global bằng key theo customer; so sánh actor/customer/hash khi replay là trách nhiệm application.

## 5. Domain và ranh giới transaction

- Transfer được tạo `PENDING` trong T1; chỉ `PENDING → COMPLETED` hoặc `PENDING → FAILED`. Terminal không chuyển lại hoặc sang terminal khác. Domain từ chối transition sai; DB check bảo vệ tổ hợp trạng thái/thời gian, trigger bảo vệ immutable fields ngay cả khi update trực tiếp. Dùng `UPDATE ... WHERE status='PENDING'` và kiểm tra số dòng dưới lock đúng thứ tự; check trạng thái riêng không chứng minh concurrency.
- Idempotency tạo `RECEIVED` cùng T1; terminal `COMPLETED`/`FAILED` cùng transfer và response trong transaction terminal. `IN_PROGRESS` chỉ là marker tạm trong T2 nếu dùng, không là lease hay bằng chứng worker sở hữu; rollback T2 giữ `PENDING`/`RECEIVED` khi rollback đã được xác nhận. Không tự suy đoán kết quả khi commit chưa rõ.
- Value objects/validation phải giữ `BigDecimal`/decimal chính xác, giới hạn precision/scale `NUMERIC(19,2)`, amount dương, VND, reference và IDs hợp lệ, source khác destination. API scale/format/canonical hash lấy từ `07` và BE-TRF-04; không tự đổi hợp đồng. UTC `TIMESTAMPTZ` cho persistence; `terminal_at` lấy theo DEC-TRF-008, không nhầm timestamp lưu với thời điểm commit thực.
- Trong T1: sau guard và kiểm tra theo contract, insert transfer trước, insert key global `ON CONFLICT` trong cùng transaction với reservation/consume/audit theo owner. Nếu thua unique của idempotency key, rollback toàn T1, kể cả transfer và side effect, rồi mới đọc lại key trên primary trong transaction mới. Không bắt conflict rồi commit transfer mồ côi; collision `reference_no` hoặc `step_up_challenge_id` là lỗi unique riêng, không xử lý như replay key. 428 trước T1 không tạo các record bởi request đó; request cạnh tranh khác vẫn có thể commit.
- Dùng domain hoặc DTO tại boundary; entity/persistence mapping là nội bộ. BE-TRF-02 chuẩn bị invariant, còn orchestration, response/replay, T2 và recovery được thực hiện/kiểm chứng ở 04–06.

## 6. Schema cần phối hợp và blocker

| Hạng mục | Yêu cầu đã quyết định | Việc còn phải chốt / bằng chứng |
|---|---|---|
| TTL/version — DEC-TRF-005/013 | T1 snapshot TTL hợp lệ ≥24h và version, `expires_at = created_at + TTL_T1`; terminal snapshot TTL/version và ghi atomic response/`completed_at`/expiry `max(expires_at, completed_at + TTL_terminal)`. Config thiếu/sai fail-closed; không rút ngắn expiry, không purge nonterminal. | `04` đã ghi cột candidate. Transfer/DBA/Ops cần review lifecycle/migration và test config đổi/race trước nghiệm thu. |
| Fence/recovery — DEC-TRF-003/010/013 | Transfer cần claim/epoch/token/lease bền và CAS ngăn token cũ ghi tiền/terminal; recovery lưu count, first/next attempt và state, retry hữu hạn qua restart. | `04` đã ghi metadata candidate; transaction protocol và tương tác provider vẫn chưa review/proven. Không suy rollback từ lease hết hạn; thiếu proof thì giữ trạng thái và alert/manual. |
| Guard/reservation — DEC-TRF-008 | Guard theo customer và reservation PENDING thuộc contract Limit; settlement/release cùng transaction. | Limit/Identity/Transfer review schema, FK, thứ tự migration và lock; không tạo bảng Limit trong Transfer. Test qua contract fake rồi provider thật. |

Các hạng mục có quyết định và candidate, không phải cột đã tồn tại trong production hay sign-off. Dependency liên module mới phải được ghi tại `05` theo §2. Nếu schema, provider hoặc evidence chưa đủ, task/AC bị ảnh hưởng vẫn Blocked.

## 7. Kế hoạch kiểm thử và evidence

| Nhóm / liên kết | Thiết lập và oracle tối thiểu |
|---|---|
| Migration | PostgreSQL 16 thật: database sạch và upgrade từ V1/predecessor với dữ liệu synthetic; kiểm tra `flyway_schema_history`, `flyway validate`, catalog constraints/index/trigger/FK, application startup `ddl-auto=validate`; lặp theo kế hoạch rollback/forward repair trên bản sao dữ liệu. FK thực phải dùng migration provider, không giả lập bằng bảng production do Transfer tạo. |
| Constraint & FK | Insert hợp lệ/không hợp lệ: amount 0/âm, currency sai, account trùng, status/timestamp/failure code sai, null/độ dài, expiry không hợp lệ; FK thiếu customer/user/account/challenge/transfer và delete RESTRICT. Kiểm tra nhiều challenge NULL được phép, cùng challenge khác NULL bị từ chối; duplicate reference, key global, transfer_id bị từ chối. |
| State — `T-STATE-01` | Test domain và SQL trực tiếp: hai transition hợp lệ; terminal → PENDING/terminal khác bị từ chối, không sửa status/amount/accounts/reference sau terminal; timestamp/failure code đúng. Các thay đổi ngoài danh sách phải theo policy đã review, không tự mở rộng trigger. |
| T1 race — phần persistence của `T-IDEM-01` | Dùng connection/transaction độc lập, barrier và timeout: hai transfer tranh cùng key global; một winner commit, loser rollback rồi read lại, chỉ một transfer + một record tương ứng, không orphan hoặc side effect của loser. Thử cùng challenge và rollback sau insert; không nhầm test persistence này với proof debit/replay đầy đủ ở BE-TRF-04/08. |
| Lifecycle TTL — phần schema của `T-IDEM-03/05` | T1/terminal lưu version và expiry theo schema được review; 24→48h, 48→24h, config thiếu/sai và PENDING quá TTL; terminal đủ response, expiry không ngắn lại, nonterminal không bị purge. Race purge/retry và integration thuộc 04/06/08, không coi test đơn vị là proof. |
| Boundaries | Kiểm tra entity không lộ qua API/DTO, domain dùng decimal và reject giá trị sai; test consumer với fake đúng `05` khi dependency chưa sẵn sàng. Khi provider có thật chạy lại contract và transaction integration; mock/H2 không chứng minh FK, lock hoặc rollback PostgreSQL. |

Mỗi bằng chứng theo `09` §10: test ID/phạm vi, revision, lệnh, PostgreSQL/version migration/seed, môi trường, output và artifact synthetic không chứa secret/PII, người chạy và reviewer. Oracle trên là kế hoạch; evidence unit hiện có liên kết trong §1, còn các kết quả khác phải ghi riêng với phạm vi và dependency. Không dùng kết quả test common hay fixture provider làm bằng chứng integration thật.

## 8. Rollout, khôi phục và điều kiện đóng

1. DBA và owner thống nhất thứ tự migration prerequisites và version không xung đột; review SQL, FK, trigger/index, quyền và ảnh hưởng lock. Kiểm tra dữ liệu hiện hữu, backfill nếu có, backup/snapshot và diễn tập trên bản sao trước rollout; index lớn dùng phương án phù hợp PostgreSQL/Flyway, kể cả giới hạn transaction của `CREATE INDEX CONCURRENTLY`.
2. Sau migrate, xác minh catalog, schema validation, integrity/reconciliation và health trước traffic. Khi lỗi dừng rollout, giữ evidence, đánh giá trạng thái thực tế và phối hợp Ops/DBA; không sửa `flyway_schema_history` hoặc xóa dữ liệu tiền tùy tiện. Kế hoạch khôi phục dùng migration forward sửa lỗi hoặc restore nhất quán từ backup đã kiểm chứng theo runbook; không hứa rollback DDL an toàn cho dữ liệu đã ghi.
3. Đóng task chỉ khi mọi AC trong [backlog](README.md) được Verified, schema bổ sung được duyệt/đồng bộ, provider thật và migration prerequisites được tích hợp, test PostgreSQL cùng evidence đạt, rollout/khôi phục được review. Ghi link revision/report, reviewer và blocker; không đánh dấu `[x]` chỉ vì tài liệu đã viết hoặc test fake đã pass.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 16/11/2026 | Chi tiết baseline schema, state, dependency, test oracle và điều kiện đóng BE-TRF-02; chưa triển khai hoặc nghiệm thu. |
| v1.1 | 16/11/2026 | Ghi nhận domain, DEC-TRF-013 và migration candidate ngoài Flyway production; các review và bằng chứng provider thật vẫn Blocked. |
| v1.2 | 16/11/2026 | Bổ sung JPA mapping/terminal adapter candidate (`MANDATORY`, throw khi lệch fence, không detach entity provider), ArchUnit boundary có fixture âm và evidence PostgreSQL đầy đủ (298 test backend, 95 Transfer); provider/migration production vẫn Blocked. |
