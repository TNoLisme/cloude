# Module identity — Backend documentation

BE-INT-05 thêm lookup challenge VERIFIED theo binding phía server và nối Audit C2/C3 cho vòng đời OTP Transfer. Issue chỉ trả challenge sau khi gateway ACK và trạng thái SENT đã lưu. [BE-INT-05](../../workofteam/integration%20modules/BE-INT-05.md)

[BE-INT-03](../../workofteam/integration%20modules/BE-INT-03.md) đã nối `CustomerQuery` vào principal và AuthResponse sau MFA hoặc refresh. User CUSTOMER có mapping trả Customer ID, kể cả INACTIVE. Staff và CUSTOMER thiếu mapping trả null. Provider lỗi thì xác thực trả 503.

> Baseline: `dev2@56210cc80ad742ddad9139dc1a08fe9ff524465a` · Trạng thái: IMP-01/IMP-02/IMP-03/IMP-04/IMP-05 đã triển khai; IMP-06 PARTIAL (MOD-OI-07 còn OPEN); IMP-07 BLOCKED ở integration Transfer/Limit; IMP-08 PARTIAL (MFA mọi password login theo DEC-ID-008, persistent trust hoãn); IMP-09 PARTIAL (Passcode fail-closed và password-history component theo DEC-ID-009); IMP-10 PARTIAL (WebAuthn/Yubico và password+OTP fallback đã có code; enrollment/Audit/Notification thật còn thiếu); IMP-11 PARTIAL (JPA RBAC, versioned role command và revoke sessions; production approval/Audit còn BLOCKED); IMP-12 PARTIAL (REST DTO/validation/error mapping + OpenAPI cho API-AUTH-01..07; API-AUTH-02 positive và API-AUTH-08 BLOCKED theo DEC-ID-009/006) trên branch `datht-backend-rework`; IMP-13 PARTIAL (audit port/metrics/username quota/redaction; durable audit persistence BLOCKED theo DEC-ID-013); TEST-01 BLOCKED integration, TEST-02 PARTIAL, TEST-03 PARTIAL (QA/security/perf phía Identity VERIFIED, FE/QA sign-off PENDING_EXTERNAL_SIGNOFF — DEC-ID-TEST-03); còn lại DRAFT/planning.

- [BE-ID-DOC-01 — Thiết kế chi tiết và kế hoạch triển khai](BE-ID-DOC-01.md)
- [BE-ID-IMP-02 — Báo cáo triển khai password login/Argon2id/lockout/logout/anti-enumeration](BE-ID-IMP-02.md)
- [BE-ID-IMP-03 — Báo cáo triển khai opaque session/CSRF binding/logout/SessionAuthenticator SPI](BE-ID-IMP-03.md)
- [BE-ID-IMP-04 — Báo cáo triển khai CSRF/RBAC/actor propagation/IDOR security integration](BE-ID-IMP-04.md)
- [BE-ID-IMP-05 — Báo cáo triển khai OTP state machine issue/verify/consume/expire/lock/binding](BE-ID-IMP-05.md)
- [BE-ID-IMP-06 — Delivery sau commit/fail-closed/expiry recovery; resend và durable redelivery còn BLOCKED](BE-ID-IMP-06.md)
- [BE-ID-IMP-07 — Hardening StepUpChallengePort/verify HTTP; integration Transfer/Limit còn BLOCKED](BE-ID-IMP-07.md)
- [BE-ID-IMP-08 — Device recognition, login 202 và atomic OTP→session; persistent trust hoãn theo quyết định người điều hành](BE-ID-IMP-08.md)
- [BE-ID-IMP-09 — Audit persistence/dev2: JPA password history, config/port fixes; Passcode success contract và API đổi password còn BLOCKED](BE-ID-IMP-09.md)
- [BE-ID-IMP-10 — WebAuthn/Yubico, counter/replay PostgreSQL và password+OTP fallback; dependency enrollment/Audit/Notification](BE-ID-IMP-10.md)
- [BE-ID-IMP-11 — JPA RBAC, Admin role command, assignment version và active-session revocation; dependency Admin/Audit](BE-ID-IMP-11.md)
- [BE-ID-IMP-12 — REST DTO/validation/error mapping API-AUTH-01..07 + OpenAPI; Passcode positive và resend BLOCKED](BE-ID-IMP-12.md)
- [BE-ID-IMP-13 — Audit integration/metrics/username rate limit/log redaction; durable audit persistence BLOCKED](BE-ID-IMP-13.md)
- [BE-ID-TEST-01 — Unit/contract/concurrency suite, traceability và blocker integration](BE-ID-TEST-01.md)
- [BE-ID-TEST-02 — PostgreSQL Testcontainers, Spring wiring, restart/failure injection; durable Audit/Notification/Transfer còn BLOCKED](BE-ID-TEST-02.md)
- [BE-ID-TEST-03 — QA security (cookie contract/credential binding/RBAC matrix/mass assignment), performance 5 kịch bản và handover; FE/QA sign-off PENDING](BE-ID-TEST-03.md)
- [BE-ID — Task breakdown, checklist và acceptance criteria](tasks/README.md)
- [Nguồn tổng quan Identity/Auth](../_overview/01-identity-auth.md)

Nguồn chuẩn: `docs-common/03. SRS.md`, `04. Database design & analysis.md`, `06. System architecture.md`, `07. API contracts.md`, `design/04. security.md`, `design/05. Modules contract.md`. Không coi tài liệu là bằng chứng implementation.
