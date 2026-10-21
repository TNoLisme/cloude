# Tiến độ dự án

## Trạng thái hiện tại
Week 1 (Foundation, Recipient Resolve, Small Transfer) **hoàn thành 21/10/2026** — toàn bộ checklist & evidence trong [`docs transactions/Work/Week1.md`](docs transactions/Work/Week1.md). Full backend suite xanh: **183 tests / 0 fail** (Java 21 + Testcontainers postgres:16-alpine).

## Đã hoàn thành
- W1/D1: Small transfer path 9 bước + UT-01..06 + happy-path integration 50K (Postgres thật) — `TransferService` + ports `AccountModuleApi`/`IdentityModuleApi` + `TransferLockRepository`
- W1/D2: Recipient resolve tách `ResolveRecipientService`; concealment 404 `RECIPIENT_NOT_AVAILABLE`; rate limit 30/phút theo customer; masking 4 số cuối
- W1/D3: Query skeleton — cursor Base64 URL-safe, `limit+1`, visibility source/destination (QV-01..05)
- W1/D4: State machine chốt — migration **V8** (`transfers_awaiting_requires_otp`, `transfers_expiry_only_awaiting`), lock order `ORDER BY id ASC FOR UPDATE` có EXPLAIN
- `Makefile` gốc repo với target `check` (openapi + mvn test + git diff --check)

## Đang thực hiện
- W2 (chưa bắt đầu): large path confirm-otp, tách `CreateTransferService`/`ConfirmTransferOtpService` (M01), di chuyển `TransferPolicy` sang `domain/` (M03)

## Vấn đề đã biết
- **Java version**: system Java 25 làm Mockito/byte-buddy fail khi chạy test. Bắt buộc dùng JDK 21: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home` (macOS). `Makefile` tự phát hiện qua `/usr/libexec/java_home -v 21`; máy khác override `JAVA_HOME`.
- Docker image backend compose (build 19/10) chưa chứa migration V8 — áp dụng ở lần build image tiếp theo; V8 đã được verify qua Testcontainers (Flyway tươi V1–V8).

## Các bước tiếp theo
1. W2: confirm-otp lifecycle (SM-06.., CON-*, FI-*) theo `docs transactions/Work/` tuần 2
2. Tách `CreateTransferService` / `ConfirmTransferOtpService` (M01) khi bắt đầu W2
3. Build lại docker image backend để compose local chạy V8
