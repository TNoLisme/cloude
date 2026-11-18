# BE-ID-IMP-07 — StepUpChallengePort: hardening và chuẩn bị tích hợp Transfer

> **Ngày:** 16/11/2026 · **Baseline:** `origin/dev2@56210cc80ad742ddad9139dc1a08fe9ff524465a`
> **Branch:** `datht-backend-rework`
> **Trạng thái task:** **BLOCKED — thiếu Transfer/Limit runtime và protocol được duyệt.** Phần Identity triển khai trong task này không đồng nghĩa hoàn tất integration.
> **Nguồn:** `docs-common/07. API contracts.md` §6, §8.1.5; `04. Database design & analysis.md` §4.2.5; `design/02. consistency-concurrency.md` §5.1–§5.2; `design/05. Modules contract.md` §5.2, §10.1, §13–§14; [DEC-ID-007](../../../../DECISIONS.md).

## 1. Hiện trạng và thẩm quyền

- Task này tái sử dụng implementation `BE-ID-IMP-01..06`, không tạo challenge subsystem thứ hai.
- Transfer, Limit, Account, Ledger và Audit chưa được đấu nối runtime trực tiếp với Identity tại thời điểm thực hiện task (`MOD-OI-01/09`).
- Overview Identity cũ ghi `status='CONSUMED'`, trái `04` §4.2.5; giữ `VERIFIED` + `consumed_at` theo nguồn cao hơn. Security design nêu `403 STEP_UP_INVALID`, trái error catalog `07` §6.6; dùng mã/status của `07`. Các xung đột và giới hạn quyết định đã ghi tại DEC-ID-007.

## 2. Contract và lifecycle

| Operation | Boundary | Điều kiện và side effect |
|---|---|---|
| `StepUpChallengePort.requestChallenge` | OWN/T0 | User do server cung cấp; purpose TRANSFER_STEP_UP bind request hash và idempotency key. INSERT + audit C2 trong transaction riêng; dispatch sau commit, ngoài transaction. Không tạo transfer/idempotency/ledger. |
| `verifyChallenge` | OWN/T0 | OTP kiểm bằng Argon2id hiện có; chỉ method OTP. Authenticated step-up selector chỉ cho TRANSFER_STEP_UP/SENSITIVE_ACTION; LOGIN_NEW_DEVICE phải qua caller riêng chỉ định purpose. Wrong proof không set VERIFIED/consumed. |
| `consumeVerified` | MANDATORY JOIN/T1 | Đúng ref/user/purpose/hash/key, VERIFIED, chưa consumed và còn hạn tại DB. Commit tối đa một consume; rollback caller hoàn `consumed_at`. Không mở transaction consume độc lập. |

Hash ở đây là **SHA-256 của payload chuẩn hóa JCS**, key là **idempotency key binding**, không phải OTP hash hay khóa mật mã mới. Identity không tự hash lại payload Transfer; consumer phải tự normalize và tính hash bằng Common từ payload đã validate và actor đã xác thực. OTP verifier vẫn dùng Argon2id; không đưa OTP/hash/key vào HTTP response.

Binding được kiểm tra nguyên vẹn, không ép tham số thành `CHAR(64)`/`VARCHAR(255)` làm cắt suffix sai thành prefix đúng. Expiry dùng thời gian thực của DB sau khi giữ row lock, không dùng `now()` vốn cố định lúc bắt đầu transaction. Row lock và conditional UPDATE cùng JOIN transaction bảo vệ race, không giữ lock chờ người dùng nhập OTP.

## 3. HTTP và authentication

Production endpoint `POST /api/v1/auth/step-up/verify` theo API-AUTH-05:

- Lấy user ID từ snapshot do `SessionAuthenticationFilter` tạo; không nhận user/purpose/hash/key từ client. Dùng security/CSRF/rate-limit/error pipeline hiện có.
- Body OTP theo `07` §6.4; method khác OTP bị từ chối, không giả lập FIDO/passcode. Challenge LOGIN_NEW_DEVICE không được nâng cấp qua endpoint này.
- Thành công 200 gồm challengeId/status VERIFIED/purpose/verifiedAt/expiresAt/correlationId, `Cache-Control: no-store`; không cấp token/session mới.
- OTP ở buffer `char[]`, wipe khi kết thúc; response/error không echo OTP hoặc binding.

| Tình huống | HTTP / code |
|---|---|
| Challenge SENT chưa VERIFIED khi consume | 428 STEP_UP_REQUIRED + public challenge |
| Wrong hash/key/purpose/method | 409 STEP_UP_CHALLENGE_MISMATCH |
| Không đúng user hoặc không có challenge | 404 AUTH_CHALLENGE_NOT_FOUND |
| OTP sai | 401 AUTH_OTP_INVALID |
| OTP hết hạn khi verify | 410 AUTH_OTP_EXPIRED |
| Challenge hết hạn khi consume | 410 AUTH_CHALLENGE_EXPIRED |
| LOCKED | 423 AUTH_CHALLENGE_LOCKED |
| Đã consumed hoặc transition cấm | 409 INVALID_STATE_TRANSITION |
| Dependency không khả dụng | Fail-closed, không authorize; catalog hệ thống hiện có |

428 dùng RFC 9457 Problem của Common với extension allowlist: challengeId/purpose/methods/expiresAt/maxAttempts/remainingAttempts, channel nếu có. Không chuyển mọi rejection thành 428. Nhánh **thiếu challenge** phải do Transfer đánh giá policy rồi requestChallenge và trả 428; consumer production đó **chưa có**. Không nhận test fixture là implementation của nhánh này.

## 4. Transfer, idempotency và failure

Theo contract, Transfer phải authenticate/validate/authorize → tra idempotency → đánh giá policy → T0 challenge nếu cần → client verify → cùng key/payload vào T1 insert-first và consume → audit CREATED → commit → T2 money path. Terminal replay trả outcome cũ **trước** khi yêu cầu consume lại; payload/actor khác là conflict.

T1 rollback hoàn consume; T1 commit rồi T2 lỗi không hoàn consume, retry giữ transfer identity cũ. Hết hạn sau T1 commit không làm transfer đó phải verify lại. Không reuse challenge để authorize transfer mới. Đây là **nghĩa vụ consumer theo tài liệu**, chưa phải hành vi Transfer đã chứng minh runtime.

MOD-OI-01/09 vẫn chặn gate lũy kế: scope/business date/config version và serialization/reservation chưa được duyệt. `04` §7.7.3 với `07` §5.4/§8.3 còn khác biệt resume/409 khi pending. Không tự chọn policy, không hard-code chỉ ngưỡng từng lệnh, không tạo production fake hoặc sửa bảng module khác để né blocker.

## 5. Kiểm thử và giới hạn evidence

### 5.1. Kết quả đã thực thi

Chạy Java 21, Gradle wrapper, PostgreSQL 16 Alpine qua Docker/Testcontainers; **BUILD SUCCESSFUL trong 2m14s**. Đọc JUnit XML sau execution: **289 tests, 289 passed, 0 failed, 0 errors, 0 skipped** trên 22 test classes. Không chạy lại để suy đoán kết quả; không có failure cần vá trong lần chạy này.

Lệnh tại `digitalbanking-backend/`:

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.identity.*' \
  --tests 'com.simulatedbank.banking.common.config.HttpSecurity*' \
  --tests 'com.simulatedbank.banking.common.error.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --tests 'com.simulatedbank.banking.migration.*' \
  --console=plain
```

| Suite không chồng lặp | Passed | Failed/errors | Skipped |
|---|---:|---:|---:|
| Identity (unit, PostgreSQL và HTTP integration) | 218 | 0 | 0 |
| Common HTTP security | 30 | 0 | 0 |
| Common error contract/HTTP/PostgreSQL pipeline | 26 | 0 | 0 |
| ArchUnit module boundaries | 5 | 0 | 0 |
| Flyway/migration PostgreSQL | 10 | 0 | 0 |
| **Tổng affected regression** | **289** | **0** | **0** |

Chi tiết trong nhóm Identity: `OtpChallengeServiceTest` **34/34** (thêm 5 parameterized invocations); `StepUpConsumptionPostgreSqlIntegrationTest` **29/29**; `StepUpVerificationHttpIntegrationTest` **13/13**. Tổng bổ sung trong task: **47 invocations**. PostgreSQL/HTTP tests dùng implementation Identity/session/filter/error thật; gateway acceptance/capture và consuming controller chỉ là fixture trong test. Không có fake production mới.

Evidence local: `digitalbanking-backend/build/test-results/test/TEST-*.xml` và `digitalbanking-backend/build/reports/tests/test/index.html`; log lệnh trong execution artifact của phiên triển khai. Không phải kết quả toàn bộ repository suite hoặc release/load gate.

### 5.2. Concurrency và security vectors đã PASS

- **A:** 2 connection/transaction consume cùng challenge, **5 lần**; mỗi lần đúng một committed winner, loser `INVALID_STATE_TRANSITION`, persisted `consumed_at` duy nhất.
- **B:** 8 connection/transaction consume cùng challenge, **5 lần**; mỗi lần 1 winner/7 loser, không double consume.
- **D:** wrong hash, wrong key và valid proof chạy đồng thời, **5 lần**; chỉ valid proof consume, hai wrong proof đều mismatch. Không coi các mock invocation tuần tự là race.
- Tất cả worker qua barrier bên trong transaction; transaction giữ lock đợi PostgreSQL xác nhận các worker thật sự blocked qua `pg_stat_activity`/`pg_blocking_pids` rồi mới release. Poll clear statistics snapshot; future/lock/statement/transaction/shutdown có deadline, không dùng arbitrary sleep làm oracle.
- Caller rollback do business/audit exception hoàn consumption; retry transaction mới consume được một lần. Đây là exception injection phía caller, không phải Audit persistence thật.
- Key/hash sai riêng biệt, suffix bị truncate trước đây, padded/missing/malformed binding, ref/user/purpose sai, CANCELLED, SENT, consumed, expiry đều fail-closed. Kiểm cả service validation lẫn repository SQL thật.
- Transaction bắt đầu trước expiry nhưng consume sau expiry bị từ chối; consumer đợi row lock qua expiry bị từ chối cả khi holder commit lẫn rollback. `consumed_at` dùng thời gian thực sau lock.
- HTTP production verify: session/CSRF thật; own/foreign/unknown challenge, wrong OTP, expiry, lockout, method/purpose substitution, malformed input, schema an toàn/no-store. Consumer fixture chứng minh actual RFC 9457 **428** cho SENT chưa verified, counter continuation bằng 0; wrong binding/expiry/consumed giữ đúng **409/410**, không blanket-map thành 428.

### 5.3. Tests BLOCKED, không đánh PASS

Các scenario Transfer **C/E trên execution/idempotency thật** và policy cần/không cần step-up còn BLOCKED vì không có consumer/provider/protocol. Port consume race không chứng minh không duplicate transfer; consumer fixture không chứng minh integration production.

## 6. Acceptance checklist

- [x] Identity port enforce binding nguyên vẹn và single-use trong JOIN transaction; sai proof không consume.
- [x] PostgreSQL Scenario A/B/D chạy thật, mỗi scenario lặp 5 lần.
- [x] Rollback, stale transaction/lock-wait expiry, malformed proof và CANCELLED được kiểm thử.
- [x] Production OTP step-up verify qua authentication/CSRF thật; test-only consumer chứng minh 428 envelope và không gọi continuation.
- [x] Affected regression 289 passed / 0 failed / 0 skipped; docs/checklist cập nhật theo evidence.

- [ ] StepUpChallengePort ghép Transfer T0/T1 production.
- [ ] Missing required step-up trả 428 trước mọi Transfer/idempotency/ledger write thật.
- [ ] Policy Limit/risk đúng, gồm cumulative concurrency.
- [ ] Transfer idempotency không duplicate side effect; exact retry và changed-payload replay đúng contract.
- [ ] Scenario C/E chạy trên production Transfer entrypoints thật.
- [ ] Audit persistence C1/C2 và Notification resolver/provider thật.
- [ ] Toàn bộ BE-ID-IMP-07 IMPLEMENTED + VERIFIED.

Task giữ `[ ]` tại checklist tổng. Không commit/push; không nhận sign-off thay Transfer/Limit/Architect/BA/Security.

## 7. Code paths thay đổi trong task

Các path Java dưới `digitalbanking-backend/src/`:

| File | Mục đích |
|---|---|
| `main/java/com/simulatedbank/banking/identity/api/StepUpChallengePort.java` | Làm rõ purpose selector của verify authenticated, không đổi wire contract. |
| `main/java/com/simulatedbank/banking/identity/application/OtpChallengeService.java` | Ràng buộc method/purpose, validate consume và từ chối fail-closed. |
| `main/java/com/simulatedbank/banking/identity/application/ChallengeRepository.java` | Contract row lock + conditional consume, JOIN/rollback. |
| `main/java/com/simulatedbank/banking/identity/adapter/out/JdbcChallengeRepository.java` | Exact binding, row lock, thời gian thực của DB tại consume. |
| `main/java/com/simulatedbank/banking/identity/adapter/in/StepUpVerificationController.java` | API-AUTH-05, actor từ session, response an toàn/no-store. |
| `main/java/com/simulatedbank/banking/identity/adapter/in/StepUpVerificationRequest.java` | OTP JSON string → wipeable buffer, không serialize/log secret. |
| `test/java/com/simulatedbank/banking/identity/OtpChallengeServiceTest.java` | Bổ sung purpose/method substitution và authenticated step-up selector. |
| `test/java/com/simulatedbank/banking/identity/StepUpConsumptionPostgreSqlIntegrationTest.java` | Kiểm persistence thật, race và expiry/binding/rollback. |
| `test/java/com/simulatedbank/banking/identity/StepUpVerificationHttpIntegrationTest.java` | HTTP/session/CSRF/verification thật; consuming fixture chỉ test. |

Không thêm migration, không sửa V1/V2, không tạo state CONSUMED, không ghi trực tiếp bảng module khác.
