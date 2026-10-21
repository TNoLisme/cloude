
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
