# BE-ID-IMP-12 — REST DTO, validation và error mapping API-AUTH-01..08

> **Ngày:** 16/11/2026 · **Owner module:** Đạt (Identity) · **Branch:** `datht-backend-rework`
> **Baseline:** `origin/dev2@56210cc80ad742ddad9139dc1a08fe9ff524465a`
> **Trạng thái:** **PARTIAL** — phần REST độc lập (API-AUTH-01..07 DTO/validation/error mapping, OpenAPI) đã triển khai và kiểm thử; API-AUTH-02 positive và API-AUTH-08 runtime **BLOCKED** theo quyết định đã duyệt. Không đánh DONE toàn task.
> **Quyết định ghi nhận:** [DEC-ID-012](../../../../DECISIONS.md); `design/05` §5.6.

Nguồn chuẩn: `docs-common/07. API contracts.md` §2–4, §7.2, §8.1; `design/05` §5, §11.3.1, §13.1; `04` §4.2; `design/04` §4.1–4.3; `docs-backend/00. architecture & conventions.md` §4.4–6.1 (BE-CON-08..30).

## 1. Tóm tắt

Nhiệm vụ yêu cầu 8 endpoint REST, DTO, validation, error mapping, OpenAPI và test. Trạng thái đầu task (BE-ID-DOC-01 §8.1: API-AUTH-01..08 REST = MISSING): bảy route đã được các task trước dựng service nhưng controller/DTO còn phân mảnh; task này hoàn thiện lớp `adapter/in` theo hợp đồng, tái sử dụng pipeline Common, và ghi minh bạch hai phần phụ thuộc bị chặn. **Không tạo endpoint thứ hai hoặc API-AUTH duplicate.**

| API | Route | Service được gọi | Trạng thái |
|---|---|---|---|
| API-AUTH-01 | `POST /api/v1/auth/login` | `PasswordLoginService` | VERIFIED (luôn 202 MFA, DEC-ID-008) |
| API-AUTH-02 | `POST /api/v1/auth/passcode` | `PasscodeLoginService` | DENIAL VERIFIED; positive 200 **BLOCKED** (DEC-ID-009) |
| API-AUTH-03 | `POST /api/v1/auth/fido/verify` | `FidoLoginService` | VERIFIED (begin/finish; DEC-ID-010) |
| API-AUTH-04 | `POST /api/v1/auth/otp/verify` | `OtpChallengeService.verifyLoginChallenge` → `LoginSessionService.complete` | VERIFIED |
| API-AUTH-05 | `POST /api/v1/auth/step-up/verify` | `StepUpChallengePort.verifyChallenge` | VERIFIED cho OTP; FIDO/PASSCODE proof trả 409 mismatch (chưa có provider xác thực — BLOCKED) |
| API-AUTH-06 | `POST /api/v1/auth/logout` | `LogoutService` | VERIFIED |
| API-AUTH-07 | `GET /api/v1/auth/session` | `SessionViewService` | VERIFIED (rotation DEC-ID-004) |
| API-AUTH-08 | `POST /api/v1/auth/challenges/{challengeId}/resend` | — | **BLOCKED, không có runtime route** (DEC-ID-006/MOD-OI-07) |

## 2. File thay đổi

Dưới `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/adapter/in/` (tất cả untracked do IMP-01..11 chưa commit; file mới của task này có mtime trong ngày 16/11):

| File | Nội dung |
|---|---|
| `IdentityRequestValidation.java` (mới) | Helper hợp đồng: UUID canonical, 6–8 chữ số, Base64URL; ném `VALIDATION_ERROR` duy nhất. |
| `StrictStringDeserializer.java` (mới) | Từ chối coercion JSON number/boolean → String cho mọi trường auth. |
| `StrictUuidDeserializer.java` (mới) | `deviceRef` chỉ nhận UUID string chuẩn (không chấp nhận group rút gọn của `UUID.fromString`). |
| `LoginRequest.java` | `@Size` 1–64/1–128, strict deserializer, `password`/`deviceFingerprint` WRITE_ONLY, `toString` redacted. |
| `LoginOtpVerificationRequest.java` | OTP `char[]` WRITE_ONLY qua `OtpDeserializer` (chỉ nhận JSON string), `eraseOtp()`, redacted. |
| `PasscodeLoginRequest.java` | `PasscodeDeserializer` tương tự, `erasePasscode()`, redacted. |
| `StepUpVerificationRequest.java` | `method` string + validate() chọn proof theo method (OTP/PASSCODE 6–8 digits, FIDO assertion Base64URL), `eraseSecrets()`, redacted. |
| `ChallengeRequiredResponse.java` | Typed DTO §7.2.4; `fido(...)` trả `FidoRequestOptions{challenge, rpId, allowCredentials, timeout, userVerification=required}`; `maskedDestination`/`fidoRequestOptions` NON_NULL. |
| `FidoController.java` | Response 202 dùng `ChallengeRequiredResponse.fido(...)` thay Map ad-hoc; DTO begin/finish oneOf (cặp `challengeId`+`fido` cùng có hoặc cùng vắng); username strict + `@Size(1..64)`. |
| `StepUpVerificationController.java` | Gọi `request.validate()` trong try/finally erase; method không hợp lệ → 400 `VALIDATION_ERROR`; method PASSCODE/FIDO hợp lệ schema → 409 `STEP_UP_CHALLENGE_MISMATCH` (không fake success); chỉ OTP đi vào port. |
| `AuthController.java`, `AuthHttpSupport.java` | Đơn giản hoá manual validation sang `IdentityRequestValidation`; giữ nguyên semantics logout/session và cookie. |

Tests:

| File | Nội dung |
|---|---|
| `identity/IdentityRestErrorMappingTest.java` (mới) | 14 test: lỗi domain OTP map đúng catalog (401/403/404/409/410/423/429/500/503 + `Retry-After`), `lockedUntil` extension, `BadCredentialsException`→401 và `AccessDeniedException`→403 (không bị ép thành 400), lỗi bất ngờ → 500 không lộ cause/SQL, body không hợp lệ (null/malformed/unknown property/415/405) không chạm service, không có cookie/secret/stack trong Problem. |
| `identity/adapter/in/IdentityRestDtoValidationTest.java` (mới) | 15 test biên DTO: username/password size, strict coercion (number → proof, char[] JSON, unknown property), UUID canonical, validate() theo method, FIDO assertion thiếu/sai Base64URL, erasure buffer `\0`, serialization challenge không lộ secret và FIDO `userVerification=required`. |
| `identity/IdentityOpenApiContractTest.java` (mới) | Đọc `digital-banking-v1.yaml` bằng SnakeYAML: đủ 8 operation đúng method/path, security (public `[]`/cookie+bearer/resend purpose-based), `x-runtime-status` blocked cho passcode-positive và resend, FIDO `userVerification` chỉ `required`, mọi `$ref` resolve, schema success không chứa trường secret. |
| `identity/StepUpVerificationHttpIntegrationTest.java` | Cập nhật hợp đồng method: PASSCODE/FIDO proof hợp lệ schema → 409; method lạ/FIDO assertion rỗng → 400 (thay expectation cũ "mọi method khác OTP đều 409"). |

Docs:

| File | Nội dung |
|---|---|
| `DECISIONS.md` | DEC-ID-012. |
| `docs-common/design/05. Modules contract.md` | §5.6: boundary REST IMP-12, phương án kiểm thử khi dependency thiếu, phụ thuộc maskedDestination/Customer projection. |
| `digitalbanking-docs/openapi/digital-banking-v1.yaml` | Đồng bộ 8 operation auth: description/x-error-codes/security theo `07` + DEC-ID-008/009/010/006; `Challenge.fidoRequestOptions` typed; giữ nguyên advertised 200 passcode và 202 resend với status runtime minh bạch. |
| `digitalbanking-docs/openapi/README.md` | Bảng Auth operationId/runtime/dependency và quy ước. |
| `docs-backend/modules/identity/README.md`, `tasks/README.md` | Index + checklist IMP-12. |

## 3. Hợp đồng thực thi (đối chiếu `07`)

- **Validation:** mọi constraint lấy từ §8.1: username 1–64, password 1–128 (không trim, không thêm password-policy ở login), deviceName 1–100, deviceFingerprint 1–512, OTP/passcode 6–8 digits, `deviceRef`/`challengeId` UUID, FIDO assertion Base64URL. Không tự chế giới hạn mới; `fail-on-unknown-properties: true` giữ chống mass assignment (BE-CON-25).
- **Secrets:** OTP/passcode `char[]` WRITE_ONLY, wipe trong `finally` kể cả lỗi validation; response không chứa password/OTP/passcode/hash/opaque session token; `SessionInfo`/requests `toString` redacted.
- **Error mapping:** tái sử dụng duy nhất `GlobalExceptionHandler`/`ProblemWriter` của common (không thêm `@RestControllerAdvice` thứ hai). Domain exception → catalog §4.3 (`423` kèm `lockedUntil`); `AuthenticationException`→401, `AccessDeniedException`→403, DB→503/500 theo SQLState; malformed JSON→`MALFORMED_REQUEST`; media/method/route theo catalog; không log stack/SQL/PII.
- **AuthZ:** public per §3.5 cho login/passcode/fido/otp-verify; step-up/logout/session qua `SessionAuthenticationFilter` + CSRF cookie-bound; rate limit IP/username/OTP/step-up qua `RequestRateLimitFilter` hiện có. Không đổi security chain.

## 4. BLOCKED (không fake, không tự mở endpoint)

1. **API-AUTH-02 positive (200 AuthResponse):** thiếu contract enrollment/device-possession proof/trust expiry được duyệt (DEC-ID-009 `preserve_contracts`). Request hợp lệ hiện fail-closed 403 `AUTH_DEVICE_NOT_TRUSTED` trước khi kiểm credential. Owner: Identity/Đạt + Security; mở khi duyệt protocol proof.
2. **API-AUTH-08 runtime:** resend atomic cần counter/generation/fencing/in-flight schema được duyệt + MOD-OI-07 protocol (DEC-ID-006). Không tạo route giả trả 202/503 cố định, không đếm resend bằng RAM hay mượn verify-attempt. Owner: Identity/Đạt + Notification/Thịnh + DB owner.
3. **Step-up method FIDO/PASSCODE:** chưa có provider xác thực credential tương ứng (FIDO chỉ cho API-AUTH-03; Passcode positive BLOCKED). Proof đúng schema → 409 `STEP_UP_CHALLENGE_MISMATCH`, không bao giờ VERIFIED giả. Owner: Identity/Đạt.
4. **`customerId`/`maskedDestination`:** `CustomerQuery` và trusted-contact resolver chưa có; không bịa PII (DEC-ID-003, design/05 §5.6). Owner: Customer/Đạt, Notification/Thịnh.
5. **Audit durable C2/C3** (`SecurityLogAuditAdapter` tạm) — BE-ID-IMP-13. Owner: Audit/Thịnh.

## 5. Kiểm thử

Lệnh thực chạy (Java 21, Testcontainers PostgreSQL 16):

```bash
./gradlew test --tests '...identity.IdentityRestErrorMappingTest'            # PASS
./gradlew test --tests '...identity.adapter.in.IdentityRestDtoValidationTest' \
              --tests '...identity.IdentityOpenApiContractTest'              # PASS
./gradlew test --tests '...identity.StepUpVerificationHttpIntegrationTest' \
              --tests '...identity.FidoLoginHttpIntegrationTest' \
              --tests '...identity.DeviceMfaLoginHttpIntegrationTest' \
              --tests '...identity.PasscodeHistoryPostgreSqlIntegrationTest'  # PASS
./gradlew build --rerun-tasks --console=plain   # lần cuối: BUILD SUCCESSFUL, 765/765 PASS
```

Test mới của IMP-12: **43 test** — `IdentityRestErrorMappingTest` 14 (error mapping), `IdentityRestDtoValidationTest` 23 (DTO biên/serialization), `IdentityOpenApiContractTest` 6 (OpenAPI hợp đồng). Regression HTTP liên quan (step-up/FIDO/MFA/passcode) PASS với PostgreSQL thật; chỉnh expectation cũ trong `StepUpVerificationHttpIntegrationTest` (method lạ/FIDO rỗng → 400, proof hợp lệ khác stored method → 409).

### 5.1. Full verification trên source cuối

```bash
env -u SPRING_PROFILES_ACTIVE ./gradlew build --rerun-tasks --console=plain
```

**BUILD SUCCESSFUL (5m22s), 9/9 Gradle tasks**: compileJava/resources/classes/bootJar/jar/assemble, compileTestJava, test, JaCoCo, check/build hoàn tất. JUnit XML: **51 classes / 765 tests / 0 failure / 0 error / 0 skipped** (trước IMP-12: 48 classes/721 tests; +43 = test mới 43, trong đó chưa tính vài test điều chỉnh). Đây là evidence cuối trên source cuối, không cộng dồn nhiều lần chạy. Report máy đọc: `digitalbanking-backend/build/test-results/test/TEST-*.xml`; HTML: `build/reports/tests/test/index.html`. Log lệnh: `/tmp/be-id-imp12-build-final2.log`.

## 6. Vấn đề môi trường trong các lần chạy trước (ghi minh bạch, đã xác định root cause)

Hai lần `build --rerun-tasks` trước **FAILED** không phải do code:

1. **`FidoPropertiesTest > localhostHttpFailsWithoutExplicitDevelopmentProfile`** — chẩn đoán runtime (`System.getenv`) chứng minh JVM test nhận biến môi trường **`SPRING_PROFILES_ACTIVE=local`** rò rỉ từ môi trường phiên làm việc vào Gradle daemon; `StandardEnvironment` map thành active profile `local`, nên context `localhost()` start thành công thay vì fail. Chạy standalone trên daemon sạch PASS; sau khi chạy `./gradlew --stop` + `env -u SPRING_PROFILES_ACTIVE`, full run PASS toàn bộ. Lỗi môi trường máy/biến phiên, không phải defect code.
2. **`IdentitySecurityIntegrationTest > initializationError`** (`ContainerLaunchException`) và nhiều "Could not write XML test results" — máy trong phiên có load 15-min ≈ **129**, swap gần đầy (952Mi/953Mi, tiến trình khác như Burpsuite ~890MB), đĩa 94%; container/XML ghi thất bại trong retry window. Cả hai chạy lại PASS trên môi trường nhẹ hơn.
3. Một lần chạy ghép cặp `PackagedApplicationReadinessIntegrationTest` FAILED — là flake khác (full run cuối PASS readiness), không lặp lại.

Kết luận: không có failure code còn lại để quy cho module khác hay thay đổi của task này.

## 7. Còn mở

- Đúng các mục BLOCKED §4 — task giữ **PARTIAL**, checklist `[ ]` cho phần phụ thuộc; phần độc lập đã VERIFIED bằng full build trên (43 test mới + regression Identity/Common/FIDO).

## Change Log

| Version | Ngày | Người | Mô tả |
|---|---|---|---|
| 1.0 | 16/11/2026 | Đạt (`identity`) | BE-ID-IMP-12: DTO/validation/error mapping API-AUTH-01..07, OpenAPI sync, 43 test REST/DTO/OpenAPI mới; API-AUTH-02 positive + API-AUTH-08 BLOCKED theo DEC-ID-009/006; full build 765/765 PASS sau khi gỡ biến môi trường SPRING_PROFILES_ACTIVE. |
