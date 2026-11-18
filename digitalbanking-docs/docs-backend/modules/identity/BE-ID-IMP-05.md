# BE-ID-IMP-05 — OTP state machine (issue/verify/consume/expire/lock/binding): báo cáo triển khai

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-ID-IMP-05`
> **Phiên bản:** v1.0
> **Ngày:** 16/11/2026
> **Trạng thái:** DONE (scope BE-ID-IMP-05; phần phụ thuộc chưa có nêu rõ §6)
> **Owner:** Đạt (module identity)
> **Branch:** `datht-backend-rework` (baseline `origin/dev2@56210cc` + phần chưa commit của IMP-01..04)
> **Nguồn chuẩn:** `04. Database design & analysis.md` §4.2.5 (`auth_challenge`); `design/05. Modules contract.md` §3.2/§5.2; `design/02. consistency-concurrency.md` §5.1–§5.2, §9.1; `design/01. audit-traceability.md` §5.3; `07. API contracts.md` §3.7/§6/§6.6; `03. SRS.md` FR-004/FR-026/FR-042, NFR-016, AC-003/AC-033; BE-ID-DOC-01 §6.2–§6.5, OD-ID-06.

> **Cập nhật IMP-06 (16/11/2026):** xem [BE-ID-IMP-06](BE-ID-IMP-06.md), trạng thái PARTIAL. Các mô tả bên dưới là evidence lịch sử IMP-05: OTP hiện yêu cầu `SENT` mới verify được; issue audit đã chuyển vào INSERT TX, gateway được gọi sau OWN commit. Không còn coi CREATED là trạng thái OTP có thể verify. Dispatch/recovery hiện chỉ synchronous send + expiry sweep; resend/durable redelivery vẫn BLOCKED (DEC-ID-006).

## 1. Tóm tắt triển khai

Triển khai đầy đủ state machine `auth_challenge` ở layer application/adapter (không đổi schema,
không đổi API contract public, không thêm state enum):

| Năng lực | File chính | Hành vi |
|---|---|---|
| Cross-module port step-up | `identity/api/StepUpChallengePort` | `requestChallenge` (OWN/T0), `verifyChallenge` (OWN/T0), `consumeVerified` (JOIN/T1, `@Transactional(MANDATORY)`) đúng design/05 §5.2 |
| State machine domain | `identity/domain/ChallengeState` | `CREATED→SENT→VERIFIED`; `LOCKED/EXPIRED/CANCELLED` terminal; transition validation tường minh; KHÔNG có `CONSUMED` (OD-ID-06) |
| Application service | `identity/application/OtpChallengeService` | issue/verify/consume/expire/lock; binding check; audit C2/C3; OTP hash Argon2id |
| Persistence port + JDBC | `identity/application/ChallengeRepository`, `identity/adapter/out/JdbcChallengeRepository` | Mọi mutation là MỘT conditional UPDATE/INSERT … RETURNING (atomic) |
| OTP generator | `identity/application/OtpCodeGenerator` + `adapter/out/SecureRandomOtpCodeGenerator` | CSPRNG `SecureRandom`, 6 digits; plaintext `char[]` wipe ngay sau hash |
| Audit | `AuthAuditPort` +4 action additive | `AUTH_CHALLENGE_CREATED` (C2), `AUTH_STEP_UP_VERIFIED` (C2), `AUTH_STEP_UP_FAILED` (C3), `AUTH_CHALLENGE_LOCKED` (C3) — tên khớp catalog design/01 §5.3 |

## 2. State machine, binding và invariants

### 2.1. States và transitions

```
CREATED ──dispatch (IMP-06)──▶ SENT ──verify đúng──▶ VERIFIED
   │  │                              │                 │
   │  └─sai vượt ngưỡng→ LOCKED ◀────┘                 ├─consume (T1): consumed_at (status giữ VERIFIED)
   │                                                   └─hết hạn chưa consume → EXPIRED (lazy)
   └─hết hạn→ EXPIRED        (sai từng lần → attempt_count tăng atomic, chốt LOCKED tại ngưỡng)
```

- `status` giữ nguyên bộ CHECK constraint V2: `CREATED/SENT/VERIFIED/LOCKED/EXPIRED/CANCELLED`.
- **Consume KHÔNG phải state**: consume = set `consumed_at = now()` khi `status='VERIFIED'`
  (OD-ID-06 — `design/04` SQL `status='CONSUMED'` vi phạm CHECK constraint, không copy).
- Transition cấm → `409 INVALID_STATE_TRANSITION` (re-verify VERIFIED, consume CANCELLED…).
- `CANCELLED` chưa có luồng định nghĩa (OD-ID-06, invalidate policy MOD-OI-07 mở — IMP-06).

### 2.2. Binding (`07` §6.2, §6.5; 04 §4.2.5)

| Purpose | Binding bắt buộc | Consume predicate |
|---|---|---|
| `TRANSFER_STEP_UP` | `request_hash` (SHA-256 hex64) + `idempotency_key` (≤255) | `IS NOT DISTINCT FROM` cả hai |
| `SENSITIVE_ACTION` | như trên (approvalId + hash decision payload) | như trên |
| `LOGIN_NEW_DEVICE` | không có (NULL/NULL) | reject nếu caller truyền binding |

- Verify sai purpose → `409 STEP_UP_CHALLENGE_MISMATCH`; challenge không thuộc user/không tồn tại
  → `404 AUTH_CHALLENGE_NOT_FOUND` (anti-enumeration, không phân biệt hai trường hợp).
- Consume sai binding → `409 STEP_UP_CHALLENGE_MISMATCH`; sai user → 404.
- Binding chỉ kiểm ở backend; không có API chấp nhận ownership từ client ở task này (IMP-12).

### 2.3. Invariants (task §4)

1. Single-use: `consumed_at IS NULL` trong predicate consume + verify từ chối challenge consumed.
2. Hết hạn: `expires_at > now()` trong markVerified/bump/consume; verify/consume hết hạn → 410
   (`AUTH_OTP_EXPIRED` cho verify, `AUTH_CHALLENGE_EXPIRED` cho consume — 07 §6.6).
3. LOCKED không verify/consume được (state check trước + SQL condition chốt cuối).
4. Sai OTP không bao giờ tạo VERIFIED (matches trước, chỉ markVerified khi matches).
5. Max attempts: bump MỘT statement có `attempt_count + 1 >= max → status='LOCKED'`;
   CHECK `attempt_count <= max_attempts` ở DB chốt — không thể vượt.
6. Đúng OTP cũng tăng `attempt_count` (design/02 §5.1 bước 6), guard `attempt_count + 1 <= max_attempts`.
7. Consume atomic: một conditional UPDATE; hai consume đồng thời đúng một thắng (T-STEP-01, test T19).
8. Consistency READ COMMITTED: mọi conditional re-check trong UPDATE; race → re-read + map fail-closed.
9. T1 rollback: `consumed_at` rollback theo caller (JOIN, không TX riêng) — test T17.
10. Transition trái phép fail-safe: chỉ 3 path mutation (bump/markVerified/consume/expire) + INSERT;
    không có path tự chế状态.

### 2.4. TTL và issue (UC-003/AC-003 phần challenge)

- TTL 300s (`app.security.otp.ttlSeconds`), `max_attempts=5` — dùng đúng config có sẵn
  (`AppProperties.Security.Otp`, NFR-016); OTP length 6 (`07` §3.7; không thêm config mới).
- Status khởi tạo `CREATED` (`sent_at NULL`); **verify cho phép từ `CREATED` lẫn `SENT`**
  (design/05 §5.2 không gate verify vào SENT; SENT chỉ là marker delivery của IMP-06).
- Duplicate issuance: partial unique `uq_auth_challenge_active_stepup` → reissue idempotent
  trả lại challenge active hiện có cho cùng `(idempotency_key, request_hash)`; không audit
  `AUTH_CHALLENGE_CREATED` lần hai.
- Channel MVP mặc định `SMS` (CHECK `method='OTP' ⇒ channel NOT NULL`); policy kênh + resolve
  destination + dispatch sau commit thuộc BE-ID-IMP-06.

## 3. Transaction safety (T1)

- **Issue/verify:** OWN `TransactionTemplate` ngắn. Điểm hiệu chỉnh so với mô tả
  "lock row → verify" của design/02 §5.1: verify dùng **conditional UPDATE thay SELECT…FOR UPDATE**
  (DEC-ID-005) vì (1) rejection phải COMMIT attempt counter — bump trong cùng TX rồi throw sẽ
  rollback bộ đếm, phá invariant max attempts (bắt được bằng test thật); (2) không giữ row lock
  xuyên qua Argon2id ~100ms. Atomicity/race do chính conditional UPDATE bảo đảm (design/02 §9.1,
  pattern được task §7 cho phép); C2 audit cùng TX với markVerified (fail-closed).
- **Consume:** `@Transactional(MANDATORY)` — fail fast khi caller chưa mở TX (test T18);
  UPDATE điều kiện gồm ref+user+purpose+binding+`status='VERIFIED'`+`consumed_at IS NULL`
  +`expires_at > now()`; caller rollback → `consumed_at` rollback theo (test T17: state phục hồi
  `VERIFIED`, retry T1 consume lại được; sau khi commit → 409 vĩnh viễn).
- **JDBC layer:** `JdbcChallengeRepository` `@Transactional` (REQUIRED) trên mọi method — bắt buộc
  vì Hikari `auto-commit: false` (application.yml); verify path không outer TX → method tự mở TX
  ngắn COMMIT bộ đếm; consume JOIN TX caller.
- **Rejection consume:** business rejection (exception) → caller T1 rollback toàn bộ (design/02 §5.2
  bước 3); không có compensating update tự chế. `STEP_UP_REQUIRED` khi chưa VERIFIED kèm extension
  `challenge` công khai bắt buộc bởi `ProblemResponse.checkChallenge`.

## 4. Bằng chứng kiểm thử

Lệnh (thư mục `digitalbanking-backend`):

- `./gradlew test --tests 'com.simulatedbank.banking.identity.OtpChallengeServiceTest'` — **25/25 pass**.
- `./gradlew test --tests 'com.simulatedbank.banking.identity.OtpChallengePostgreSqlIntegrationTest'` — **22/22 pass** (PostgreSQL 16 Testcontainers, DB thật + constraint thật).
- Full regression: xem §4.1.

### Unit (`OtpChallengeServiceTest`, + `ChallengeState` qua U24)

| Test ID | Scenario |
|---|---|
| U01 | issue persist binding/TTL/max_attempts/otp_hash argon2id + audit C2; view không lộ OTP |
| U02/U03 | TRANSFER_STEP_UP thiếu binding → 400; LOGIN_NEW_DEVICE có binding → 400; không INSERT |
| U04/U05 | duplicate (key,hash) → reissue trả challenge hiện có; không còn active → rethrow |
| U06 | DB chết khi issue → 503 fail-closed |
| U07–U11 | verify: 404 anti-enum, sai purpose 409, LOCKED→423, EXPIRED→410, VERIFIED/CANCELLED→409 |
| U12 | quá TTL → expire transition + 410 |
| U13/U14 | sai OTP: bump + C3 `AUTH_STEP_UP_FAILED` → 401; đạt ngưỡng: LOCKED + C3 `AUTH_CHALLENGE_LOCKED` → 423 |
| U15/U16 | đúng OTP: VERIFIED + C2 cùng TX; thua race → re-read map fail-closed |
| U17 | bump thua race → 410 |
| U18–U23 | consume: thành công/predicate đúng; chưa verify→428 kèm challenge; consumed→409; binding lệch→409; hết hạn→410(+expire); DB chết→503 |
| U24 | transition matrix ChallengeState; không có state CONSUMED |

### Integration PostgreSQL (`OtpChallengePostgreSqlIntegrationTest`)

| Test ID | Scenario | AC |
|---|---|---|
| T01 | issue: status/attempt/TTL/binding/correlation_id/otp_hash argon2id (không plaintext); audit C2 trong security log, không OTP | AC-003 |
| T02 | issue context không hợp lệ → 400, 0 row | — |
| T03 | duplicate issuance → cùng challengeRef, 1 row (partial unique thật) | AC-033 |
| T04/T05 | sai OTP đếm đúng; 4 sai + lần 5 đúng → VERIFIED + verified_at + attempt=5 + consume T1 được | AC-003 |
| T06 | 5 sai liên tiếp → 423 + status LOCKED + audit C3; đúng OTP sau lock vẫn 423 | AC-003/max attempts |
| T07 | hết hạn → 410 + EXPIRED persist; verify lại vẫn 410 | AC-003/TTL |
| T08 | consumed → verify lại bị cấm 409 | single-use |
| T09/T10 | purpose mismatch 409; foreign user 404 không đụng state | binding |
| T11 | consume chưa verify → 428 + challenge extension, không chứa OTP | AC-033 |
| T12/T13 | binding lệch → 409 (không consume); foreign user 404; LOCKED → 423 | binding |
| T15 | consume hết hạn → 410; consumed_at NULL; retry vẫn 410 | TTL |
| T16 | consume T1 → consumed_at, status giữ VERIFIED; consume lại → 409 | single-use/atomic |
| **T17** | **T1 rollback: consumed_at hoàn về NULL, status VERIFIED; retry T1 thành công; commit rồi → 409** | **T1 rollback restores status** |
| T18 | consume ngoài TX → `IllegalTransactionStateException` (MANDATORY fail-fast) | design/05 §3.2 |
| T19 | 2 consume đồng thời (CyclicBarrier) → đúng 1 thắng, 1 `409 INVALID_STATE_TRANSITION` | T-STEP-01/AC-033 |
| T20 | 5 verify sai đồng thời → chỉ 401/423, luôn LOCKED, attempt_count=5 (không bypass) | concurrency |
| T21 | verify đúng + consume đồng thời → kết cục nhất quán (consume thắng VERIFIED+consumed, hoặc thua 428 + consumed_at NULL) | concurrency |
| T22 | expire trước consume → không bao giờ consumed-sau-expired | TTL race |
| T23 | OTP plaintext không có trong security log / DB / view | NFR-020 |

### 4.1. Regression full suite

`./gradlew test` (full) — **BUILD SUCCESSFUL: 0 fail / 0 error** (16/11/2026; trước IMP-05: 274
tests theo IMP-04, nay 321 tests = +25 unit +22 integration).

## 5. Tuần thủ kiến trúc

- 5 layer đúng: `identity/api` (port cross-module), `identity/domain` (state machine thuần),
  `identity/application` (service + port out + audit port), `identity/adapter/out` (JDBC, CSPRNG);
  không import transfer/admin/notification.
- Không đổi schema/migration (dùng đúng V2 của IMP-01); không thêm config key mới; không tạo
  endpoint trùng (verify HTTP thuộc IMP-12; login flow thuộc IMP-08; transfer T0/T1 thuộc IMP-07).
- Error mapping 100% theo catalog `07`/`ErrorCode` có sẵn; không tạo mã mới.

## 6. Còn mở (không tự đóng)

1. **OTP dispatch/delivery, resend, maskedDestination, gateway outage:** BLOCKED — module
   notification chưa có (`design/05` §11.3 `sendOtp`, MOD-OI-07); thuộc BE-ID-IMP-06. Challenge
   MVP đứng ở `CREATED` sau issue; verify cho phép CREATED/SENT (nêu §2.4).
2. **HTTP endpoints verify (API-AUTH-04/05), DTO/validation:** thuộc BE-ID-IMP-12; state machine
   đã sẵn sàng qua `StepUpChallengePort`/service.
3. **Audit persistence thật (audit_log trong TX):** `SecurityLogAuditAdapter` mock có kiểm soát
   theo DEC-ID-003; C2 fail-closed thật khi BE-ID-IMP-13 có module audit.
4. **Login-new-device flow (202 + challenge + session sau verify):** thuộc BE-ID-IMP-08; phần
   OTP AC-003 đã phủ ở T04–T07 (session tạo sau verify là bước IMP-08).
5. **Transfer T0/T1 thật (428 + retry):** thuộc BE-ID-IMP-07; hợp đồng port + T1 consume + race
   đã chứng minh bằng test gọi trực tiếp port (T11/T17/T19).
6. **VERIFIED→EXPIRED cosmetic persist:** `expireIfDue` cho VERIFIED-chưa-consume chỉ persist khi
   chạy ngoài outer TX; trong T1 rejection, rollback không giữ transition thẩm mỹ — enforcement
   an toàn nhờ `expires_at > now()` ở mọi gate (t15/t22). Scheduler purge/persist thuộc IMP-06/13.

## Change Log

| Version | Ngày | Người | Mô tả |
|---|---|---|---|
| 1.0 | 16/11/2026 | Đạt (`identity`) | Tạo BE-ID-IMP-05: `StepUpChallengePort`, `OtpChallengeService`, `JdbcChallengeRepository`, `ChallengeState`, `SecureRandomOtpCodeGenerator`, audit +4 action; 47 test (unit + PostgreSQL Testcontainers); DEC-ID-005. |
