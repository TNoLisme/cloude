# BE-ID-IMP-08 — Device recognition và MFA login

> **Ngày:** 16/11/2026 · **Baseline:** `origin/dev2@56210cc80ad742ddad9139dc1a08fe9ff524465a`
> **Branch:** `datht-backend-rework`
> **Trạng thái tổng:** **PARTIAL** theo quyết định người điều hành: `mfa_every_login`; không triển khai persistent device trust khi chưa có contract proof được duyệt.
> **Nguồn:** `docs-common/07. API contracts.md` §3.8, §7.2.3–§7.2.4, §8.1.1, §8.1.4; `04. Database design & analysis.md` §4.2.1–§4.2.6; `design/01. audit-traceability.md` §5.3; `design/05. Modules contract.md` §5; [DEC-ID-008](../../../../DECISIONS.md).

## 1. Quyết định bảo mật và phạm vi

`07` §3.8 cho phép `deviceRef` + `deviceFingerprint` khớp trusted device bỏ OTP, nhưng cả hai là dữ liệu client có thể sao chép. Schema/API baseline không có device-secret hash/protocol proof, trust expiry hoặc chính sách tự trust được duyệt. Người điều hành đã chọn **luôn MFA cho password login**, không chọn extension cookie DB_DEVICE/migration/API revoke. Đây là quyết định rõ ràng về scope, không phải fallback cho thiếu implementation.

Mọi password login, kể cả row `trusted=true`, đều phải OTP. Không tạo persistent trust, không đổi `trusted_at`, không cấp device secret; `AuthResponse.session.deviceTrusted=false`.

## 2. API thực tế

1. `POST /api/v1/auth/login` giữ nguyên username/password/deviceRef/deviceName/deviceFingerprint. Password sai vẫn cùng lỗi anti-enumeration, lockout hiện có. Password hợp lệ chỉ reset counter primary authentication, không cập nhật `last_login_at` và không ghi LOGIN_SUCCESS.
2. Device metadata được HMAC bằng Common, chỉ phục vụ nhận diện/binding. Sau đó tạo challenge `LOGIN_NEW_DEVICE` theo TTL/max attempts cấu hình `app.security.otp`; audit CREATED trong OWN transaction, dispatch qua gateway **sau commit và ngoài transaction**.
3. Gateway ACCEPTED mới chuyển SENT và trả **202** `ChallengeRequiredResponse` gồm code/message/challenge/correlationId. Không session row, không token, không `Set-Cookie DB_SESSION`; challenge reference không dùng làm Bearer được.
4. `POST /api/v1/auth/otp/verify` là public endpoint đã có trong API contract, body chỉ challengeId và OTP string 6–8 chữ số. Server lấy user/device từ challenge persisted; không nhận user ID/device ID/trusted flag từ client.
5. OTP đúng mới chuyển VERIFIED, consume, tạo opaque session + CSRF hash, ghi OTP success attempt, update last login/last seen và mandatory C2 audits trong **một transaction**. Chỉ trả **200 AuthResponse + DB_SESSION** sau commit. Không JWT/refresh-token subsystem mới.

Cả 202/200 đều `Cache-Control: no-store`. Cookie session giữ nguyên cấu hình HttpOnly/Secure/SameSite/Path/Max-Age; raw OTP/token/CSRF/device fingerprint không log. OTP body dùng buffer `char[]`, WRITE_ONLY, redacted `toString()`, wipe ở finally cả validation failure; LoginCommand/LoginResult cũng redacted.

| Tình huống | Kết quả |
|---|---|
| Password hợp lệ, bất kỳ metadata/trusted row | 202 challenge, không session |
| OTP hợp lệ, challenge LOGIN/OTP/SENT còn hạn | 200 AuthResponse + cookie sau commit |
| OTP sai | 401 AUTH_OTP_INVALID; failed attempt commit |
| OTP hết hạn | 410 AUTH_OTP_EXPIRED |
| Hết lượt | 423 AUTH_CHALLENGE_LOCKED |
| Replay, CANCELLED, sai purpose login | 409 INVALID_STATE_TRANSITION |
| Method không OTP | 409 STEP_UP_CHALLENGE_MISMATCH |
| Challenge không có | 404 AUTH_CHALLENGE_NOT_FOUND |
| Device revoked/không thuộc user khi hoàn tất | 403 AUTH_DEVICE_NOT_TRUSTED |
| User disabled/locked sau password | 403 AUTH_FORBIDDEN / 423 AUTH_ACCOUNT_LOCKED |
| Gateway thiếu/lỗi hoặc chưa ACK SENT | 503 OTP_DISPATCH_FAILED, không session |
| DB/dependency lỗi | Fail-closed theo Common error pipeline, không cookie/session thành công |

## 3. Device recognition và lifecycle

- Dùng bảng `banking.device` hiện có. Ref mới do server sinh, không nhận client ref làm bằng chứng sở hữu. Lookup luôn scope user.
- Thiếu fingerprint: không gắn device (`device_id=NULL`), vẫn OTP. Unknown/foreign ref hoặc fingerprint khác không sửa device của người khác; đăng ký metadata row untrusted của đúng user.
- Unique `(user_id, device_fingerprint_hash)` xử lý race registration bằng INSERT ON CONFLICT DO NOTHING rồi SELECT fresh READ COMMITTED. Không upsert trạng thái/trust; không hồi sinh REVOKED.
- Device đã biết REVOKED bị từ chối; revoke giữa password và OTP được kiểm tra lại dưới lock trước khi cấp session.
- Session lưu `device_id` khi có context hợp lệ. Validate/refresh và CSRF rotation từ chối session gắn device REVOKED hoặc owner lệch, kể cả status được cập nhật trực tiếp trong DB. Không báo đã xây public API revoke hoặc durable revoke timestamp khi chưa có contract/schema.
- `last_seen_at` chỉ cập nhật sau OTP thành công; `trusted`/`trusted_at` giữ nguyên. Không suy luận trust từ UA/IP/fingerprint/ref hoặc header giả.
- Persistent registration/trust-expiry/re-registration/revoke management API **chưa triển khai theo lựa chọn người điều hành**; task không được đánh DONE toàn bộ.

## 4. Transaction, replay và failure

Reuse duy nhất OtpChallengeService và JdbcChallengeRepository hiện có; không có bảng login-MFA/challenge thứ hai. Challenge ref ngẫu nhiên là định danh riêng của từng password-authenticated attempt, liên kết user và optional device qua cột hiện có.

OTP so khớp bằng Argon2id hiện có ngoài lock dài. Wrong attempt được conditional UPDATE và commit, không rollback bộ đếm khi trả 401/423. Nhánh đúng mở OWN transaction: markVerified có điều kiện, recheck/lock user → device → atomic consume → session → OTP attempt/last seen → audit VERIFIED rồi LOGIN_SUCCESS cuối transaction. Consume giữ row lock rồi so expiry theo database wall clock; không dựa thời điểm transaction bắt đầu.

Hai request verify không thể cùng chuyển một SENT challenge thành VERIFIED/consumed và tạo hai session. Session insertion, user/device eligibility hoặc mandatory audit lỗi sẽ rollback cả markVerified/consume/session/last_login/last_seen; challenge còn SENT để retry bằng proof cũ khi chưa hết hạn. Challenge đã commit consumed không tạo session thứ hai.

Rate limit dùng Common hiện có: public OTP trước session được quota theo IP với giá trị cấu hình OTP hiện hành; authenticated OTP giữ quota theo user. Global quota và max attempts vẫn áp dụng. Không tuyên bố limiter trong process là distributed quota.

## 5. Database và compatibility

Không thêm migration, không sửa V1/V2, không thêm enum/table/secret storage. Tái sử dụng `device`, `auth_challenge.device_id`, `session.device_id`, `auth_attempt.attempt_type=OTP`, consumed_at và constraints/FK/index hiện có.

Internal command/repository records thêm device context/attempt type và toàn bộ callers được migrate; không compatibility shim hoặc password-only session path. Các regression fixture nhận session qua HTTP 202 → captured test-only OTP → verify200, không bypass MFA bằng config production.

## 6. Code paths

Dưới `digitalbanking-backend/src/main/java/com/simulatedbank/banking/`:

- `identity/application/PasswordLoginService.java`, `LoginCommand.java`: primary credential/lockout và challenge-only result.
- `identity/application/OtpChallengeService.java`, `ChallengeRepository.java`; `identity/adapter/out/JdbcChallengeRepository.java`: login challenge binding/public lookup và atomic completion dùng subsystem cũ.
- `identity/application/DeviceRepository.java`; `identity/adapter/out/JdbcDeviceRepository.java`: device metadata ownership/registration/locking.
- `identity/application/LoginSessionService.java`, `LoginResult.java`: consume + session + attempts + audits và token redaction.
- `identity/application/SessionRepository.java`, `UserAccountRepository.java`; `identity/adapter/out/JdbcSessionRepository.java`, `JdbcUserAccountRepository.java`: device-bound session, primary-vs-completed login, typed attempts.
- `identity/adapter/in/AuthController.java`, `ChallengeRequiredResponse.java`, `LoginOtpVerificationRequest.java`: API-AUTH-01/04 theo schema hiện có.
- `common/config/RequestRateLimitFilter.java`: quota public OTP trước khi có authenticated principal.

## 7. Evidence kiểm thử

### 7.1. Lệnh đã chạy và kết quả

Tại `digitalbanking-backend/`, Java 21, Docker + PostgreSQL 16 Alpine/Testcontainers:

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.identity.*' \
  --tests 'com.simulatedbank.banking.common.config.HttpSecurity*' \
  --tests 'com.simulatedbank.banking.common.config.PublicOtpRateLimitTest' \
  --tests 'com.simulatedbank.banking.common.error.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' \
  --tests 'com.simulatedbank.banking.migration.*' \
  --console=plain
```

- Lần đầu: **315 tests, 70 failed**, 0 error/skip. Lỗi fixture: seed không commit khi Hikari auto-commit=false; helper đọc challengeId ở root thay vì `challenge.challengeId`. Đã sửa bằng PlatformTransactionManager thật và đúng schema, không nới assertion hoặc thêm bypass.
- Lần chạy lại cùng lệnh sau sửa: **316 tests, 315 passed, 1 failed**, 0 error/skip; thêm regression cho ambient transaction. Failure duy nhất là fixture local kế thừa cookie.secure=false trong khi assert Secure. Đặt rõ cấu hình Secure=true cho suite ngân hàng, giữ nguyên assertion Secure/HttpOnly/SameSite, không đổi default/config production.
- Tại lần này, **291 tests ngoài suite DeviceMfaLoginHttpIntegrationTest đều PASS**; production code không thay đổi sau đó.

Sau khi sửa fixture Secure và thêm hai vector expiry/revocation dưới row-lock wait, chạy narrowest affected suite:

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.identity.DeviceMfaLoginHttpIntegrationTest' \
  --console=plain
```

**BUILD SUCCESSFUL trong 39s; JUnit XML: 27 passed, 0 failed/error/skipped.** Tổng evidence cuối theo suite không chồng lặp là **291 regression PASS + 27 MFA HTTP PASS = 318 invocations**; không tuyên bố 318 chạy trong một lệnh duy nhất hoặc toàn bộ repository suite.

| Suite cuối không chồng lặp | Passed | Failed/error/skip còn lại |
|---|---:|---:|
| Identity regression ngoài suite MFA mới | 218 | 0 |
| DeviceMfaLoginHttpIntegrationTest | 27 | 0 |
| Common HTTP security, gồm 2 public OTP quota tests | 32 | 0 |
| Common error contract/HTTP/PostgreSQL | 26 | 0 |
| ArchUnit | 5 | 0 |
| Migration/Flyway PostgreSQL | 10 | 0 |
| **Tổng evidence cuối từ hai lần chạy liên quan** | **318** | **0** |

Evidence local của lần cuối: `digitalbanking-backend/build/test-results/test/TEST-com.simulatedbank.banking.identity.DeviceMfaLoginHttpIntegrationTest.xml`, `digitalbanking-backend/build/reports/tests/test/index.html`. Output/JUnit summary của lần regression được ghi trong execution artifacts của phiên triển khai; Gradle thay XML selection sau narrow run, không coi XML cuối là chứa cả 318 tests.

### 7.2. Security/concurrency đã chứng minh

- Test A/B: HTTP 202 chính xác, không Set-Cookie/session/last_login/success attempt; challenge làm Bearer bị 401; đúng OTP mới HTTP 200/cookie và persisted session đúng device/user.
- Test C/D/E: sai/expired/locked/replayed OTP không session; wrong attempt commit, OTP đúng sau sai còn lượt hoạt động; consumed challenge không phát session thứ hai.
- Test F/G/H: sao chép trusted ref/fingerprint/UA, foreign ref, modified/unknown metadata không bypass; proof không thay được giữa hai login attempt/user; header giả không chọn actor; revoked device không reactivate và session liên quan bị deny sau revoke.
- Test I: verify HTTP thật đồng thời **lặp 3 lần**, mỗi lần đúng một 200/một 409 và một persisted session/OTP success; simultaneous registration **lặp 3 lần**, chỉ một device untrusted row.
- Hai vector deterministic giữ device row lock: chờ PostgreSQL `pg_blocking_pids` xác nhận request verify thật đang blocked; revoke rồi commit → 403/no session; chờ database wall clock qua expiry rồi release → 410/no session. Poll có clear statistics snapshot, future/await/shutdown bounded, không arbitrary sleep làm oracle.
- Test J: device-storage constraint failure, gateway failure/undelivered OTP, session INSERT constraint failure và từng C2 audit failure đều fail-closed. Session insert/audit failure rollback cả verified/consumed/session/last_login/last_seen; proof cũ retry được trước expiry.
- Ambient transaction regression: device metadata được OWN commit trước challenge; caller rollback không làm mất FK context hoặc tạo session sớm. OTP vẫn hoàn tất đúng một lần.
- Test K: password anti-enumeration/lockout, session validation/refresh, CSRF, RBAC, logout, step-up consume, error handling, migrations và module boundaries đều có regression PASS. Public OTP quota 429 chặn owner continuation, không echo proof.

### 7.3. Acceptance và phần không được đánh PASS

- [x] Phạm vi `mfa_every_login` đã được người điều hành chốt tại DEC-ID-008.
- [x] New/untrusted/copied-metadata login yêu cầu MFA; 202 không có authenticated session.
- [x] OTP đúng mới atomic consume + session; replay/cross-user/context substitution bị từ chối.
- [x] Revoked device eligibility được recheck dưới lock; session gắn revoked device không authenticate.
- [x] HTTP/PostgreSQL race, rollback, failure injection và regression đã thực thi thành công như trên.
- [x] Tài liệu/checklist cập nhật theo evidence; không commit/push.
- [ ] Persistent device-trust credential/bypass/expiry/re-registration và public lifecycle API: **deferred theo quyết định người điều hành**, không phải đã implement.
- [ ] Provider OTP/contact resolver và Audit persistence thật: **dependency chưa có**, không PASS từ fake/spy.
- [ ] Full BE-ID-IMP-08 DONE: **không đánh dấu**, trạng thái PARTIAL.

## 8. Dependencies và giới hạn

- Notification/OtpGateway adapter/contact resolver thật vẫn chưa có: production request cần dispatch trả 503; không fake production để cho login qua. Test dùng capture gateway in-memory chỉ trong test.
- Audit provider persistence thật vẫn chưa có (IMP-13); giữ port C2 fail-closed và adapter log hiện tại. Audit rollback được kiểm bằng failure injection, không nhận đó là audit chain/persistence integration thật.
- Resend/durable redelivery/crash-safe handoff vẫn thuộc MOD-OI-07/IMP-06; không thể khôi phục plaintext OTP từ hash. Task này không tự duyệt schema/cryptographic scheme mới.
- Theo DEC-ID-008, trusted-device bypass và persistent trust lifecycle bị hoãn. Checklist tổng giữ `[ ]`, trạng thái PARTIAL kể cả khi phần MFA luôn bật đã PASS toàn bộ kiểm thử liên quan.

## 9. Test/documentation files thay đổi

Dưới `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/`:

- Mới: `DeviceMfaLoginHttpIntegrationTest.java`.
- Migrate contract/fixture hiện có: `IdentityAuthPostgreSqlIntegrationTest.java`, `IdentityAuthDbFailureIntegrationTest.java`, `IdentitySecurityIntegrationTest.java`, `StepUpVerificationHttpIntegrationTest.java`, `PasswordLoginServiceTest.java`, `OtpChallengeServiceTest.java`, `OtpDispatchServiceTest.java`, `OtpDeliveryPostgreSqlIntegrationTest.java`.
- Mới dưới `digitalbanking-backend/src/test/java/com/simulatedbank/banking/common/config/`: `PublicOtpRateLimitTest.java`.

Tài liệu: file báo cáo này, `modules/identity/README.md`, `modules/identity/tasks/README.md`, `docs-common/07. API contracts.md` §3.8, `docs-common/design/05. Modules contract.md` §5.4 và `DECISIONS.md` DEC-ID-008. Không rewrite các task khác hoặc sửa migration để làm test pass.
