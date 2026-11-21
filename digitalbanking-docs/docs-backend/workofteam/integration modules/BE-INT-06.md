# BE-INT-06 — Tích hợp Account, Ledger và Limit trong T2

> **Trạng thái** · Verified trong context tích hợp PostgreSQL 16. [Báo cáo và bằng chứng](BE-INT-06-verification.md).
>
> **Người thực hiện và chốt** · Người điều hành tích hợp theo DEC-INT-003.
>
> **Kết quả cần giao** · Từ intent T1 đã commit đến kết quả terminal, với debit/credit, ledger, hạn mức, phản hồi replay và các ghi bắt buộc cùng commit hoặc cùng rollback trên PostgreSQL.

## 1. Baseline và phạm vi

Áp dụng FR-015–021/034, AC-006–012/018/034 và NFR-009–012. BE-INT-05 đã xác minh T0/T1 đến seam `PostCommitSettlement`, dùng fixture cho T2 và dữ liệu terminal dùng để kiểm replay. BE-INT-06 nối executor Account/Ledger/Limit thật và xác minh kết quả HTTP trong context tích hợp. BE-INT-07 chứng minh Fraud, thứ tự C2→C1 và chuỗi Audit. BE-INT-08 chứng minh outbox/delivery đầu-cuối. BE-INT-10 chứng minh recovery worker và Tfail. [SRS](../../../docs-common/03.%20SRS.md) · [BE-INT-05 verification](BE-INT-05-verification.md) · [Kế hoạch tích hợp](README.md)

| Lát tích hợp | Baseline và việc cần giao |
|---|---|
| Transfer | `TransferSettlementCandidate` có executor riêng và entry point JOIN cho recovery. Ghép `PostCommitSettlement`, claim/lock và terminal writer qua Transfer-owned persistence. V13 có attempt metadata và TTL terminal. |
| Account | `AccountBalance` có lock/debit/credit. Map đủ outcome có kiểu, gồm INACTIVE và INSUFFICIENT_BALANCE. Kiểm missing-account trước debit mà vẫn giữ transaction usable. |
| Ledger | `LedgerPosting.postDoubleEntry` JOIN T2 trên V14, insert và đọc lại cặp. Dùng DTO Ledger và ledger balance sau mutation do Account trả. |
| Limit | `TransferLimits` và `TransferLimitAdapter` có check/settle/release theo V20 (DEC-INT-011). T1 đã lưu reservation. T2 phải lấy guard mới và nạp reservation PENDING đã commit qua Limit API. |
| Audit, Fraud, Notification | Dùng Audit C1 bền vững lúc terminal. Giữ seam Fraud trước money và outbox trong T2. Dùng fixture test-only cho seam chưa ghép và ghi rõ giới hạn bằng chứng. |

[Database design §§4.3–4.5](../../../docs-common/04.%20Database%20design%20%26%20analysis.md) · [Modules contract §§7.2, 8.4, 9, 10.2, 11, 13.4](../../../docs-common/design/05.%20Modules%20contract.md) · [BE-TRF-05](../../modules/transfer/tasks/BE-TRF-05.md) · [BE-LED-03](../../modules/ledger/tasks/BE-LED-03.md) · [BE-INT-04 verification](BE-INT-04-verification.md)

## 2. Transaction và thứ tự khóa

1. **Handoff và claim.** Chỉ caller vừa tạo intent thành công gọi settlement sau commit T1. Đọc primary lấy context bất biến để chọn customer guard. Claim ngắn giữ guard → transfer row, CAS trên PENDING/attempt state/epoch và commit trước executor. T2 mở writable READ COMMITTED trên cùng primary datasource/TX manager với providers. Lấy lại guard → lock transfer và re-read intent → xác minh PENDING, epoch/token và claim còn hợp lệ. Stale claim, context lệch hoặc row bận phải dừng trước money. Recovery executor JOIN transaction xử lý của caller theo DEC-TRF-014. HTTP retry cho nonterminal tiếp tục trả 409 và `Retry-After`.
2. **Account và thời gian logic.** Khóa hai account theo `account_id ASC`. Dùng source/destination/amount/currency từ intent, kiểm hai ID khác nhau, source thuộc customer, ACTIVE, VND và available balance của source. Missing hoặc inactive sau T1 là business FAILED nếu TX usable. Mismatch owner/currency của intent đã lưu là invariant failure và alert. Lấy `terminal_at = clock_timestamp()` đúng một lần trên primary sau account locks và trước usage locks. Nhánh account thiếu lấy mốc sau khi xác định outcome, trước release/terminal. Convert sang business date theo zone cấu hình, mặc định `Asia/Ho_Chi_Minh`; mốc trước `created_at` là invariant error.
3. **Reservation và Limit.** Dưới guard và sau transfer/fence re-read, nạp PENDING reservation từ Limit theo transferId/customerId/sourceId/amount/VND/createdAt, lấy T1 date và policy version từ record bền. Thiếu/lệch/đã terminal hoặc lỗi đọc dừng xử lý. `lockAndCheck` chọn config CUSTOMER và ACCOUNT trong một statement theo `terminal_at`, khóa usage CUSTOMER → ACCOUNT rồi kiểm per-transaction/daily. Giữ `Allowed` trong T2. ACCOUNT lần đầu tính từ cutover. Chụp trước nửa đêm vẫn thuộc ngày cũ dù commit ngày sau. Sau check hợp lệ gọi Fraud, trước money.
4. **Money và Ledger.** Mở money savepoint trước debit, sau các khóa cần giữ. Conditional debit APPLIED mới đi tiếp credit. Dùng ledger balance trả về từ cả hai UPDATE cho Ledger posting. Provider kiểm lại đúng seq 1 DEBIT source, seq 2 CREDIT destination, cùng transferId/amount/VND, hai entry ID khác nhau, balanceAfter và correlation khớp, tổng có dấu bằng 0. Sau đó gọi `settleOrReleaseReservation(COMPLETED, Allowed)`, writer usage duy nhất của Transfer. Nhánh business FAILED gọi release với completion null, giữ nguyên amount/count/effect. Lỗi ledger/settlement rollback toàn T2.
5. **Terminal, outbox và audit.** Conditional update đúng một transfer PENDING có fence khớp, kết thúc attempt và ghi `completed_at` hoặc `failed_at`. Update đúng một idempotency record cùng status, masked response và TTL terminal version hợp lệ ≥24h. Lưu `expires_at = max(expires_at hiện tại, terminal_at + TTL_terminal)`; dùng `terminal_at` cho completion timestamp của idempotency, response, Limit và audit. Enqueue notification đã mask trong cùng TX theo policy, flush business, ghi C2 flags theo ID tăng dần khi đã ghép Fraud, rồi C1 `TRANSFER_COMPLETED`/`TRANSFER_FAILED` cuối TX. Chỉ trả terminal sau commit xác nhận. Replay giữ status/body và correlation gốc trong body, header correlation theo request hiện tại.
6. **Timeout và retry.** Áp dụng thiết kế §7.10, mặc định lock timeout 2 giây và budget T2 5 giây. Rà FK/unique, idempotency, outbox, flush và audit-chain lock. Retry cả T2 có giới hạn/backoff cho tranh chấp đã xác nhận rollback, với claim hợp lệ hoặc được claim lại an toàn. Lỗi kỹ thuật không chuyển thành thiếu số dư hay vượt hạn mức. Hết lease hoặc mất kết nối không xác nhận rollback. [Consistency & Concurrency §§5.3, 7, 8](../../../docs-common/design/02.%20consistency-concurrency.md) · [API contracts §§5, 8.3.1](../../../docs-common/07.%20API%20contracts.md)

## 3. Failure và dependency còn thiếu

| Tình huống | Hành vi cần kiểm chứng |
|---|---|
| Business reject trước debit hoặc debit 0 row với outcome rõ | Commit FAILED, release reservation, ghi replay 422, outbox theo policy và C1. Account missing/inactive sau T1 dùng `TRANSFER_FAILED` với `failureCode=ACCOUNT_NOT_FOUND/ACCOUNT_INACTIVE`. Thiếu số dư/vượt hạn mức dùng mã tương ứng của API. Không có biến động tiền, ledger hay usage effect cho transfer đó. |
| Credit lỗi ngay sau debit APPLIED | Rollback money savepoint; xác minh còn lock, TX/context usable và không sót hiệu ứng tiền. Chỉ khi đủ bằng chứng mới commit FAILED 422 `TRANSFER_FAILED` / `PROCESSING_ERROR` trong T2. Nếu proxy đã đánh dấu rollback-only hoặc không chứng minh được trạng thái, rollback toàn T2. Tfail riêng thuộc BE-INT-10 và cần rollback đã xác nhận, fence bền và evidence đủ. |
| Lỗi hệ thống/invariant sau money, gồm Ledger trùng/lệch cặp, reservation mismatch, TTL, outbox hoặc C1 lỗi | Rollback toàn T2. Khi rollback xác nhận, intent và C1 `TRANSFER_CREATED` còn bền, transfer/reservation PENDING và idempotency nonterminal. Lỗi Audit dùng `AUDIT_WRITE_FAILED`; 5xx không được lưu thành terminal replay. |
| Commit không rõ hoặc worker stale | Đọc primary, phân loại theo evidence và claim. Chưa chứng minh outcome thì giữ trạng thái thực tế và alert/manual theo DEC-TRF-006/014. Không post lại, release reservation, ghi terminal hoặc suy từ aggregate usage. |

Limit dùng `findPendingReservation` để lấy ngày T1 và policy version dưới guard T2. Account dùng `findAndLockTransferAccounts` để biểu diễn row thiếu mà không đánh dấu rollback-only. `creditUnderSavepoint` phân biệt 0 row với exception SQL. Fraud ALLOW và policy/mapping enqueue là fixture trong context kiểm thử; outbox persistence và Audit C1 dùng provider thật. [Modules contract §8.4](../../../docs-common/design/05.%20Modules%20contract.md) · [DEC-INT-015](../../../../DECISIONS.md)

## 4. Kiểm thử và điều kiện đóng

| Vector | Bằng chứng cần lưu |
|---|---|
| INT06-01 · Happy path và replay | T1 thật → handoff → claim → Account/Ledger/Limit thật. Đọc từ connection khác sau commit để kiểm hai loại balance, đúng hai entry, SETTLED và scope effects đúng version, terminal replay 201 và C1. Retry giữ số lượng entry/effect/audit. |
| INT06-02 · Business FAILED, config và ngày | Sau T1 đổi trạng thái account hoặc giảm balance, kiểm 422, release, audit/outbox, replay và 0 money/ledger/effect. Exact limit và +0,01, hai scope, Admin cutover, trước/sau 00:00 và clock lùi. Dùng mock có kiểu cho missing account nếu FK canonical ngăn tạo tình huống; ghi phạm vi. |
| INT06-03 · Concurrency và fence | Barrier PostgreSQL cho 20 lệnh theo T-CON-01/02, A→B/B→A, các cặp độc lập và cùng customer khác source cạnh tranh daily cap. Hai executor cùng transfer, epoch/token stale và lease. Chỉ một terminal, số dư không âm, tổng ledger cân và usage không cộng đôi. |
| INT06-04 · Credit và failure injection | Lỗi sau debit, kiểm savepoint usable và rollback-only. Tiêm lỗi sau credit, Ledger post/readback, Limit settlement và terminal/outbox. Query từ connection khác sau rollback để kiểm balance, ledger, counter/effect, reservation, status/key và audit. |
| INT06-05 · Audit, TTL và lỗi kỹ thuật | C1 lỗi sau business flush rollback toàn T2. TTL terminal invalid rollback và TTL giảm không rút expiry. Lock timeout/deadlock có giới hạn và retry chỉ sau rollback xác nhận. Mất response sau commit replay kết quả cũ, commit không rõ đi qua evidence và không thêm writer. |

Dùng PostgreSQL 16, schema Flyway canonical V1–V20 và role runtime. Migration cần thiết phải forward-only. Chạy Gradle `--tests` theo DEC-INT-007 cho integration T2 mới và các lớp bị tác động như `AccountBalancePostgreSqlIntegrationTest`, `LedgerPostingPostgresTest`, `TransferLimitPostgreSqlIntegrationTest`, `TransferSettlementCandidatePostgresTest` và hồi quy `TransferT0T1PostgreSqlIntegrationTest`. Lưu revision, lệnh, migration, boundary fixture/fault, pass/fail/skip và JUnit theo Test Plan §10. [Test Plan §§7, 10](../../../docs-common/09.%20Test%20plan.md)

- [x] Handoff/claim, money T2, terminal/TTL và C1 dùng module API thật.
- [x] INT06-01…05 có bằng chứng, gồm race, rollback sau debit và usage đúng một lần.
- [x] Báo cáo ghi các điều kiện còn thuộc BE-INT-07/08/10. Production create và G0/G2/G3 xét theo kế hoạch tích hợp.
