# Module `identity` (`auth`) — Chỉ mục & Đặc tả Kỹ thuật

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-IDENTITY-00`
> **Phiên bản:** 1.1
> **Ngày cập nhật:** 18/11/2026
> **Trạng thái:** Đã triển khai core/REST (75 classes Java, migrations `V2`–`V4`, 597 tests PASS — chờ ghép tích hợp `AuditModuleApi`, `OtpGateway`, `CustomerQuery`)
> **Owner:** Đạt
> **Phối hợp / Reviewer:** Phương (`common`), Thịnh (`audit`, `notification`), Vũ (`transfer`), Thắng (`FE`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] (FR-001..007, FR-026, FR-040), [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] (§4.2), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§6.2, §7.1), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§3, §6, §8.1), [[digitalbanking-docs/docs-common/design/04. security.md]] (§4, §5), [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] (§5), [[digitalbanking-docs/docs-backend/modules/_overview/01-identity-auth.md]], [[DECISIONS.md]] (`DEC-ID-001..013`, `DEC-ID-TEST-02/03`)

---

## 1. Tổng quan Hiện trạng Triển khai (`75 Classes Java`)

Module `com.simulatedbank.banking.identity` đã hoàn tất triển khai **75 classes Java** trong `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity` cùng 3 phiên bản Flyway migration (`V2__create_identity_and_rbac_tables.sql`, `V3__add_fido_verification_material.sql`, `V4__add_role_assignment_version.sql`), vượt qua **33 test suites / 597 tests** (unit, HTTP slice, OpenAPI contract, PostgreSQL 16 Testcontainers concurrency/restart/failure injection) và **5 kịch bản đo hiệu năng (`perfTest`)**:

- **`api/` (2 interfaces):** `StepUpChallengePort` (`requestChallenge`, `verifyChallenge`, `consumeVerified`), `IdentityRoleCommand` (`getRoleAssignmentVersion`, `applyApprovedRoleChange`).
- **`domain/` (2 classes):** `ChallengeState`, `PasswordPolicy`.
- **`application/` & `application/port/` (32 classes):** `PasswordLoginService`, `LoginSessionService`, `LogoutService`, `SessionViewService`, `OtpChallengeService`, `OtpDispatchService`, `OtpExpiryRecoveryService`, `FidoLoginService`, `PasscodeLoginService`, `PasswordHistoryService`, `IdentityRoleCommandService`, `AuthMetrics`, `FidoProperties`, `PasswordPolicyProperties`, cùng các outbound ports (`UserAccountRepository`, `SessionRepository`, `DeviceRepository`, `ChallengeRepository`, `AuthorityReader`, `PasswordHasher`, `OtpCodeGenerator`, `AuthAuditPort`, `FidoAssertionVerifier`, `FidoCredentialStore`, `PasswordHistoryRepository`, `RoleChangeStore`).
- **`adapter/in/` (14 classes):** `AuthController`, `FidoController`, `PasscodeController`, `StepUpVerificationController`, `OtpRecoveryJob`, `AuthHttpSupport`, `IdentityRequestValidation`, `StrictStringDeserializer`, `StrictUuidDeserializer`, cùng các Request/Response DTOs (`LoginRequest`, `PasscodeLoginRequest`, `LoginOtpVerificationRequest`, `StepUpVerificationRequest`, `AuthResponse`, `ChallengeRequiredResponse`).
- **`adapter/out/` (25 classes):** `IdentitySessionAuthenticator` (hiện thực `common.config.SessionAuthenticator`), `Argon2PasswordHasher`, `SecureRandomOtpCodeGenerator`, `YubicoFidoAssertionVerifier`, `SecurityLogAuditAdapter`, các JDBC repositories (`JdbcUserAccountRepository`, `JdbcSessionRepository`, `JdbcDeviceRepository`, `JdbcChallengeRepository`) và các JPA entities/repositories (`AuthorityUserEntity`, `JpaAuthorityReader`, `FidoDeviceEntity`, `FidoCeremonyEntity`, `JpaFidoCredentialStore`, `PasswordHistoryEntity`, `JpaPasswordHistoryRepository`, `UserCredentialEntity`, `RoleChangeUserEntity`, `RoleChangeRoleEntity`, `RoleChangeAssignmentEntity`, `RoleChangePrivilegeEntity`, `RoleChangeSessionEntity`, `JpaRoleChangeStore`).

---

## 2. Các Cổng Tích hợp Liên Module Còn Chờ Ghép Nối (`Cross-Module Integration Gates`)

| Phụ thuộc liên module | Module cung cấp | Trạng thái hiện tại trong `identity` | Hành động khi ghép tích hợp |
|---|---|---|---|
| **`AuditModuleApi`** | `audit` (Thịnh) | Đang gọi qua `AuthAuditPort` + `SecurityLogAuditAdapter` (ghi metric + log đã qua `LogRedaction`). | Nối `AuthAuditPort` sang `AuditRecorder` (`C2` `MANDATORY`) và `C3` standalone (`DEC-ID-013`). |
| **`OtpGateway`** | `notification` (Thịnh) | Đã định nghĩa `notification.api.OtpGateway` và `OtpDispatchService` (gửi sau khi commit `T0`, fail-closed `503 OTP_DISPATCH_FAILED`). | Nối adapter gửi OTP thực tế và hoàn tất endpoint gửi lại `API-AUTH-08` (`DEC-ID-006/012`). |
| **`CustomerQuery`** | `customer` (Đạt) | Đã định nghĩa hợp đồng `CustomerQuery.findByUserId` và `resolveNotificationDestination`. | Nối `CustomerQuery` để điền `customerId` vào `Session` và lấy địa chỉ nhận OTP đã che dấu (`DEC-TRF-011`). |
| **Consumers của `StepUpChallengePort` & `IdentityRoleCommand`** | `transfer` (Vũ), `admin` (Đạt) | Cả hai provider port đã triển khai và kiểm chứng trên PostgreSQL (`BE-ID-IMP-07`, `BE-ID-IMP-11`). | Ghép trực tiếp với `TransferCreateOrchestrator` (`DEC-TRF-017`) và `MakerCheckerApprovalService` (`DEC-ID-011`). |

---

## 3. Danh mục Tài liệu trong Thư mục `modules/identity/`

- [Tóm tắt Module 01: `identity` / `auth`](../_overview/01-identity-auth.md)
- [BE-ID — Kế hoạch phân rã công việc và Checklist nghiệm thu](tasks/README.md)
- [BE-ID-DOC-01 — Rà soát kiến trúc, truy vết yêu cầu và phân xử quyết định mở](BE-ID-DOC-01.md)
- [BE-ID-IMP-02 — Báo cáo triển khai Password Login, Argon2id, Lockout, Logout & Anti-Enumeration](BE-ID-IMP-02.md)
- [BE-ID-IMP-03 — Báo cáo triển khai Opaque Session, CSRF Binding, Logout & `SessionAuthenticator` SPI](BE-ID-IMP-03.md)
- [BE-ID-IMP-04 — Báo cáo triển khai Tích hợp Bảo mật CSRF, RBAC, Actor Propagation & Chống IDOR](BE-ID-IMP-04.md)
- [BE-ID-IMP-05 — Báo cáo triển khai Máy trạng thái OTP (`issue`/`verify`/`consume`/`expire`/`lock`/`binding`)](BE-ID-IMP-05.md)
- [BE-ID-IMP-06 — Báo cáo triển khai Gửi OTP sau Commit (`OtpGateway`), Timeout & `OtpRecoveryJob`](BE-ID-IMP-06.md)
- [BE-ID-IMP-07 — Báo cáo triển khai `StepUpChallengePort` & Xác minh Step-Up HTTP (`API-AUTH-05`)](BE-ID-IMP-07.md)
- [BE-ID-IMP-08 — Báo cáo triển khai Nhận diện Thiết bị & Đăng nhập MFA Bắt buộc (`DEC-ID-008`)](BE-ID-IMP-08.md)
- [BE-ID-IMP-09 — Báo cáo triển khai Chính sách Passcode Fail-Closed & Lịch sử Mật khẩu (`DEC-ID-009`)](BE-ID-IMP-09.md)
- [BE-ID-IMP-10 — Báo cáo triển khai Xác thực FIDO2/WebAuthn (`Yubico`) & Fallback (`DEC-ID-010`)](BE-ID-IMP-10.md)
- [BE-ID-IMP-11 — Báo cáo triển khai JPA RBAC, `IdentityRoleCommand`, `role_assignment_version` & Thu hồi Phiên (`DEC-ID-011`)](BE-ID-IMP-11.md)
- [BE-ID-IMP-12 — Báo cáo triển khai REST DTO, Validation, Error Mapping `API-AUTH-01..07` & OpenAPI (`DEC-ID-012`)](BE-ID-IMP-12.md)
- [BE-ID-IMP-13 — Báo cáo triển khai Tích hợp Audit, Metrics, Rate Limit theo Username & `LogRedaction` (`DEC-ID-013`)](BE-ID-IMP-13.md)
- [BE-ID-TEST-01 — Báo cáo Bộ kiểm thử Unit, Contract và Concurrency](BE-ID-TEST-01.md)
- [BE-ID-TEST-02 — Báo cáo Kiểm thử Tích hợp PostgreSQL Testcontainers, Spring Wiring, Restart & Failure Injection](BE-ID-TEST-02.md)
- [BE-ID-TEST-03 — Báo cáo Kiểm thử Bảo mật QA, Đo lường Hiệu năng (`perfTest`) và Bàn giao FE/QA](BE-ID-TEST-03.md)
