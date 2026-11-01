
## *Template
## YYYY-MM-DD: Decision

- **Lý do:** ...
- **Phương án lựa chọn:** ...
- **Phương án bị loại:** ...
- **Ràng buộc:** ...

## Decision:

## 2026-10-21: Giữ TransferService gộp (create + confirm) trong Week 1

- **Lý do:** confirm-otp là scope W2/W3; tách ngay = refactor ngoài scope tuần, vi phạm quy tắc "một tính năng tại một thời điểm" (MISMATCH M01).
- **Phương án lựa chọn:** giữ `TransferService` gộp, tách thành `CreateTransferService` + `ConfirmTransferOtpService` khi vào W2.
- **Phương án bị loại:** tách ngay trong W1.
- **Ràng buộc:** không refactor ngoài scope tuần.

## 2026-10-21: Thứ tự validate → parseAmount → verifyPin trong create()

- **Lý do:** request hỏng (amount không canonical, key sai format, self-transfer) bị chặn trước khi đốt PIN attempt của customer. Spec bảng 9 bước để PIN ở bước 2 (trước validate) — đổi thứ tự có chủ đích (MISMATCH M07).
- **Phương án lựa chọn:** validate DTO + amount trước, verifyPin sau — PIN vẫn verify **trước** idempotency claim và money TX.
- **Phương án bị loại:** giữ đúng thứ tự spec (PIN trước validate) — request malformed sẽ đốt PIN attempt.
- **Ràng buộc:** PIN phải chặn trước khi đụng idempotency (Architecture §2).

## 2026-10-21: Migration V8 bổ sung 2 CHECK constraint state machine

- **Lý do:** DB thiếu invariant `AWAITING_OTP → otp_challenge_id NOT NULL` và `expires_at NOT NULL → AWAITING_OTP` (ERD §6) — MISMATCH M06.
- **Phương án lựa chọn:** migration forward-only `V8__transfers_state_invariants.sql`; `TransferJdbcRepository.complete/expire/fail` xoá `expires_at` khi chuyển terminal (M08).
- **Phương án bị loại:** sửa V3/V5 (đã apply) — bị cấm bởi Timelines §9.
- **Ràng buộc:** không sửa migration đã apply; forward-only.

## 2026-10-21: Lock order do PostgreSQL quyết định (ORDER BY id ASC FOR UPDATE)

- **Lý do:** Java `UUID.compareTo()` khác byte-order của PostgreSQL, dễ gây deadlock nếu hai bên tự sắp khác nhau (Architecture §5).
- **Phương án lựa chọn:** `TransferLockRepository` native SQL `WHERE id IN (?, ?) ORDER BY id ASC FOR UPDATE` — thứ tự khóa do DB quyết định; verified bằng `EXPLAIN (ANALYZE, BUFFERS)`.
- **Phương án bị loại:** sắp xếp bằng Java rồi lock từng dòng.
- **Ràng buộc:** không H2 cho lock test; không sleep-sync trong concurrency tests.

## 2026-10-21: Idempotency claim dùng ON CONFLICT DO NOTHING (không DuplicateKeyException)

- **Lý do:** `createOrFind` dùng `ON CONFLICT DO NOTHING` → claim loser không ném exception nên **không** đánh dấu rollback-only cho money TX (rủi ro Architecture §7 nêu với DuplicateKeyException không xảy ra). Claim loser đi qua `replayOrConflict`: khác hash → 409 `IDEMPOTENCY_KEY_REUSED` (MISMATCH M04).
- **Phương án lựa chọn:** giữ `ON CONFLICT DO NOTHING`, xử lý loser ngay trong TX hiện tại.
- **Phương án bị loại:** bắt `DuplicateKeyException` ngoài transaction (cần thiết kế lại repo, chưa có lợi ích).
- **Ràng buộc:** hành vi tương đương Architecture §7.

## 2026-10-21: Chạy build/test bắt buộc với JDK 21

- **Lý do:** system Java 25 làm Mockito/byte-buddy fail ("Could not mock this class") khi chạy test.
- **Phương án lựa chọn:** `JAVA_HOME` trỏ temurin-21; `Makefile` phát hiện qua `/usr/libexec/java_home -v 21` (macOS), override được qua biến môi trường.
- **Phương án bị loại:** chạy bằng Java 25 system mặc định.
- **Ràng buộc:** `make check` phải pass trên máy dev mặc định.

## 2026-11-01: Tách `CreateTransferService` + `ConfirmTransferOtpService`, `TransferPolicy`/`TransferStatus` về `domain/` (đóng M01/M03)

- **Lý do:** W1 giữ `TransferService` gộp có chủ đích; W2 thêm confirm 8 bước làm service gộp quá lớn và trộn 2 transaction script khác nhau (create 7 bước / confirm 8 bước).
- **Phương án lựa chọn:** `application/CreateTransferService`, `application/ConfirmTransferOtpService`; `domain/TransferPolicy`, `domain/TransferStatus`, `domain/TransferExpirationPolicy`. `TransferController` inject cả hai service; query giữ `TransferQueryService`.
- **Phương án bị loại:** giữ service gộp + tách private method — vẫn chung constructor dependency và không cô lập được lock order.
- **Ràng buộc:** refactor thuần cơ học; suite W1 phải xanh trước khi thêm code W2 (đã verify).

## 2026-11-01: Port `IdentityModuleApi` sở hữu toàn bộ OTP lifecycle (đóng M09/M10/M11)

- **Lý do:** `TransferService` inject trực tiếp `OtpChallengeRepository`/`OtpHashingService`/`OtpSender` (internals identity) và `TransferOtpRepository` đọc/ghi bảng `otp_challenges` — vi phạm ERD §4 + Arch §1. Identity `OtpChallengeService.issue` gửi OTP **trong** TX nên không dùng lại được cho create 7 bước (dispatch phải sau COMMIT).
- **Phương án lựa chọn:** `IdentityModuleApi` mở rộng `issueOtp(customerId, purpose) → OtpIssue`, `dispatchOtp(OtpIssue)`, `consumeOtp(challengeId, otp) → OtpConsumeResult`, `invalidateOtp(challengeId)`. `IdentityModuleApiAdapter` insert challenge trực tiếp (không gọi `OtpChallengeService.issue`, để dispatch ngoài TX), lock challenge bằng `SELECT ... FOR UPDATE` theo `id`, tái dùng `OtpHashingService`/`OtpSender`/`OtpChallengeRepository` của identity. Xoá `TransferOtpRepository`; không sửa `identity/**`.
- **Phương án bị loại:** (a) gọi `OtpChallengeService.issue` — gửi OTP trong TX, rollback vẫn gửi; (b) gọi `OtpChallengeService.consume` — yêu cầu phone identifier, không lock theo challenge id.
- **Ràng buộc:** `OtpIssue` trả plaintext code **chỉ** để adapter dispatch sau commit — không lộ ra API response; response Case B chỉ có `expiresAt`/`expiresInSeconds`/`message`.

## 2026-11-01: Idempotency complete trong money TX, dispatch OTP sau COMMIT, compensation khi dispatch fail (AF-03/AF-04)

- **Lý do:** nếu complete idempotency sau khi dispatch thì request replay có thể thấy trạng thái trung gian; nếu dispatch trong TX thì OTP gửi dù transfer rollback (Arch §12).
- **Phương án lựa chọn:** create large path: claim `ON CONFLICT DO NOTHING` → `issueOtp` (chỉ INSERT challenge) → `INSERT transfers AWAITING_OTP` → `complete(idempotency, 200, awaitingBody)` → COMMIT → `dispatchOtp`. Dispatch ném lỗi → `TransactionTemplate` mới: lock transfer + `fail(..., OTP_DISPATCH_FAILED)` với `WHERE status='AWAITING_OTP'` + `invalidateOtp` → `503 SERVICE_UNAVAILABLE`.
- **Phương án bị loại:** dispatch trong TX; hoặc để transfer `AWAITING_OTP` khi dispatch fail (replay sẽ chờ OTP không bao giờ tới).
- **Ràng buộc:** replay sau 503 thấy `FAILED(OTP_DISPATCH_FAILED)` — documented behavior, không retry gửi OTP mới trong W2.

## 2026-11-01: Confirm trả outcome rồi mới ném HTTP error sau COMMIT (AC-06, Task 3.2)

- **Lý do:** `@Transactional` rollback khi exception thoát ra ngoài → attempt OTP tăng/FAILED/EXPIRED sẽ mất nếu ném `ApiException` trong TX.
- **Phương án lựa chọn:** `confirm()` chạy TX trả `ConfirmOutcome(replayed, error)`; sau khi TX commit mới `throw outcome.error()`. Mọi nhánh lỗi (OTP_INVALID 400, ATTEMPTS_EXCEEDED 409, TRANSFER_EXPIRED 409, STATE_CONFLICT 409, INSUFFICIENT_FUNDS/ACCOUNT_NOT_ELIGIBLE 409) đều ghi mutation trong TX trước khi trả outcome.
- **Phương án bị loại:** `REQUIRES_NEW` cho riêng phần ghi attempt — phức tạp hơn và vẫn có cửa sổ commit lệch với money TX.
- **Ràng buộc:** test wrong-OTP 1..4 phải assert attempts đã commit dù HTTP 400; lần 5 assert FAILED + challenge invalidated cùng TX.

## 2026-11-01: Lock order cố định transfer → challenge → accounts ASC

- **Lý do:** confirm khoá transfer → challenge → 2 account; compensation khoá transfer → challenge; create small path khoá 2 account. Giữ một thứ tự cố định giữa các nhánh để tránh deadlock chéo.
- **Phương án lựa chọn:** confirm: `transfers.lock(id)` → `accounts.ownerCustomerId(source)` (đọc, không lock) → `identities.consumeOtp` (lock challenge FOR UPDATE) → `accounts.lockAccountsAscending` → update. Compensation: `transfers.lock` → fail → invalidate challenge. Verify bằng unit `InOrder` + CON-03 (Postgres thật).
- **Phương án bị loại:** lock accounts trước challenge — đảo thứ tự với compensation path.
- **Ràng buộc:** mọi lock account vẫn do PostgreSQL sắp (`ORDER BY id ASC FOR UPDATE`), không dùng `UUID.compareTo`.

## 2026-11-01: Expiry semantics nghiêm ngặt `now.isAfter(expiresAt)`

- **Lý do:** AT-01 yêu cầu boundary rõ ràng và thống nhất giữa Java và SQL. SQL dùng `expires_at < now` (strict) nên Java phải khớp.
- **Phương án lựa chọn:** `TransferExpirationPolicy.resolveEffectiveStatus` chỉ đổi sang `EXPIRED` khi `status == AWAITING_OTP && expiresAt != null && now.isAfter(expiresAt)`. History filter dùng CASE `expires_at < ?`. Confirm tại đúng `expires_at` vẫn thành công (test boundary).
- **Phương án bị loại:** `!now.isBefore(expiresAt)` (inclusive) — lệch với SQL và với docs "now > expires_at".
- **Ràng buộc:** không scheduled job trong MVP (lazy evaluation); nếu thêm job sau chỉ `UPDATE ... WHERE status='AWAITING_OTP' AND expires_at < now()`.

## 2026-11-01: Chấp nhận cross-module read repository trong query path (M13)

- **Lý do:** `TransferQueryService` cần mask account number + phone của counterparty, hiện đọc `accounts`/`customers` qua `AccountJdbcRepository`/`IdentityJdbcRepository`. Chuyển qua module API sẽ thêm 2 method chỉ để đọc hiển thị, trong khi read path không chạm tiền.
- **Phương án lựa chọn:** giữ inject read-only repository trong `TransferQueryService`; ghi nhận M13 là nợ kỹ thuật có kiểm soát, xử lý khi có module API đọc tương ứng.
- **Phương án bị loại:** mở rộng `AccountModuleApi`/`IdentityModuleApi` ngay trong W2 — ngoài scope tuần, rủi ro đổi boundary money path.
- **Ràng buộc:** chỉ SELECT, không ghi; money path tuyệt đối không dùng đường này.

## 2026-11-01: Failure injector qua Spring Profile, no-op ở prod (FI-01)

- **Lý do:** Arch §10/§12 cấm `if (testMode)` trong code nghiệp vụ.
- **Phương án lựa chọn:** interface `application/TransferFailureInjector`; `NoOpTransferFailureInjector @Profile("!transfer-failure-injection")`; test dùng `ThrowingTransferFailureInjector @Profile("transfer-failure-injection")` trong test sources. `FoundationPostgresTest` assert default profile wire bean no-op.
- **Phương án bị loại:** mock bean trong từng test — không chứng minh được cấu hình profile.
- **Ràng buộc:** injector chỉ gọi giữa debit và credit, không có nhánh nào khác.

## 2026-11-01: Mỗi lớp Postgres test tự quản lý Testcontainers (R2)

- **Lý do:** `DisposablePostgresTestSupport` của BE-B chưa có (Week2 §6).
- **Phương án lựa chọn:** mỗi test class tự `@Testcontainers` + `postgres:16-alpine` + Flyway tươi V1–V8, giống pattern W1; đánh dấu TODO đổi sang shared support khi BE-B xong.
- **Phương án bị loại:** shared static container — container sống qua nhiều class nhưng state rò rỉ giữa test.
- **Ràng buộc:** không H2 cho lock test; không sleep-sync (CountDownLatch ready/start + `Future.get(20s)`).
