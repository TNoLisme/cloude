# BE-ID-IMP-10 — WebAuthn/FIDO2 verification và fallback

> **Ngày:** 16/11/2026 · **Branch:** `datht-backend-rework` · **Baseline:** đã fetch `origin/dev2@56210cc80ad742ddad9139dc1a08fe9ff524465a`, trùng HEAD. Giữ nguyên thay đổi chưa commit IMP-01..09; không checkout/reset/rebase/merge/push.

> **Trạng thái:** **PARTIAL** ở mức task end-to-end; verification và fallback có implementation thật, nhưng enrollment/Audit/Notification production còn BLOCKED (§6). Không đánh hoàn tất AC-002 toàn hệ thống.

## 1. Nguồn, quyết định và phạm vi

- Nguồn: `docs-common/03. SRS.md` FR-003/FR-006, UC-002, AC-002; `04` §4.2.4–4.2.6; `05` §5.2.2/§5.4.3; `07` §8.1.3; `design/01` catalog AUTH; `design/05` §5/§13. Đã đối chiếu bản `origin/dev2`, không coi working-tree supplement là nội dung baseline.
- Thứ bậc: `docs-common/01–11` > `design/` > tài liệu implementation. SRS AC-002 yêu cầu trusted credential tạo session, lockout và fallback; checklist IMP-10 yêu cầu thêm RP ID/origin/signature/counter/replay. Không thu hẹp AC nguồn thành chỉ compile hoặc cryptography.
- **DEC-ID-010:** người điều hành chọn `approve_verification_extension`: cho phép migration forward-only bổ sung verification material, Yubico, UV required, `0→0` hợp lệ, anomaly từ chối nhưng không tự revoke. Credential trusted phải provision ngoài task; không thêm enrollment/trust protocol.
- `07` dùng purpose `LOGIN_NEW_DEVICE` cho FIDO MVP; giữ enum/schema hiện hành. Ví dụ `preferred`/60000ms trong API không phải policy runtime; policy được duyệt là `required` và TTL mặc định 30 giây có cấu hình. `design/04` chỉ hook FIDO, không phủ định verification đã được SRS/API và người điều hành yêu cầu.
- IMP-03 session đã có; IMP-08 PARTIAL (`mfa_every_login`), không tự cấp persistent trust. IMP-09 Passcode positive còn BLOCKED. Không thay các quyết định này để tạo success giả.

## 2. Luồng production

### 2.1. Begin

`POST /api/v1/auth/fido/verify` với `username`, `deviceRef` đúng API-AUTH-03:

1. Validate DTO, oneOf begin/finish; không chấp nhận half-finish hoặc field lạ. Dùng global HTTP/body/CORS/security pipeline hiện có.
2. Bucket login IP và normalized username dùng `RequestRateLimiter` hiện có, tên group theo Common usage; bucket IP dùng chung password login. Hết quota trả 429 với Retry-After thực; capacity exhaustion trả 503. Quota vẫn process-local theo baseline; replay/counter không dựa RAM.
3. Lock user, kiểm trạng thái/lockout; credential phải đúng owner, device ACTIVE/trusted và có credential ID/COSE key/userHandle. Thiếu hoặc không eligible trả lỗi credential chung, không trả metadata tài khoản/credential.
4. `SecureRandom` sinh nonce 32 bytes, Base64URL; persist `auth_challenge` FIDO/LOGIN_NEW_DEVICE, device/credential binding, max attempts cấu hình và expiry.
5. C2 AUTH_CHALLENGE_CREATED trong transaction; 202 trả `fidoRequestOptions` gồm RP ID, nonce, allowCredentials, timeout và UV required. Không session/token/cookie.

### 2.2. Finish và signature

Cùng endpoint với username/deviceRef/challengeId/fido assertion:

1. Lock challenge rồi user rồi device, đọc lại state dưới lock; không mutate foreign challenge. Method/purpose/owner/device/credential phải khớp; terminal/consumed/expired bị từ chối.
2. Expiry kiểm bằng PostgreSQL `clock_timestamp()` sau khi chờ lock, không dùng transaction-start `now()` để gia hạn vô tình.
3. `YubicoFidoAssertionVerifier` dùng Yubico `webauthn-server-core:2.10.1`, phiên bản release lấy từ Maven metadata. Không tự viết production signature parser/verifier. Fixture test ký bằng JCA, không mock kết quả crypto.
4. Library kiểm `rpIdHash`, exact origin, `webauthn.get`, challenge, UP, required UV, credential/userHandle và signature chuẩn trên `authenticatorData || SHA256(clientDataJSON)`. Repository bound chỉ nhìn thấy credential/user đang lock, không lookup tùy ý từ global ID.
5. Parser bổ sung từ chối duplicate JSON/trailing JSON/crossOrigin=true hoặc sai kiểu; Base64URL phải canonical. userHandle response có thể vắng theo contract; nếu có phải khớp registered handle.
6. Cho phép ES256/384/512, RS256/384/512, EdDSA/Ed25519/Ed448 có verifier trên Java 21; SHA-1/unknown/ML-DSA chưa hỗ trợ bị từ chối. Không suy ra hợp lệ chỉ từ credential ID.
7. Sau mọi kiểm tra mới atomic CREATED→VERIFIED, cập nhật counter, gọi `LoginSessionService.completeFido` JOIN/MANDATORY. Dùng lại consume/session/CSRF/authorities/audit pipeline OTP; không duplicate issuer. Consume, counter, session, last-login, device-last-seen, auth_attempt FIDO và C2 audit cùng commit/rollback.
8. Wrong assertion/counter tăng challenge attempts và user lockout, auth_attempt FIDO trong transaction rồi mới ném lỗi HTTP ngoài transaction; không rollback mất failure counter. Reason nội bộ không trả client; C3 AUTH_LOGIN_FAILED/AUTH_ACCOUNT_LOCKED qua port hiện có.

### 2.3. Counter, replay và concurrency

- Cả stored/received bằng 0: chấp nhận authenticator không hỗ trợ counter; signature và mọi kiểm tra khác vẫn bắt buộc.
- Nếu một counter khác 0: received phải lớn hơn stored. Equal/regression/reset/wraparound bị từ chối; không cập nhật stored counter hoặc tự revoke device.
- Counter unsigned 32-bit lưu BIGINT, DB CHECK `0..4294967295`. Device row lock serialize các challenge khác nhau dùng cùng credential; refresh managed entity sau lock tránh stale persistence context.
- Challenge row lock + conditional verification/consume chặn double success giữa nhiều instance. Cùng assertion/cùng challenge chỉ một committed session; rollback C2/session lỗi hoàn lại counter và challenge để retry an toàn.
- Challenge đã consume không được cleanup đổi về active; expiry sweep hiện có xử lý unconsumed active challenge. Không thêm hard-delete retention khi chưa có policy được duyệt.

## 3. Fallback không hạ assurance

Nguồn FR-003, `07` §8.1.3 và DEC-ID-008/010: **password + OTP mới** qua API-AUTH-01 và API-AUTH-04. Không thêm endpoint fallback, không chuyển FIDO challenge thành OTP, không xác thực chỉ bằng username/deviceRef và không carry assurance từ FIDO thất bại.

| Tình huống | Xử lý |
|---|---|
| Không credential / untrusted / revoked / disabled | Không FIDO success; error chung không tiết lộ credential; bắt đầu lại password flow nếu account eligible |
| Cancel / thiết bị không hỗ trợ / authenticator unavailable | Browser chọn password flow; không có cancel assertion giả hoặc server endpoint cancellation trong contract |
| Expired | 410 AUTH_CHALLENGE_EXPIRED; có thể begin ceremony mới hoặc password + OTP mới |
| Invalid assertion / signature / RP/origin/UV | 401 AUTH_INVALID_CREDENTIALS, commit attempts/lockout; không mint token |
| Counter anomaly | Cùng public denial; reason nội bộ COUNTER_ANOMALY, counter giữ nguyên, không tự revoke |
| Account/challenge locked | 423 theo catalog; password fallback không vượt account lockout |
| Feature tắt / dependency unavailable | Fail-secure; feature tắt trả 503, không fake WebAuthn success; fallback password vẫn phải có OTP provider hoạt động |

Passcode positive không được dùng làm fallback tắt MFA: còn BLOCKED theo DEC-ID-009. Cancellation và capability là trạng thái browser, không phải giá trị request mà API nguồn cho phép; backend không tự suy diễn từ assertion sai.

## 4. Configuration và schema

| Cấu hình | Biến môi trường | Quy tắc |
|---|---|---|
| `app.identity.fido.enabled` | `APP_FIDO_ENABLED` | false mặc định; endpoint fail-secure 503 khi chưa bật |
| `rp-id` | `APP_FIDO_RP_ID` | DNS RP ID tường minh, không URL/wildcard/IP |
| `allowed-origins` | `APP_FIDO_ALLOWED_ORIGINS` | Danh sách exact HTTPS origins, không path/query/userinfo/wildcard; host thuộc RP ID |
| `challenge-ttl` | `APP_FIDO_CHALLENGE_TTL` | Duration dương, mặc định PT30S |

Local/test có thể dùng RP ID `localhost`, origin `http://localhost:5173` khi **chỉ** active local/test profiles. Production/demo phải HTTPS; ghép production với local không mở HTTP. Không lấy CORS origin thay cho WebAuthn validation; cả hai phải cấu hình đúng. Compose và `.env.example` có wiring, không bật FIDO mặc định.

Migration mới `src/main/resources/db/migration/V3__add_fido_verification_material.sql`:

- `device.fido_public_key_cose TEXT NULL`, `fido_user_handle VARCHAR(86) NULL`, `fido_sign_count BIGINT NOT NULL DEFAULT 0 CHECK unsigned32`.
- `auth_challenge.fido_nonce VARCHAR(86) NULL`.
- Không sửa V1/V2, không backfill fake key, không thêm bảng/session issuer hoặc status enum. Legacy thiếu verification material bị từ chối.
- V3 thực tế còn trống; tài liệu migration roadmap trước đây dự kiến V3 cho Customer nhưng chưa có migration/file. Customer owner phải dùng version tiếp theo khi triển khai; không overwrite V3 mới.
- CRUD dùng Spring Data JPA; native conditional query chỉ cho transitions/DB wallclock. `FidoCeremonyEntity` dùng Java short khớp SMALLINT, không disable Hibernate validation để né sai mapping.

## 5. Tệp production và kiểm thử

Các đường dẫn dưới đây thuộc `digitalbanking-backend/`:

- Prefix Java: `src/main/java/com/simulatedbank/banking/`. Application: `identity/application/{FidoLoginService,FidoProperties}.java`; ports: `identity/application/port/{FidoAssertionVerifier,FidoVerificationException,FidoCredentialStore}.java`.
- `identity/adapter/in/FidoController.java`, `AuthHttpSupport.java`; sửa `AuthController.java` dùng chung response OTP/FIDO.
- `identity/adapter/out/YubicoFidoAssertionVerifier.java`, `JpaFidoCredentialStore.java`, `FidoDeviceEntity.java`, `FidoDeviceJpaRepository.java`, `FidoCeremonyEntity.java`, `FidoCeremonyJpaRepository.java`.
- `identity/application/LoginSessionService.java`: thêm entry FIDO, giữ OTP contract/issuer.
- `common/config/RequestRateLimiter.java`: public exception capacity để Identity bắt đúng loại, không broad-catch RuntimeException hoặc đổi thuật toán quota.
- `gradle/libs.versions.toml`, `build.gradle.kts`; `src/main/resources/application.yml`, `.env.example`, `compose.yaml`; migration V3 tại đường dẫn §4.
- `src/test/java/com/simulatedbank/banking/identity/FidoTestAuthenticator.java`, `FidoAssertionVerifierTest.java`, `FidoPropertiesTest.java`, `FidoPersistencePostgreSqlIntegrationTest.java`, `FidoLoginHttpIntegrationTest.java`.

## 6. Dependency và giới hạn nghiệm thu

- **Verification runtime có code thật**, không simulator, không custom signature verification; credential được provision bằng key pair test-only để kiểm thử.
- **Enrollment/provisioning trusted credential chưa có contract/runtime**, ngoài scope được người điều hành duyệt. Không có public registration, attestation hoặc device trust lifecycle mới; không bịa success enrollment để đóng AC.
- **Notification provider/contact resolver thật và Audit persistence thật chưa có** trên baseline/working tree. Gateway test-only và audit failure injection chứng minh integration seam/rollback; `SecurityLogAuditAdapter` hiện có chỉ log/metric, không durable audit và không thể coi thay audit_log.
- Không phê duyệt production release/full AC-002 chỉ từ cryptographic tests; rollout cần provider/Audit/enrollment integration thật. Checklist không đánh unrelated task DONE.

## 7. Ma trận bằng chứng AC-002

Các class test nằm trong `src/test/java/com/simulatedbank/banking/identity/`.

| Yêu cầu | Bằng chứng tự động |
|---|---|
| RP ID / rpIdHash | `FidoAssertionVerifierTest.rejectsDifferentRpHashEvenWhenCorrectlySigned`; HTTP `invalidAssertionsNeverConsumeOrIssueTokens[rp]` |
| Exact origin | `requiresExactOriginIncludingSchemeHostAndPort`, `acceptsEachExplicitlyConfiguredOriginButNotOtherPorts`; HTTP `[origin]`; `FidoPropertiesTest` chặn wildcard/HTTP production và mixed production/local |
| Signature | `verifiesRealSignaturesForEverySupportedAlgorithm`, `rejectsTamperingWithEverySignedComponent`, `zeroCounterDoesNotBypassSignatureVerification`; HTTP `[signature]`, `unsupportedAlgorithmCannotAuthenticateEvenWithMatchingCredentialId` |
| Counter | `acceptsZeroCounterPairOrStrictIncrease`, `rejectsCounterRollbackEqualityAndWraparound`; HTTP `zeroCounterAuthenticatorCanAuthenticateAcrossDistinctCeremonies`, `counterRollbackAndResetAreRejectedWithoutChangingCounter`, `concurrentDistinctChallengesCannotBypassCredentialCounter` |
| Replay/expiry | HTTP `validAssertionConsumesChallengeAdvancesCounterAndAuthenticatesRealSession`, `concurrentReplayHasExactlyOneCommittedAuthentication`, `expiredAndUnknownChallengesDoNotAuthenticate`; persistence `concurrentIndependentAdaptersVerifySameCeremonyOnlyOnce`, `expiryUsesDatabaseWallClockAfterEveryLockWaitEvenWhenHolderRollsBack` |
| Ownership/state/UV | Crypto `requiresBothUserPresenceAndUserVerification`, `absentHandleStillBindsAssertionToRequestedUsersCredential`; HTTP `changedEligibilityAfterBeginFailsSecurely`, `foreignUserAndOtpChallengeCannotBeSubstituted` |
| Fallback / không phát token trước verify | HTTP `passwordFallbackRequiresFreshPasswordAndOtp` (7 tình huống), `invalidAttemptsLockAccountAndCannotUsePasswordFallbackToBypassLock`, `unavailableVerificationDependencyFailsClosedWithoutConsumingChallenge` |
| Transaction rollback/migration | HTTP `mandatoryAuditFailureRollsBackCounterConsumptionAndSession`; persistence `verificationAndCounterAreDurableAcrossIndependentAdaptersAndRollBackTogether`, `forwardMigrationPreservesV2RowsWithoutInventingVerificationMaterial`, `rowLocksRefreshStaleManagedDeviceCeremonyAndUserProjections` |

API-AUTH-05 step-up vẫn chỉ OTP theo IMP-07; không tự tạo FIDO transfer/sensitive ceremony hoặc quyết định method-selection OI-API-17 thay owner. Những consumer/selection policy đó không được coi đã hoàn tất bởi login FIDO.

## 8. Lệnh và evidence thực thi

Chạy từ `digitalbanking-backend/`, Java 21, Docker 29.7.2, Testcontainers PostgreSQL 16; không H2, không skip infrastructure-dependent tests.

```bash
./gradlew test --tests '*Fido*Test' --tests '*DeviceMfaLoginHttpIntegrationTest' --tests '*IdentitySecurityIntegrationTest' --tests '*ModuleBoundaryArchTest' --console=plain
./gradlew test --tests '*FidoLoginHttpIntegrationTest' --tests '*FidoPersistencePostgreSqlIntegrationTest' --tests '*DeviceMfaLoginHttpIntegrationTest' --tests '*IdentitySecurityIntegrationTest' --tests '*ModuleBoundaryArchTest' --console=plain
./gradlew test --tests '*FidoLoginHttpIntegrationTest' --console=plain
docker compose config --quiet
```

- Lần đầu compile FAILED: exception capacity của limiter package-private; sửa visibility thành public để Identity bắt đúng loại, không đổi logic quota.
- Lần tiếp theo crypto **66/66 PASS**, config **45/45 PASS**; integration context FAILED vì Java int map SMALLINT sai JDBC type. Sửa entity thành short, không sửa schema V2 hoặc disable validation.
- Sau sửa mapping: persistence **21/21 PASS**, MFA HTTP hiện có **27/27 PASS**, Identity security **19/19 PASS**, ArchUnit **5/5 PASS**. HTTP FIDO ban đầu FAILED tại seed vì Hikari `auto-commit=false`: insert user riêng không commit nên device FK không thấy user. Sửa fixture dùng transaction thật cho seed và state mutations; không đổi assertion nghiệp vụ để ép xanh.
- Lần HTTP sau sửa fixture và bổ sung unsupported-algorithm/dependency-outage scenarios: **BUILD SUCCESSFUL 43s, 33/33 PASS**, 0 failure/error/skip. Crypto luôn thật, trừ test outage cố ý ném lỗi dependency, không mock thành signature success.
- `docker compose config --quiet`: exit 0. Đây là validation config, không phải Cloud deployment hoặc browser/authenticator thật.

Không cộng số test từ các lần chạy trên thành một full run. Artifact JUnit/HTML/JaCoCo nằm trong `build/test-results/test/`, `build/reports/tests/test/`, `build/reports/jacoco/test/` và được lần chạy tiếp theo thay thế.

### 8.1. Full verification sau sửa cuối

```bash
./gradlew build --rerun-tasks --console=plain
```

**BUILD SUCCESSFUL trong 5m09s, 9/9 tasks executed.** JUnit XML của đúng lần chạy này: **46 classes, 666 tests PASS, 0 failures, 0 errors, 0 skipped**. Identity có **453 tests PASS**, bao gồm 165 tests FIDO dưới đây; không cộng lặp các phạm vi con.

| Phạm vi trong cùng full run | PASS | Failure/error/skip |
|---|---:|---:|
| FidoAssertionVerifierTest — signatures/algorithms/RP/origin/flags/counter | 66 | 0 |
| FidoPropertiesTest — config fail-fast/dev/production | 45 | 0 |
| FidoPersistencePostgreSqlIntegrationTest — migration/locks/races/rollback/expiry | 21 | 0 |
| FidoLoginHttpIntegrationTest — API/signature/session/fallback | 33 | 0 |
| DeviceMfaLoginHttpIntegrationTest — regression MFA hiện có | 27 | 0 |
| IdentitySecurityIntegrationTest — regression security hiện có | 19 | 0 |
| ModuleBoundaryArchTest | 5 | 0 |
| Migration/Flyway hiện có | 10 | 0 |

`compileJava`, `compileTestJava`, bootJar, test/check/build và JaCoCo đều thực thi thành công trong lệnh trên. Test compilation còn cảnh báo unchecked/unsafe operations; không có compiler error. Không chạy/không nhận PASS cho scanner ngoài build chưa cấu hình.

**BLOCKED/NOT_TESTED ngoài fixtures:** enrollment/provisioning production; OTP provider/contact resolver thật; durable Audit integration; browser + hardware authenticator thật/Cloud. Testcontainers/HTTP/crypto thật không bị skip. Các fake/spy chỉ dùng để cấp OTP test và inject audit/dependency outage theo Modules contract §13; không thay chữ ký bằng success giả. Chưa có runtime/contract cho các provider/lifecycle này nên không tự tạo production stub để đóng task.

**Kết luận:** năm kiểm soát RP ID/origin/signature/counter/replay đã VERIFIED bằng code + tests; task end-to-end giữ **PARTIAL**, checklist `[ ]` vì dependency production tại §6 chưa được chứng minh. Không đánh các task IMP-06/08/09/13 hoàn tất theo full run xanh.

## Change Log

| Ngày | Thay đổi |
|---|---|
| 16/11/2026 | DEC-ID-010 được duyệt; triển khai Yubico/JPA/API-AUTH-03/session reuse/fallback, migration V3, config và 165 tests mới; sửa compile/mapping/fixture failures, full backend 666/666 PASS. |
