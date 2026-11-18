# BE-ID-IMP-06 — OTP gateway delivery, timeout và expiry recovery

> **Ngày:** 16/11/2026 · **Owner thực hiện:** Codex (Identity) · **Trạng thái: PARTIAL**
> **Branch:** `datht-backend-rework` · **Baseline đã fetch:** `origin/dev2@56210cc80ad742ddad9139dc1a08fe9ff524465a` (trùng HEAD).
> Giữ nguyên IMP-01..05 và các thay đổi chưa commit đã có. Không dùng main/Phuong/common, không reset/rebase/push/merge/PR. Không tạo commit trong task này.

## 1. Nguồn và phạm vi

Nguồn: `docs-common/04. Database design & analysis.md` §4.2.5; `07. API contracts.md` §6, §8.1.8; `design/05. Modules contract.md` §5.2, §11.3, §13.1, MOD-OI-07/09; `design/04. security.md`; BE-ID-DOC-01 §6.2–6.5, BE-ID-IMP-05; DEC-ID-006.

**Blocker kiến trúc xác định trước khi sửa:** MOD-OI-07 vẫn OPEN; Notification trên dev2 chỉ có package skeleton. Không có thiết kế được duyệt cho protected durable OTP handoff, lease/attempt/backoff/dead-letter, resend generation/counter hoặc contact resolver/provider thật. Hash không thể dùng làm payload gửi lại; mã hóa OTP bằng khóa PII không được tài liệu này cho phép. Do đó chỉ triển khai phần độc lập an toàn; không đánh DONE vì các acceptance bắt buộc còn thiếu.

## 2. File thay đổi

Các path bên dưới tương đối với root repository.

| Path | Mục đích |
|---|---|
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/notification/api/OtpGateway.java` | Provider-owned synchronous sendOtp port; routing handle user ID, ephemeral char[], normalized outcome/failure; yêu cầu trusted-contact resolution và finite transport deadline. Không production fake. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/application/OtpDispatchService.java` | Suspend transaction caller; single send; normalize response/exception không lộ cause; conditional local ACK; bounded metric labels, coded log. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/application/OtpChallengeService.java` | INSERT + C2 audit cùng OWN REQUIRES_NEW TX; chỉ send sau commit; wipe OTP; duplicate CREATED không redispatch; scope user/purpose và expiry check; chỉ SENT mới verify. Vá audit issue trước đây nằm sau commit. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/application/ChallengeRepository.java` | Thêm markSent với verifier fence và expireDue batch; mô tả SENT-gated verify. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/adapter/out/JdbcChallengeRepository.java` | Conditional CREATED→SENT đúng verifier/chưa hết hạn; verify/failed-attempt SQL chỉ nhận SENT; expiry CTE SKIP LOCKED. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/application/OtpExpiryRecoveryService.java` | Một batch 100 expired active/unconsumed row trong REQUIRES_NEW; metric sau commit; không redelivery. |
| `digitalbanking-backend/src/main/java/com/simulatedbank/banking/identity/adapter/in/OtpRecoveryJob.java` | Scheduled expiry-only recovery; ISO duration app.identity.otp-expiry-sweep-delay, default PT1M; initial delay bằng interval. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/OtpDispatchServiceTest.java` | Normalization/failure vectors, no-provider, ambiguity/no-retry, safe errors/logs, transaction suspension và ack fencing trên PostgreSQL. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/OtpDeliveryPostgreSqlIntegrationTest.java` | Real DB commit/rollback/concurrency/failure injection và expiry recovery; fake gateway chỉ trong test. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/OtpChallengeServiceTest.java` | Cập nhật constructor; duplicate SENT contract; thêm unsent gate, pending duplicate, foreign binding và format/wipeable buffers của CSPRNG generator. |
| `digitalbanking-backend/src/test/java/com/simulatedbank/banking/identity/OtpChallengePostgreSqlIntegrationTest.java` | Inject accepted fake gateway; kỳ vọng SENT sau issue; deterministic OTP fixture không trùng mã sai 000000. |
| `digitalbanking-docs/docs-backend/modules/identity/BE-ID-IMP-06.md` | Báo cáo hiện tại, evidence và acceptance matrix. |
| `digitalbanking-docs/docs-backend/modules/identity/BE-ID-IMP-05.md` | Chú thích superseded CREATED-verification/audit/delivery behavior; giữ evidence lịch sử. |
| `digitalbanking-docs/docs-backend/modules/identity/README.md` | Link báo cáo và trạng thái PARTIAL. |
| `digitalbanking-docs/docs-backend/modules/identity/tasks/README.md` | Giữ IMP-06 [ ], ghi phần có/blocked và evidence. |
| `digitalbanking-docs/docs-common/design/05. Modules contract.md` | §11.3.1 ghi alternative adapter boundary khi dependency chưa có; không đóng MOD-OI-07/09. |
| `DECISIONS.md` | DEC-ID-006 ghi transaction, confidentiality, fail-closed và các blocker. |

## 3. Database và compatibility

Không thêm/sửa migration, enum hay bảng. Dùng `auth_challenge` V2 hiện có: `otp_hash`, `status`, `sent_at`, `expires_at`, `consumed_at`, unique active step-up. Không ghi plaintext hoặc ciphertext OTP vào DB/outbox/queue/audit. CREATED row biểu thị challenge chưa được local ACK; đây **không phải** outbox có khả năng redelivery.

Cutover fail-closed: CREATED cũ của IMP-05 không còn verify OTP được, sẽ expire; client phải bắt đầu challenge hợp lệ sau expiry. Không tự đánh SENT cho dữ liệu cũ. Verify SENT, single-use consume, attempt limit và TTL giữ nguyên; domain transition chung CREATED không bị đổi vì còn phương thức FIDO/PASSCODE.

## 4. Dispatch, timeout và recovery

1. Validate purpose/user/binding/correlation; sinh 6 chữ số bằng SecureRandom hiện có, hash bằng approved implementation Argon2id (không đổi verifier construction của IMP-05).
2. OWN REQUIRES_NEW INSERT challenge CREATED và audit C2 trong cùng TX. Audit exception rollback cả INSERT, không gọi gateway.
3. Sau commit, NOT_SUPPORTED suspend transaction caller; truyền borrowed char[] tới gateway theo challenge reference, internal user routing handle, SMS và expiry. Không khóa DB trong external call.
4. ACCEPTED/NONE mới markSent bằng conditional UPDATE đúng ID/verifier/status/expiry; sent_at = clock_timestamp(). ACCEPTED là provider acceptance, không phải chứng minh người dùng đã nhận.
5. RETRYABLE/FAILED, timeout, unavailable, throttle, rejection, null/contradictory response hoặc ACK lỗi đều trả safe OTP_DISPATCH_FAILED; không log provider body/message/cause, không auto-retry. TIMEOUT/UNAVAILABLE là classification, không phải bảo đảm chưa gửi.
6. Duplicate TRANSFER_STEP_UP nhờ unique active key/hash: SENT/VERIFIED trả cùng reference; CREATED trả 503, không lấy OTP mới gửi cho verifier cũ; foreign user/purpose bị từ chối. Chưa chứng minh idempotency toàn bộ login/SENSITIVE_ACTION do thiếu context/constraint tương ứng.
7. Expiry job chạy mỗi PT1M mặc định, một batch ≤100, FOR UPDATE SKIP LOCKED; chỉ CREATED/SENT/VERIFIED chưa consumed và đã hết hạn → EXPIRED. Nhiều worker có thể cùng chạy; không thay terminal/consumed hay hồi sinh challenge.

### Crash windows

| Window | Hành vi hiện tại | Giới hạn |
|---|---|---|
| Trước T0 commit | Rollback, không send | Test audit failure chặn send |
| Sau commit/trước dispatch, hoặc process chết trong dispatch | CREATED tồn tại; verify bị chặn; expiry job dọn sau TTL | Không còn plaintext trong process nên không redeliver; chưa có process-kill/restart delivery worker test |
| Provider accepted nhưng chưa local ACK | CREATED, có thể đã physical delivered; replay không redispatch và code không verify được | Test inject local ACK failure; không tuyên bố process termination thật |
| Đã SENT | Có thể verify đúng một lần; expiry/attempt/consume gates còn hiệu lực | Không retry/send duplicate từ replay step-up |
| Recovery worker mới hoặc concurrent | Scan DB theo expiry, idempotent expiry transition | Chỉ expiry recovery, không lease/dispatch recovery |

**Resend chưa triển khai:** vẫn giữ policy `07` rotate OTP/reset TTL/không reset verify attempts, cooldown 60s và max 3 từ config; không có counter/generation/in-flight fencing schema được duyệt để triển khai atomic nhiều instance. Không mở endpoint giả, không đếm bằng RAM hay mượn verify attempts. Cần forward migration và protocol MOD-OI-07 trước API-AUTH-08 end-to-end.

## 5. Security, session và observability

- OTP chỉ ở borrowed char[] trong synchronous request, wipe finally cả success/error; không durable secret payload, không mã hóa OTP có thể giải mã. Không suy diễn mã từ DB.
- Reuse Argon2id constant-time comparison/attempt/expiry/replay enforcement của IMP-05; service và SQL đều chặn verify CREATED. markSent có verifier fence và wall-clock expiry, không resurrect trạng thái khác.
- Issue/delivery/expiry không gọi session repository, không issue access/refresh/session token. Test kiểm 0 session ở cả delivery failure và direct OTP verification. **Không coi đó là bằng chứng login MFA hoàn chỉnh:** password login hiện chưa có device-trust gate; login-new-device 202→verify→atomic session activation phụ thuộc IMP-08, chưa sửa/claim đạt AC-003 end-to-end.
- `auth.otp.delivery{outcome,failure}` phân biệt acceptance, timeout, unavailable, throttle, rejected, invalid, unconfigured, ambiguous; không ID/PII/OTP/hash trong labels. `auth.otp.recovery{outcome=EXPIRED}` tăng số row sau expiry commit.
- Coded OTP_DELIVERY log chỉ outcome/failure/reference/correlation, không cause/provider body/secret. SafeJsonLogEncoder hiện chỉ allowlist chi tiết HTTP, nên stdout production không xuất riêng các trường OTP_DELIVERY; correlation/request MDC vẫn theo common khi có request. Durable per-attempt audit/full event catalog và operational alerts chưa có: không suy ra từ metric hoặc test ListAppender.
- Audit CREATED/verification giữ AuthAuditPort/C2/C3 hiện có; SecurityLogAuditAdapter vẫn là adapter tạm theo DEC-ID-003, không phải audit_log persistence thật.
- External physical delivery dedupe chưa có provider để chứng minh. Challenge ref là key đề nghị adapter dùng nếu provider hỗ trợ; **không exactly-once external delivery**.

## 6. Kiểm thử

Lệnh thực chạy tại `digitalbanking-backend`: `./gradlew check --console=plain` — **BUILD SUCCESSFUL**, 2m54s; Java 21, Docker 29.7.2, PostgreSQL 16 Testcontainers. XML JUnit: **382 tests, 0 failures, 0 errors, 0 skipped**; Identity **171/171 pass**. Trong đó `OtpChallengeServiceTest` 29, `OtpChallengePostgreSqlIntegrationTest` 22, `OtpDispatchServiceTest` 36, `OtpDeliveryPostgreSqlIntegrationTest` 21. So với evidence IMP-05: +61 cases (4 unit +36 dispatch vectors/DB +21 delivery integration). Notification chưa có regression suite/provider riêng: gateway fake và port được kiểm qua các suite Identity trên. Không gọi provider thật.

`check` đã chạy Java main/test compilation, bootJar, toàn bộ regression/ArchUnit và JaCoCo; project không cấu hình formatter/linter task riêng nên không tuyên bố đã chạy chúng. Có compiler note unchecked operations ở OTP unit test và log DB/scheduler errors ở giai đoạn shutdown cuối command; không có JUnit failure/error/skip. Raw command output: `/tmp/be-id-imp-06-check.log`; JUnit XML: `digitalbanking-backend/build/test-results/test/`; HTML: `digitalbanking-backend/build/reports/tests/test/index.html`.

Test mới kiểm durable state (không chỉ HTTP): committed row nhìn thấy từ independent TX tại gateway; rollback audit; suspend caller TX + caller rollback; missing provider; timeout/throttle/reject/unavailable/invalid responses; concurrent duplicate request; failed local ACK/no redispatch; expiry in-flight; concurrent correct verification/replay; expired CREATED/SENT/VERIFIED sweep; consumed/terminal unchanged; concurrent expiry workers; no secrets trong DB/response/log và borrowed buffer wipe. Fixture deterministic, không gọi provider thật.

**Chưa chạy được vì chưa có implementation/contract:** resend cooldown/max/concurrent resend; bounded durable retry/backoff/exhaustion; duplicate queue delivery; dispatch lease/stale-processing takeover/worker restart; actual process-kill crash tests; HTTP new-device MFA/session atomic activation; real provider contact/config/deadline/dedupe. Đây là functional/architectural blocker, không phải lý do skip test đang tồn tại.

## 7. Acceptance / Definition of Done

| Tiêu chí | Trạng thái thực tế |
|---|---|
| Gateway theo Notification/MOD-OI-07 | PARTIAL: consumer seam + test fake; provider/contact/deadline thật và owner approval còn thiếu |
| Không plaintext persistence | Đã triển khai hash-only + ephemeral handoff, không secret outbox |
| Dispatch chỉ sau commit | Đã triển khai OWN commit + NOT_SUPPORTED; audit rollback không send |
| Resend cooldown/max | BLOCKED: schema/protocol chưa duyệt |
| Idempotency/concurrency | PARTIAL: TRANSFER_STEP_UP unique/replay + verify/consume; login/SENSITIVE_ACTION/queue chưa đủ |
| Timeout/bounded retry | PARTIAL: normalize/fail-closed; transport deadlines/provider và retry policy chưa có |
| Crash recovery | PARTIAL: expiry-only; durable redelivery/lease/process-kill chưa có |
| Không reactivate expired/consumed | Conditional ACK + expiry sweep giữ fail-closed |
| Gateway failure không tạo session | Direct OTP path giữ 0 session; login MFA end-to-end BLOCKED IMP-08 |
| Secret-safe observability | Coded metrics/log/error và leak assertions; durable delivery audit/catalog/alerts chưa có |
| Automated/regression pass | 382 pass, 0 failure/error/skip; Identity 171 pass; các path chưa có implementation liệt kê §6 chưa được chứng minh |
| Identity docs/checklist | Cập nhật; IMP-06 giữ [ ], không DONE |

## 8. Điều kiện gỡ blocker

Identity + Notification + Security + Product cần chốt protected short-lived durable secret handoff (hoặc protocol regenerate/invalidate được duyệt), khóa/retention/cleanup, generation + resend counter + last-resend, lease/fencing + provider idempotency và ambiguous-outcome policy, bounded attempts/backoff/exhaustion/dead-letter, contact resolver/channel policy; DB owner duyệt forward migration. Sau đó mới triển khai và chạy resend/durable recovery/process-kill/real-provider contract tests. MOD-OI-07/09 và BE-ID-IMP-06 không được đóng bằng báo cáo này.

## 9. Git summary

- Branch giữ nguyên `datht-backend-rework`; HEAD/baseline `56210cc80ad742ddad9139dc1a08fe9ff524465a`; đã fetch `origin/dev2` trước khi sửa.
- Không commit, push, merge, rebase, reset hoặc tạo PR. Không có resulting commit mới.
- Working tree vẫn chứa thay đổi chưa commit của người dùng và phần IMP-06. Trạng thái đầu task: 11 tracked modified, 37 untracked; không phải clean baseline. Các file IMP-06 đã tạo/sửa liệt kê chính xác ở §2, không nhận toàn bộ dirty tree là thay đổi của task này. Không chạy Git để re-audit các edit sau verification.
