# BE-INT-01 — Khóa ma trận hợp đồng tích hợp Backend

> **Ưu tiên:** P0 — gate G0, ngày T1 của W6  
> **Thực hiện/chốt:** người điều hành/merge owner, toàn quyền sửa mọi module theo DEC-INT-003  
> **Trạng thái:** DONE phần ma trận/định danh và xử lý xung đột phát hiện; **G0 vẫn BLOCKED** vì một số provider API/adapter v1 chưa có và vector xuyên provider chưa chạy  
> **Đầu ra:** ma trận thiết kế ↔ Java API ↔ consumer ↔ migration, gap còn lại và test vector chung. Không dùng trạng thái task report làm bằng chứng kích hoạt money path.

## 1. Nguồn, phạm vi và nguyên tắc

- Nguồn ưu tiên: [SRS](../../../docs-common/03.%20SRS.md), [Database design](../../../docs-common/04.%20Database%20design%20%26%20analysis.md), [System architecture](../../../docs-common/06.%20System%20architecture.md), [API contracts](../../../docs-common/07.%20API%20contracts.md), [Test plan](../../../docs-common/09.%20Test%20plan.md), sau đó [Modules contract §3–§14](../../../docs-common/design/05.%20Modules%20contract.md). [Kế hoạch tích hợp](README.md) xác định G0/G1 và owner.
- Phạm vi: tất cả `TRFC-*` trong Modules contract §13.4, `MOD-OI-01..11`, các port Identity/Customer/Account/Admin dùng chung, và migration trực tiếp cần cho handoff. Đối chiếu **source hiện có**, không coi tên class hoặc test fake là chứng cứ provider đã ghép production.
- Ký hiệu trạng thái: **Có API** = thấy interface trong `provider.api`, chưa mặc nhiên là compatible; **Candidate** = logic/port Transfer chưa được ghép runtime; **Thiếu** = chưa thấy contract hoặc implementation cần thiết; **Đã dùng Admin** = Admin gọi provider thật theo code, còn cần test liên module cho gate này. `R`, `OWN`, `JOIN`, `AFTER` theo Modules contract §3.2. Consumer không import `application/domain/adapter` của provider.
- Khi API thiếu hoặc không khớp: giữ fake/fixture **chỉ trong test/dev** theo contract, production wiring và gate liên quan `BLOCKED`. Người điều hành tự đối chiếu nguồn ưu tiên, ghi quyết định ở `DECISIONS.md`, sửa provider/consumer và cập nhật `05. Modules contract.md`; không chờ cá nhân khác.

## 2. Ma trận Transfer v1: contract ↔ Java API ↔ consumer ↔ dữ liệu

Đường dẫn source bên dưới tính từ repo `digitalbanking-backend/src/main/java/com/simulatedbank/banking/`; migration ở `src/main/resources/db/`. Cột **cần đóng** là điều kiện handoff, không phải kết quả test.

| Contract / TX | Provider Java API quan sát được | Consumer, migration quan sát được | Điều kiện cần đóng |
|---|---|---|---|
| `TRFC-ID-02` request challenge / OWN T0 | `identity.api.StepUpChallengePort.requestChallenge`; trả `challengeRef` dạng `String` UUID, `ConsumedChallenge.challengeId` là `long`. | `transfer.application.StepUpChallengePort` dùng `UUID`; `transfer-candidate` chưa đăng ký; `migration/V2__create_identity_and_rbac_tables.sql` có `auth_challenge`. | Adapter UUID↔String và binding actor/hash/key, dispatch ACK/fail-closed, purpose, lỗi `07` phải dùng vector chung. Identity + Transfer; `MOD-OI-07/09`. |
| `TRFC-ID-05` find verified binding / R primary | **Thiếu** operation trong `identity.api.StepUpChallengePort`; §5.2 ghi là đề xuất. | Transfer có seam `findVerifiedBindingIfPresent` qua port riêng; chưa có adapter provider. | Hiện thực lookup scoped, absent khác DB error, multiple active là invariant; hoặc chọn client truyền ref rõ ràng và cập nhật contract. P0 T0/T1. |
| `TRFC-ID-04` consume verified / JOIN T1 | `identity.api.StepUpChallengePort.consumeVerified` đã có; `challengeRef` String → `challengeId` long, provider phải MANDATORY. | `TransferIntentCandidate` nay giữ UUID, insert FK NULL, consume rồi attach BIGINT; Transfer DDL V13 có FK/unique challenge. | Xác minh binding/expiry/single-use, unique race, rollback T1 với Identity thật. |
| `TRFC-AC-01` resolve destination / R primary | `account.api.AccountQuery.findAccount(id)` và `resolveDestination(String accountNumber)` có API/implementation. | `TransferPreflightCandidate` và `TransferOrchestrator` dùng consumer seam; account table ở `migration/V8`. | Map `Optional.empty` khác DB lỗi, status/currency/masked fields, normalization và account number blind-index; test provider thật. Account + Transfer. |
| `TRFC-AC-02` assert ownership / R primary | `AccountQuery.assertOwnership(accountId, customerId, correlationId)` là `void` + exception, **khác** outcome đề xuất `OWNED/FORBIDDEN/NOT_FOUND`. | `transfer.application.AccountOwnershipPort` là seam outcome; Transfer GET/POST chưa nối Account adapter thật. | Chốt mapping exception→outcome, tránh lộ account khi forbidden, test IDOR; nếu đổi provider signature phải version và test consumer. |
| `TRFC-AC-03`, `TRFC-AC-04`, `TRFC-AC-05` lock, debit, credit / JOIN T2 | `account.api.AccountBalance` có cả 3 operation; `AccountBalanceService` dùng `MANDATORY`. | `TransferSettlementCandidate.Ports` còn abstract; `migration/V8` có account; chưa có Account movement evidence per transfer. | Adapter bảo toàn lock ID tăng dần, conditional debit và balanceAfter; PostgreSQL rollback/credit failure; Account evidence `TRFC-AC-06` là blocker recovery. Account + Transfer. |
| `TRFC-LI-01` usage snapshot / R | `limit.api.LimitUsage`, `ApplicableLimits`, `LimitRules` có, nhưng chưa thấy `getUsageSnapshot` đúng v1. | Transfer preflight/step-up là candidate; `limit_config` V9 và `limit_usage` V12 đã ở main migration. | Hoàn thiện customer/account/date/version, phân biệt snapshot với guard authoritative và chạy vector LI-01..03. |
| `TRFC-LI-04` guard/step-up / JOIN gate/T1 | **Thiếu** `lockStepUpGuard` và `evaluateStepUpUnderGuard` trong `limit.api`; `LimitEnforcement` hiện chỉ `lockAndCheck/recordCompleted`. | `TransferIntentCandidate` có port guard/assessment giả lập; chưa có provider adapter hoặc reservation row trong migration chính. | P0: customer guard, projected total completed hôm nay + mọi PENDING + lệnh mới đúng một lần, qua ngày/2 key/428 race; nếu chưa có thì không kích hoạt T1. Limit + Transfer + Identity; `MOD-OI-01`. |
| `TRFC-LI-02` lockAndCheck / JOIN T2 | `limit.api.LimitEnforcement.lockAndCheck(customerId, sourceId, amount)` **thiếu** `terminalAt`, `businessDate`, config IDs/version và outcome v1. | Settlement candidate gọi port `lockUsageCustomerThenAccount`, chưa nối provider; schema V9/V12 đã hợp nhất. | Chốt một read config hai scope, usage lock customer→account, business date, reject/system outcome và no double count. |
| `TRFC-LI-05` reserve + `TRFC-LI-07` settle/release / JOIN T1/T2 | **Thiếu** provider API reservation/settlement v1. DEC-INT-001 đã tách hai ID, chưa chốt chữ ký Java. | `TransferIntentCandidate.reserveForPendingTransfer`; `TransferSettlementCandidate.settleCompletedReservation/releaseFailedReservation` còn consumer ports; chưa thấy reservation migration chính. | Chốt provider DTO/signature, state và unique transfer, `policyVersion`, ngày T1/terminal, exactly-once usage; không gọi `recordCompleted` thêm cho cùng transfer. Limit + Transfer + DB; P0. |
| `TRFC-LE-01` post double entry / JOIN T2 | `ledger.api.LedgerPosting.postDoubleEntry(PostingCommand)` và `PostingRef` có; service dùng `MANDATORY`. | Settlement candidate có `postAndVerifyBalancedLedger` seam; Ledger DDL đã ở V14, FK Transfer/Account. | Ghép adapter với Account balanceAfter từ cùng T2; xác nhận đúng hai entry, no duplicate và rollback. |
| `TRFC-FR-01` fraud decision / JOIN T2 | Chưa thấy `fraud.api.FraudEvaluation`; logic hiện ở `fraud.domain/application`. | `TransferSettlementCandidate` có `FraudResult`/savepoint seam; `Flag.ruleVersion` nay là `int > 0` (gap String trong §13.4 đã được sửa ở candidate). | Tạo provider-owned API v1 sau review, map ALLOW/BLOCK/ENGINE_ERROR, flag IDs/INT version; test savepoint thật trước fail-open. Fraud + Transfer + Audit. |
| `TRFC-AU-01`, `TRFC-AU-02` C1/C2 JOIN, C3 OWN | `AuditModuleApi` đã đồng bộ `recordInTransaction(AuditEvent) → AuditRecordRef` và `recordStandalone(AuditEvent) → Optional<AuditRecordRef>` theo §11.1. | Provider Audit, Admin adapter và hai writer Audit đã test; V10/V17 tạo bảng và canonical payload, cùng chain lock. Transfer C1/C2/C3 vẫn là seam chưa nối runtime. | Ghép Transfer/Audit T1/T2, C2 flag ID tăng rồi C1 cuối sau flush; test C3 fallback, lỗi C1/C2 rollback toàn T2. |
| `TRFC-CU-02` contact by purpose / R AFTER | `customer.api.CustomerQuery` chỉ có lookup identity; chưa có `resolveNotificationDestination`. | Identity/Notification cần contact khi gửi; Customer V5 có PII encrypted, không được consumer đọc bảng riêng. | Chốt SYSTEM purpose, consent/status/channel, temporary destination, unavailable khác technical error; test contact đổi trước dispatch. Customer + Identity + Notification; `MOD-OI-07`. |
| `TRFC-NO-01` enqueue outcome / JOIN T2, T3 AFTER | `notification.api.NotificationOutboxPort.enqueueTransferNotificationsInT2(List<TransferNotice>)` có, nhưng DTO `TransferNotice` nằm ở `notification.domain`, khác quy tắc provider API §3.1; return `void` khác `NotificationRef`. | `TransferNotifications` consumer mapping có; notification DDL đã ở V16. | Đưa DTO immutable vào `notification.api` hoặc duyệt adapter tương thích; chốt unique/dedupe/lease/destination và T2 rollback, không gửi gateway trong T2. |
| `TRFC-AC-06`, `TRFC-LE-02`, `TRFC-LI-06`, `TRFC-AU-03` evidence recovery / R/JOIN | Chưa thấy bộ provider API per-transfer đầy đủ. | `TransferRecoveryEvidence.Reads` là consumer candidate; không được dùng balance hiện tại hay bảng private làm proof. | Người điều hành hiện thực evidence trong từng provider + mode atomic; thiếu/partial/mismatch/error thì giữ nguyên, alert/manual, không re-post. Account + Ledger + Limit + Audit + Transfer; P0 recovery. |

### 2.1. Các port dùng chung và Admin

| Contract / TX | Provider API ↔ consumer quan sát được | Migration / điều kiện còn lại |
|---|---|---|
| Session + Customer identity / R | `common.config.SessionAuthenticator` tạo session; `customer.api.CustomerQuery.findByUserId/findByCustomerId` trả `Optional<CustomerIdentity>` với ACTIVE/INACTIVE. Identity, Account và Admin có consumer. | Identity V2 + Customer V5. Kiểm `Session.customerId`, inactive, absent khác DB error bằng session thật (`BE-INT-03`). |
| Identity role mutation / JOIN approval | `identity.api.IdentityRoleCommand` có API; `Admin` gọi trong proposal/decision flow. | Identity V2/V4 + Admin V6/V7. Maker khác Checker, expectedVersion, challenge binding và rollback Audit cùng TX cần test (`BE-INT-09`). |
| Limit config mutation / JOIN approval | `LimitConfigMutationPort` có API; `ApprovalProposalService`/`ApprovalDecisionService` gọi provider cho LIMIT. Luồng Admin LIMIT cũ trùng lặp đã được gỡ, build qua. | Limit V9/V12 và Admin V6/V7 ở main migration; LimitChangesIT/LimitEnforcementIT đã qua với FK thật. Admin HTTP còn test cũ lệch binding/validation/quyền DB, ghi kết quả lịch sử ở verification report. |
| Admin audit / JOIN C2, OWN C3 | `AuditModuleApi` đã dùng generic AuditEvent/ref; Admin dùng `AdminAuditPort` qua `DurableAdminAuditAdapter`. | V10/V17 là một bảng, hai writer chung lock; AuditModulePostgreSqlIntegrationTest, AuditWriterIT và AuditQueryJdbcStoreIT đã qua. Transfer vẫn cần adapter C1/C2/C3. |
| Contact/Account staff read / R | `CustomerQuery.getCustomerView` trong bảng draft §6 chưa phải Java API; `AccountQuery.getAccountView` có owner/staff scope nhưng staff data scope/tenant chưa duyệt. | Customer V5, Account V8. Không suy permission là quyền đọc mọi customer/account (`BE-INT-03/09`). |

Các dòng trên là kiểm kê source, chưa xác nhận bean production hoặc nghĩa runtime. Consumer không được import `application/domain/adapter` của provider.

## 3. Migration và chênh lệch tài liệu phải xử lý trước G1

| Vấn đề quan sát được | Tác động và hành động | Owner |
|---|---|---|
| `db/migration` từng trùng version **V2–V6** và trùng bảng Audit/Limit/Admin. | Đã lưu các script module-local trong `db/migration-legacy`, giữ V1–V11 canonical và thêm V12–V17. Fresh DB/upgrade canonical V7→V17 đã test; DB dùng script module-local cần lộ trình dữ liệu/checksum riêng. | Người điều hành |
| Transfer/Ledger candidate SQL trước đây ở ngoài `db/migration`. | Đã promote thành V13 Transfer/idempotency rồi V14 Ledger; V15 Fraud, V16 Notification, V17 Audit bridge. Chưa có bằng chứng T1/T2 production wiring. | Người điều hành |
| `05` §13.4 từng ghi `Flag.ruleVersion String` và `verifiedChallengeId BIGINT` là gap, trong khi Transfer candidate đã dùng `int` và UUID snapshot → consume → BIGINT FK. | Đã đồng bộ narrative candidate trong `05`; vector provider vẫn chưa PASS, không đánh dấu G0/G2 đạt. | Người điều hành |
| `05` §14 `MOD-OI-10/11` từng viết như câu hỏi mở, còn DEC-ADM-003 và `07` §5.6 đã chọn Maker binding/idempotency. | Đã cập nhật trạng thái/runtime trong `05`; build PASS, Admin HTTP class có 11/27 test FAIL ở lần chạy riêng trước thay đổi grant V12, chưa chạy lại nên chưa coi gate PASS. | Người điều hành |

## 4. Các quyết định mở và điều kiện kỹ thuật

`DEC-TRF-*` và `DEC-ADM-*` đã chọn ngữ nghĩa tại [DECISIONS.md](../../../../DECISIONS.md), nhưng chưa thay bằng chứng API/runtime. Người điều hành đã chốt ID `TRFC-LI-05/07` và có toàn quyền thực hiện/chốt mọi integration task theo DEC-INT-003. Chữ ký Java/implementation còn thiếu được liệt kê theo điều kiện kỹ thuật; không cần xác nhận của cá nhân khác.

| ID | Câu hỏi cần khóa / điều kiện đóng | Thực hiện/chốt |
|---|---|---|
| `MOD-OI-01`, `MOD-OI-02` | Guard/reservation + 428 race; phân loại pre-T1/sau-T1, savepoint/Tfail và commit unknown; vector PostgreSQL thật. | Người điều hành |
| `MOD-OI-03`, `MOD-OI-04` | Recovery evidence/atomic mode và replay/TTL/global key, response/status gốc, purge; không re-post khi proof thiếu. | Người điều hành |
| `MOD-OI-05`, `MOD-OI-06` | Audit C2→C1 cuối, fail-open fraud chỉ sau rollback savepoint, snapshot limit/version, retry scheduler. | Người điều hành |
| `MOD-OI-07`, `MOD-OI-08`, `MOD-OI-09` | OTP/outbox/contact purpose, staff/customer scope và correlation replay; chữ ký/error/TX v1 provider API còn thiếu. | Người điều hành |
| `MOD-OI-10`, `MOD-OI-11` | Kiểm chứng quyết định Maker step-up/idempotency đã triển khai, cập nhật trạng thái tài liệu; không tái mở policy nếu implementation khớp quyết định. | Người điều hành |
| Chênh riêng `TRFC-LI-05/07`, `TRFC-AC-02`, `TRFC-AU-01`, `TRFC-AU-02`, `TRFC-NO-01` | ID Limit đã chốt; `05` §13.4 ghi mapping Account exception→outcome; Audit event/ref đã đồng bộ; Notification DTO/return còn cần chốt mapping/version mới. | Người điều hành |

### 4.1. Trạng thái từng `MOD-OI-*` tại revision kiểm kê

`Quyết định có` chỉ xác nhận ngữ nghĩa đã ghi trong `DECISIONS.md`, **không** là kết quả chạy. Theo DEC-INT-003, người điều hành tự thực hiện/chốt bảng này; các phần API/mapping chưa có bằng chứng vẫn BLOCKED tại G0.

| ID | Nguồn quyết định / trạng thái hiện hành | Bằng chứng và xác nhận còn thiếu |
|---|---|---|
| MOD-OI-01 | DEC-TRF-001/002/008 chọn tổng projected và guard customer; provider Limit còn thiếu. | LI-01/02, `428` race và reservation PostgreSQL. |
| MOD-OI-02 | DEC-TRF-003 chọn ranh giới pre-T1/sau-T1, savepoint/Tfail/fence; candidate chưa nối. | Credit/commit unknown, rollback T2/Tfail. |
| MOD-OI-03 | DEC-TRF-006/014 chọn evidence per-transfer, complete chỉ khi đủ proof; provider reads thiếu. | REC-01, claim/fence, manual path. |
| MOD-OI-04 | DEC-TRF-004/005 chọn global key, replay/status/TTL; Transfer candidate có logic riêng. | HTTP replay và purge trên runtime. |
| MOD-OI-05 | DEC-TRF-007 chọn C2 flag ID tăng, C1 cuối; Audit API đã có, Transfer chưa ghép. | AU-01, flush/chain/rollback. |
| MOD-OI-06 | DEC-TRF-009/010 chọn limit snapshot, fraud count, retry; APIs/evidence chưa đủ. | LI-03, FR-01, scheduler PostgreSQL. |
| MOD-OI-07 | DEC-TRF-011 chọn OTP/outbox/contact at-least-once; CU-02/NO-01 còn lệch/thiếu. | CU-NO-01, dispatch/lease/privacy. |
| MOD-OI-08 | DEC-TRF-004/012 chọn replay correlation và read permissions; staff scope còn mở. | Principal, IDOR, scope test. |
| MOD-OI-09 | DEC-TRF-012/015 chọn provider-owned v1 boundary; nhiều DTO/signature chưa có. | Duyệt §2 từng dòng, boundary tests. |
| MOD-OI-10 | DEC-ADM-002/003 đã chọn Maker hash + key, Checker approvalId; Admin runtime source và build đã qua. | Người điều hành chạy lại ADM-01/02; lần chạy riêng trước thay đổi grant V12 có 11/27 test Admin LIMIT FAIL, chưa dùng làm gate PASS. |
| MOD-OI-11 | DEC-ADM-003 đã chọn Admin idempotency riêng và TTL; runtime source và build đã qua. | Người điều hành kiểm concurrent/purge/HTTP và schema trên test mới; không suy từ build thành PASS. |

## 5. Contract vectors và bằng chứng cần nộp

Provider và consumer dùng **cùng input/expected result** trong [bộ vector review](BE-INT-01-vectors.md), không chứa secret/PII thật. Người điều hành lưu contract ID, version, revision, command, môi trường, expected/actual HTTP + DB và artifact theo Test plan §10. Bảng dưới là nhóm phủ, không phải kết quả đã chạy.

| Vector tối thiểu | Oracle / ranh giới |
|---|---|
| ID-V1: đúng binding; sai actor/purpose/hash/key; expired; consumed; hai consume đồng thời; T1 audit/unique rollback. | Chỉ đúng binding consume một lần; `challengeRef` UUID không suy ra `challengeId` BIGINT; rollback trả challenge/transfer/key/reservation về trạng thái trước T1. |
| AC-V1: account ID/number, not found, inactive, forbidden, locked A↔B; debit thiếu tiền; credit lỗi. | Read không lộ dữ liệu khi forbidden; lock ID tăng dần; lỗi sau debit không commit tiền một phía. |
| LI-V1: amount 10 triệu, projected total 20 triệu/20 triệu+1; PENDING từ hôm trước; hai key cùng customer; terminal qua nửa đêm; missing config. | Theo `07` §6 và DEC-TRF-008; 428 của request không ghi business row; reservation và usage không cộng đôi; lỗi config/DB fail-closed. |
| FR/AU-V1: `ruleVersion=1`, `0`, âm, overflow/nonnumeric ở adapter; BLOCK, ENGINE_ERROR, C2/C1 failure. | INT > 0 round-trip; savepoint chỉ fail-open khi transaction usable; C1/C2 failure rollback, C3 fallback không thay C1/C2. |
| LE/NO/REC-V1: đúng hai entry, duplicate/mismatch; outbox insert failure, worker crash/retry/contact đổi; evidence absent/partial/mismatch/DB error. | Ledger/terminal/outbox/audit atomic; gateway sau commit, at-least-once; recovery không tự complete/re-post khi proof thiếu. |
| ADM-V1: Maker/Checker khác người, hash mismatch, same-key retry, hai checker song song, stale version, provider/audit lỗi. | Một apply, replay/denial đúng `07`, rollback approval + provider + audit; scope staff chưa duyệt không được mở rộng. |

**Phương pháp:** trước tiên chạy consumer fake và provider contract suite chung để phát hiện lệch DTO/outcome. Sau khi G1 sạch, chạy Testcontainers PostgreSQL với các provider thật, hai connection/barrier cho race, inject lỗi để xem DB rollback/lock/unique và đối chiếu HTTP/OpenAPI. Fake/H2 không đủ chứng minh JOIN, savepoint hoặc tiền/audit atomic. Ghi rõ test chưa chạy là `NOT VERIFIED`, không điền `PASS` dựa trên source inspection.

## 6. Checklist đóng BE-INT-01 và bàn giao

- [x] Mỗi dòng §2 đã đối chiếu source provider/consumer, chữ ký, scope, TX, lỗi và migration; chênh có điều kiện kỹ thuật cần xử lý.
- [x] `MOD-OI-01..11` có trạng thái hiện hành, nguồn quyết định, người chốt và việc còn thiếu; nội dung cũ trong `05` đã được đồng bộ theo source kiểm kê.
- [x] Bộ vector chung đã có input/oracle và báo cáo kiểm chứng ghi trung thực case chưa chạy.
- [x] Người điều hành/merge owner đã quyết định toàn quyền thực hiện/chốt integration modules theo DEC-INT-003; không có điều kiện sign-off từ cá nhân khác.
- [ ] G0 chỉ đạt khi API/adapter/mapping cần thiết có bằng chứng contract test; G1 (Flyway sạch) thuộc `BE-INT-02`, G2/G3 thuộc `BE-INT-03..12`. Không coi task report là chứng cứ money path/release.

**Verification report:** [BE-INT-01-verification.md](BE-INT-01-verification.md) ghi compile, migration, test đã chạy và blocker. **Trạng thái evidence hiện tại: PARTIAL VERIFIED / G0 BLOCKED.**

## Change log

| Ngày | Nội dung |
|---|---|
| 18/11/2026 | Lập task detail và ma trận kiểm kê tĩnh từ Modules contract v0.7, Java API/candidate và migration hiện có; chưa xác nhận runtime hoặc sign-off. |
| 18/11/2026 | Bổ sung ma trận port dùng chung/Admin, bộ vector review, báo cáo compile thực tế; đồng bộ narrative candidate, Admin runtime và MOD-OI-10/11 trong `05`; DEC-INT-001 tách ID Limit. G0 vẫn BLOCKED. |
| 18/11/2026 | DEC-INT-002: người điều hành/merge owner tự chốt BE-INT-01, bỏ điều kiện hai owner sign-off; ma trận chuyển IN_REVIEW, G0 vẫn theo bằng chứng test thực tế. |
| 18/11/2026 | DEC-INT-003: một người toàn quyền BE-INT-01..12. Sửa build/Admin/Audit/Account-Limit, hợp nhất Flyway V1–V17, cập nhật test fixture; ma trận ghi API còn thiếu và bằng chứng đã chạy. G0 vẫn BLOCKED bởi Transfer/Limit/Identity/recovery contract chưa nối. |
