# Tiến độ dự án

## Trạng thái hiện tại
Week 2 (Large Transfer, OTP Lifecycle, Concurrency) **hoàn thành 01/11/2026** — toàn bộ checklist & evidence trong [`docs transactions/Work/Week2.md`](docs transactions/Work/Week2.md). Full backend suite xanh: **215 tests / 0 fail** (Java 21 + Testcontainers postgres:16-alpine), `make check` pass.

## Đã hoàn thành
- W1/D1: Small transfer path 9 bước + UT-01..06 + happy-path integration 50K (Postgres thật) — ports `AccountModuleApi`/`IdentityModuleApi` + `TransferLockRepository`
- W1/D2: Recipient resolve tách `ResolveRecipientService`; concealment 404 `RECIPIENT_NOT_AVAILABLE`; rate limit 30/phút theo customer; masking 4 số cuối
- W1/D3: Query skeleton — cursor Base64 URL-safe, `limit+1`, visibility source/destination (QV-01..05)
- W1/D4: State machine chốt — migration **V8** (`transfers_awaiting_requires_otp`, `transfers_expiry_only_awaiting`), lock order `ORDER BY id ASC FOR UPDATE` có EXPLAIN
- W2/D1: Tách `CreateTransferService` + `ConfirmTransferOtpService`, `TransferPolicy`/`TransferStatus`/`TransferExpirationPolicy` về `transfer/domain/` (đóng M01/M03)
- W2/D2: Boundary OTP M09–M11 — `IdentityModuleApi` thêm `issueOtp`/`dispatchOtp`/`consumeOtp`/`invalidateOtp`; adapter sở hữu internals identity; xoá `TransferOtpRepository`; không sửa `identity/**`
- W2/D3: Large create 7 bước (issue OTP trong money TX, dispatch sau COMMIT, compensation `FAILED(OTP_DISPATCH_FAILED)` + 503) + confirm 8 bước (outcome-after-commit, wrong OTP persist, lock order transfer → challenge → accounts)
- W2/D4: CON-01..03 + FI-01 pass PostgreSQL thật; lazy expiry AT-01..03; history filter `status/from/to` + QV-01..06; injector qua Spring Profile (NoOp prod)
- `Makefile` gốc repo với target `check` (openapi + mvn test + git diff --check)

## Đang thực hiện
- Không — W2 đóng. W3 dự kiến 08/11 – 14/11: CON-04/05 (duplicate-confirm/block race với BE-A), FI-02..05, risk rules hoàn chỉnh, load test, chuyển evidence concurrency sang `evidence/BE3-concurrency.md`.

## Vấn đề đã biết
- **Java version**: system Java 25 làm Mockito/byte-buddy fail khi chạy test. Bắt buộc dùng JDK 21: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` (macOS). `Makefile` tự phát hiện qua `/usr/libexec/java_home -v 21`; máy khác override `JAVA_HOME`.
- Docker image backend compose (build 19/10) chưa chứa migration V8 — áp dụng ở lần build image tiếp theo; V8 đã được verify qua Testcontainers (Flyway tươi V1–V8).
- **Đề xuất cho BE-A (identity)**: chuẩn hoá contract OTP lifecycle giống `IdentityModuleApi` của transfer (`issue` không gửi trong TX, `dispatch` sau COMMIT, `consume` lock theo challenge id, `invalidate`); hiện adapter transfer dùng raw SQL lock theo id vì `OtpChallengeService.consume` yêu cầu phone.
- **Nợ kỹ thuật có kiểm soát (M13)**: `TransferQueryService` inject read-only `AccountJdbcRepository`/`IdentityJdbcRepository` để mask/projection — không chạm money path; xử lý khi có module read API.

## Các bước tiếp theo
1. W3 (08/11 – 14/11) theo Timelines §4: CON-04/05 phối hợp BE-A; FI-02..05 (timeout-after-commit, restart, compensation race); risk rules; load test
2. Chuyển evidence CON-01..03 sang `evidence/BE3-concurrency.md` khi finalize W3
3. Build lại docker image backend để compose local chạy V8
4. Đề xuất BE-A cập nhật API docs §8 theo M15 (OtpIssue + dispatch/invalidate)
