# Tiến độ dự án

## Trạng thái hiện tại
Week 3 (Races, Risk, Acceptance, Handover) **hoàn thành 01/11/2026** — sớm hơn re-baseline 08–14/11; checklist & evidence trong [`docs transactions/Work/Week3.md`](docs transactions/Work/Week3.md) + [`docs transactions/evidence/`](docs transactions/evidence/). Full backend suite xanh: **230 tests / 0 fail** (66 classes, Java 21 + Testcontainers postgres:16-alpine), `make check` pass lúc 2026-11-01T09:49:54Z. D5 load test ghi **blocked-note** có điều kiện (k6 chưa cài, backend image stale, thiếu demo credentials/kịch bản FE) — không ghi PASS.

## Đã hoàn thành
- W1/D1: Small transfer path 9 bước + UT-01..06 + happy-path integration 50K (Postgres thật) — ports `AccountModuleApi`/`IdentityModuleApi` + `TransferLockRepository`
- W1/D2: Recipient resolve tách `ResolveRecipientService`; concealment 404 `RECIPIENT_NOT_AVAILABLE`; rate limit 30/phút theo customer; masking 4 số cuối
- W1/D3: Query skeleton — cursor Base64 URL-safe, `limit+1`, visibility source/destination (QV-01..05)
- W1/D4: State machine chốt — migration **V8** (`transfers_awaiting_requires_otp`, `transfers_expiry_only_awaiting`), lock order `ORDER BY id ASC FOR UPDATE` có EXPLAIN
- W2/D1: Tách `CreateTransferService` + `ConfirmTransferOtpService`, `TransferPolicy`/`TransferStatus`/`TransferExpirationPolicy` về `transfer/domain/` (đóng M01/M03)
- W2/D2: Boundary OTP M09–M11 — `IdentityModuleApi` thêm `issueOtp`/`dispatchOtp`/`consumeOtp`/`invalidateOtp`; adapter sở hữu internals identity; xoá `TransferOtpRepository`; không sửa `identity/**`
- W2/D3: Large create 7 bước (issue OTP trong money TX, dispatch sau COMMIT, compensation `FAILED(OTP_DISPATCH_FAILED)` + 503) + confirm 8 bước (outcome-after-commit, wrong OTP persist, lock order transfer → challenge → accounts)
- W2/D4: CON-01..03 + FI-01 pass PostgreSQL thật; lazy expiry AT-01..03; history filter `status/from/to` + QV-01..06; injector qua Spring Profile (NoOp prod)
- W3/D1: CON-04 duplicate-confirm + CON-05 block-vs-confirm pass PostgreSQL thật; CON-05 dùng `AccountStatusService.change` thật; FI-04 cả hai thứ tự confirm/compensation
- W3/D2: FI-02 timeout-retry, FI-03 dispatch compensation, FI-05 restart bằng Spring context thứ hai (cùng DataSource) pass; AF-05 accepted limitation ghi rõ
- W3/D3: Risk AFTER_COMMIT integration — LARGE_TRANSFER (>5M), HIGH_FREQUENCY (>5/10 phút), failure isolation AF-06, rollback không trigger risk, terminal guard matrix
- W3/D4: Full acceptance 230 tests / 66 classes / 0 fail; `make check` pass; evidence 3 file + handover FE/BE-A/BE-B
- W3/D5: Load test k6 20 RPS × 10 phút **blocked-note** (chưa chạy) — điều kiện mở lại trong `evidence/BE3-handover.md §9`
- `Makefile` gốc repo với target `check` (openapi + mvn test + git diff --check)

## Đang thực hiện
- Không — W3 đóng. Milestone module transactions (W1–W3) hoàn thành phần BE; hạng mục mở: load test R5, AC-06 PIN lockout concurrent phía BE-A, xác nhận FE/BE-A/BE-B.

## Vấn đề đã biết
- **Java version**: system Java 25 làm Mockito/byte-buddy fail khi chạy test. Bắt buộc dùng JDK 21: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` (macOS). `Makefile` tự phát hiện qua `/usr/libexec/java_home -v 21`; máy khác override `JAVA_HOME`.
- Docker image backend compose (`cloude-backend:latest`, build 2026-10-21) chưa chứa migration V8 — cần `docker compose build backend` trước khi chạy compose; V8 đã verify qua Testcontainers (Flyway tươi V1–V8).
- **Đề xuất cho BE-A (identity)**: chuẩn hoá contract OTP lifecycle giống `IdentityModuleApi` của transfer (`issue` không gửi trong TX, `dispatch` sau COMMIT, `consume` lock theo challenge id, `invalidate`); hiện adapter transfer dùng raw SQL lock theo id vì `OtpChallengeService.consume` yêu cầu phone (M15).
- **AC-06 phần PIN lockout concurrent**: transfer chỉ nhận `403 PIN_LOCKED`; test đồng thời 5 lần sai PIN phía identity thuộc BE-A — chưa có trong suite transfer.
- **Load test R5 chưa chạy**: k6 chưa cài, `.env` không có `DEMO_*`, chưa có kịch bản FE; blocked-note + điều kiện mở lại tại `docs transactions/evidence/BE3-handover.md §9`.
- **MISMATCH M17**: API docs ghi `429 RATE_LIMITED` cho create transfer nhưng interceptor hiện chỉ áp `recipient-resolve`/`operator-lookup`; không sửa module shared ngoài scope transfer.
- **Nợ kỹ thuật có kiểm soát (M13)**: `TransferQueryService` inject read-only `AccountJdbcRepository`/`IdentityJdbcRepository` để mask/projection — không chạm money path; xử lý khi có module read API.

## Các bước tiếp theo
1. Build lại docker image backend (`docker compose build backend`) để compose local có V8
2. Chạy load test R5 khi có k6 + data/kịch bản: 20 RPS × 10 phút, invariant zero negative balance/duplicate debit, post-load reconciliation (Task 2.4)
3. BE-A: chốt M15 OTP lifecycle contract; bổ sung test AC-06 PIN lockout concurrent; xác nhận CON-05
4. FE: walkthrough matrix trong `evidence/BE3-handover.md §1–§6`; xác nhận bàn giao
5. BE-B: xác nhận giữ per-class Testcontainers (B4) hoặc migrate `DisposablePostgresTestSupport`

### BE-B — Test Infrastructure, Resilience, Audit/Risk & Integration Lead
- **Hạ tầng Test PostgreSQL cô lập:** Tạo DisposablePostgresTestSupport.java quản lý lifecycle DB an toàn trên Testcontainers, không đè dữ liệu thật.
- **Kịch bản phục hồi sự cố (Resilience / DR):** Viết DatabaseFailureRecoveryTest.java dùng Toxiproxy kiểm chứng ngắt mạng DB báo lỗi 503 an toàn, context restart tự hồi phục và giữ nguyên tính idempotency.
- **Kiểm chứng Audit & Risk:** Hoàn tất test PII redaction, audit append-only và logic rủi ro (ngưỡng 5M, tần suất 5/6, AFTER_COMMIT, chống duplicate) (BE4-audit-risk.md).
- **Dữ liệu k6 & SQL Bất biến số dư:** Viết scripts/seed-k6-accounts.sql (100 account) và queries/verify-financial-invariant.sql đo bảo toàn 1 tỷ VND.
- **Bảo mật Container & Nâng cấp Spring Boot:** Nâng cấp Spring Boot 3.4.13, Tomcat 10.1.60, Postgres driver 42.7.14, Springdoc 2.8.5; giảm 75.4% CVE (BE4-container-scan.md); Swagger UI và /v3/api-docs chạy ổn định.
- **Hồ sơ Evidence & Trạng thái nghiệm thu:** Chuẩn hóa toàn bộ tài liệu nghiệm thu trong docs transactions/evidence/; xác nhận 165/165 backend test pass, frontend typecheck/test/build pass 100%.

### BE-A — Authentication, Security & Account Lifecycle
- **Xác thực & Phân quyền (Authentication & RBAC):** Hoàn thiện xác thực JWT Access Token, phân quyền theo 3 vai trò CUSTOMER, OPERATOR, AUDITOR; kiểm soát truy cập API và ngăn chặn truy cập trái quyền.
- **Refresh Token Rotation & Revocation:** Hoàn thiện cơ chế xoay vòng Refresh Token, phát hiện token bị tái sử dụng (Reuse Detection), tự động thu hồi toàn bộ Token Family bằng PostgreSQL Recursive CTE; xử lý concurrent refresh và grace period 10 giây.
- **Bảo vệ PIN/OTP & Chống Brute-force:** Giới hạn 5 lần xác thực sai, khóa PIN trong 15 phút, tự động reset bộ đếm sau khi hết hạn; OTP có thời hạn, sử dụng một lần và được bảo vệ bằng PostgreSQL row-level locking.
- **Chống đăng ký trùng Email/SĐT:** Chuẩn hóa email, áp dụng PostgreSQL UNIQUE/CHECK constraints; xử lý an toàn các yêu cầu đăng ký đồng thời, không tạo dữ liệu customer/account trùng hoặc mồ côi.
- **Chống IDOR & Bảo vệ dữ liệu khách hàng:** Kiểm soát quyền sở hữu tài khoản theo customerId; ngăn Customer truy cập tài khoản người khác và chặn thao tác Operator trái quyền.
- **Chống nạp tiền trùng & Pessimistic Locking:** Áp dụng Idempotency-Key, kiểm tra request payload và SELECT ... FOR UPDATE để bảo vệ số dư; xác minh các giao dịch nạp đồng thời không gây cộng tiền trùng hoặc lost update.
- **Quản lý trạng thái tài khoản (Account Lifecycle):** Hoàn thiện khóa/mở tài khoản ACTIVE/BLOCKED, ngăn mở lại tài khoản CLOSED, chặn nạp tiền vào tài khoản bị khóa và ghi nhận audit events khi thay đổi trạng thái.
- **Khôi phục tài khoản & Session Security:** Hoàn thiện password recovery, chống user enumeration, tự động thu hồi toàn bộ refresh sessions sau khi đặt lại mật khẩu.
- **Kiểm thử & Nghiệm thu BE-A:** Hoàn thành kiểm thử bảo mật, concurrency và PostgreSQL integration tests bằng Testcontainers. Kết quả: 196/196 tests PASS, 0 Failures, 0 Errors, 0 Skipped. OpenAPI validation PASS (31 operations).
- **CI & Evidence:** Bằng chứng nghiệm thu lưu tại docs transactions/evidence/BEA-auth-account.md.
