# BE-1 — Identity, Security, Onboarding, CI Backend

**Người phụ trách:** BE-1
**Reviewer:** BE-4 (Integration Owner)
**Mục tiêu:** Khóa đúng lớp auth/security, chứng minh bằng test thật trên PostgreSQL, own onboarding controller/orchestration, xong CI backend + dependency/secret/log scan. Không ôm transfer/k6/FE.

---

## 1. Vai trò trong team

BE-1 là **owner duy nhất** của auth boundary. Mọi quyết định về login/session/JWT/CSRF/rate-limit/OTP/PIN/recovery do BE-1 chốt. Người khác cần đổi behavior auth phải gửi proposal, BE-1 review.

BE-1 không own Testcontainer base, shared error handler, account/operator services, transfer, k6 hoặc FE CI.


## 2. Phạm vi chi tiết

### 2.1 Được sửa (duy nhất BE-1 được merge trực tiếp)

```text
be/src/main/java/com/bank/simulator/identity/**
be/src/main/java/com/bank/simulator/customer/**
be/src/main/java/com/bank/simulator/shared/ratelimit/**
be/src/main/java/com/bank/simulator/shared/error/** (chỉ gửi auth error proposal; BE-4 owns shared handler)
be/src/test/java/com/bank/simulator/identity/**
be/src/test/java/com/bank/simulator/customer/**
be/src/test/java/com/bank/simulator/shared/ratelimit/**
be/src/test/java/com/bank/simulator/identity/web/OnboardingControllerTest.java
.github/workflows/backend-ci.yml (tạo mới, chỉ job backend)
docs/projects/backend-mvp/evidence/BE1-*.md (tự tạo)
```

### 2.2 Không được sửa

- `account/**`, `transfer/**`, `audit/**`, `risk/**` implementation. Chỉ đọc để hiểu boundary.
- `frontend/**`, `tests/load/**`, `.github/workflows/frontend-contract.yml`.
- `contracts/openapi.yaml`: chỉ gửi proposal cho BE-4.
- Migration V1–V7 đã apply: không sửa. Cần schema mới phải qua BE-4 cấp số V8+.

### 2.3 Shared ownership (điểm chạm duy nhất)

| File/vùng | Quy tắc |
|---|---|
| `SecurityConfiguration.java` | BE-1 own. Đổi cross-role phải có BE-4 review |
| `OnboardingController.java` | BE-1 owns controller and onboarding orchestration. BE-2 không sửa; chỉ gửi AccountCreation contract proposal nếu cần |
| `shared/error/**` | BE-4 owns global handler; BE-1 chỉ gửi auth error proposal |
| `DisposablePostgresTestSupport.java` | BE-4 owns; BE-1 consumes only |

## 3. Đầu vào (đọc trước khi code)

1. `be/src/main/java/.../identity/**`: login, refresh, logout, recovery, registration, OTP.
2. `SecurityConfiguration`: filter chain, JWT verifier, CSRF check, role mapping.
3. `shared/ratelimit`: bucket IP vs identifier, TTL, key format.
4. `docs/projects/backend-mvp/02-shared-security.md`, `03-identity-onboarding.md`.
5. `contracts/openapi.yaml`: auth error codes, 401/403/429 shape.
6. `DisposablePostgresTestSupport.java` contract from BE-4 for integration tests.

Dependency rule: BE-1 can implement auth and unit tests without BE-4 support. PostgreSQL integration tests consume BE-4 support; no private container fork.

## 4. Quy trình thực hiện chi tiết

### Bước 0 — Khảo sát hiện trạng (Tiên quyết — Chưa code)

- Liệt kê mọi endpoint auth: method + path + role + rate-limit key.
- Ghi ra giấy: token lưu đâu (memory vs cookie), refresh rotation thế nào, CSRF check ở đâu, recovery token TTL bao lâu, PIN lock sau mấy lần sai.
- Output: bảng 1 trang trong `BE1-auth-regression.md` mục "Hiện trạng". Nếu phát hiện behavior khác docs, ghi `MISMATCH` và báo BE-4 ngay, không tự sửa docs pass.

### Bước 1 — Chốt onboarding boundary và test support contract

- BE-1 owns `OnboardingController.java` và gọi onboarding/default-account flow qua contract hiện có.
- Không tạo `AccountCreationService` mới nếu chưa có quyết định chuyển ownership tạo account sang `account/**`.
- Nếu cần contract mới, ghi method signature, input/output, transaction owner, error mapping vào proposal gửi BE-2 + BE-4; không tự thêm abstraction.
- Dùng `DisposablePostgresTestSupport.java` do BE-4 cung cấp. Không tạo container/base riêng.

### Bước 2 — Auth/session regression

Viết/bổ sung test theo ma trận sau. Mỗi dòng là 1 test case thật, không test chay bằng mock DB (trừ OtpSender được mock):

| ID | Input | Kỳ vọng |
|---|---|---|
| AUTH-01 | Login phone đúng + password đúng | 200, access token memory, refresh cookie HttpOnly |
| AUTH-02 | Login sai password | 401 đúng Problem.code, không leak user tồn tại hay không |
| AUTH-03 | Login bằng email | 400/404 theo contract (email không phải identifier login) |
| AUTH-04 | JWT hết hạn | 401 |
| AUTH-05 | JWT sai chữ ký/sai key | 401 |
| AUTH-06 | Customer gọi operator API | 403 deny-by-default |
| AUTH-07 | Auditor gọi mutate | 403 |
| AUTH-08 | 2 refresh concurrent cùng token | 1 winner 200, 1 loser 401, token cũ hết hiệu lực |
| AUTH-09 | Logout | refresh revoke, gọi lại 401 |
| AUTH-10 | Recovery confirm | revoke toàn bộ refresh sessions |
| AUTH-11 | Thiếu/sai X-CSRF-Token ở refresh/logout | 403 |
| AUTH-12 | Login 6 lần/60s cùng IP | lần 6 bị 429, bucket identifier riêng không ảnh hưởng user khác |

Cách làm từng test:

1. Viết test đỏ trước (TDD): chạy, xem fail đúng lý do missing/wrong.
2. Fix minimal trong `identity/**`.
3. Chạy lại đơn test đó pass.
4. Chạy cả package không vỡ test cũ.

Lệnh:

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Security*Test,*Onboarding*Test,*Refresh*Test" test
```

### Bước 3 — OTP/PIN/recovery atomicity (Song song hoặc sau Bước 2)

| ID | Input | Kỳ vọng |
|---|---|---|
| OTP-01 | OTP có leading-zero `001234` | verify đúng, không trim sai |
| OTP-02 | DB chỉ lưu hash, không plaintext | query DB kiểm tra |
| OTP-03 | Sai OTP 1–4 lần | vẫn pending, attempt tăng |
| OTP-04 | Sai lần 5 | invalidate, đúng contract code |
| OTP-05 | Registration proof dùng 2 lần | lần 2 fail, one-use |
| OTP-06 | Resend OTP | OTP cũ vô hiệu |
| OTP-07 | 2 register concurrent cùng phone/email | 1 commit 201, 1 conflict 409 |
| REC-01 | Recovery initiate user không tồn tại | vẫn generic 200/202, không oracle |
| REC-02 | Recovery verify đúng → reset token one-use | dùng lại fail |
| REC-03 | Recovery confirm | đổi password, revoke sessions |
| PIN-01 | Setup/change/reset PIN | đúng flow, sai 5 lần lock 15 phút |

Lưu ý:

- OtpSender mock trong test, nhưng uniqueness/concurrency bắt buộc PostgreSQL (DisposablePostgresTestSupport do BE-4 cung cấp).
- Không log OTP/password/token. Test nào cần assert log thì capture và kiểm tra không chứa secret.

Lệnh:

```powershell
mvn -q -Dtest="*Otp*Test,*Registration*Test,*Recovery*Test" test
```

### Bước 4 — Rate-limit 2 bucket (Gộp vào Bước 2 hoặc làm độc lập)

- Chứng minh IP bucket và identifier bucket độc lập: flood từ 1 IP block IP đó nhưng user khác IP khác vẫn pass; flood 1 identifier block identifier đó nhưng identifier khác vẫn pass.
- Cấu hình chốt: login 5/60s, register OTP 3/300s, recover initiate 3/300s, recover verify 5/300s. Đổi số phải báo BE-4 + FE/BE (ảnh hưởng E2E/k6).
- Output: bảng số + test log.

### Bước 5 — OTP dispatch failure, mailbox guard + secret/log scan

1. OTP dispatch failure: force `OtpSender` failure after `AWAITING_OTP` boundary. Assert `503 SERVICE_UNAVAILABLE`, proof/OTP retirement semantics, `AWAITING_OTP -> FAILED` only, terminal state not overwritten. Transfer-side state assertion belongs to BE-3.
2. Mailbox (local OTP viewer): only profile `local/demo`. Prod profile bật mailbox là fail review.
3. Secret scan: grep `.env`, `JWT_SECRET`, `password`, `otp`, `BEGIN PRIVATE KEY` trong `be/src`, log, artifact. Có hit là phải fix, không ghi "để sau".
4. Sensitive-log review: chạy 1 flow register→login→transfer→recovery, capture log, assert không có password/PIN/OTP/token/account full.
5. Dependency scan: `mvn dependency:tree` + tool team chốt (OWASP DC hoặc tương đương). Ghi version tool + số CVE critical/high.

### Bước 6 — CI backend (Sau khi test auth & scan ổn định)

Tạo `.github/workflows/backend-ci.yml` chỉ job backend:

```yaml
- mvn -q -DskipTests package
- mvn -q test
- python be/scripts/validate-openapi.py
- upload surefire reports (không secret)
```

Không bỏ k6/FE vào file này. FE CI do FE/BE own file riêng.

Tiêu chí: push branch test → CI xanh. Fail thì fix code, không disable test để xanh giả.

## 5. Test và lệnh nghiệm thu

Lệnh chuẩn BE-1 (ghi exact output vào evidence):

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Security*Test,*Onboarding*Test,*Recovery*Test,*Registration*Test,*Otp*Test,*Refresh*Test" test
mvn -q -DskipTests package
python scripts/validate-openapi.py
cd ..
git diff --check
```

Bắt buộc PostgreSQL/Testcontainers cho uniqueness/concurrency. Mock/H2 không đủ evidence lock. Testcontainers fail thì ghi exact error, phân loại env/tool/code, không thay shared PG, không ghi pass.

## 6. Tiêu chí đạt (DoD) — thiếu 1 là chưa xong

- [ ] `DisposablePostgresTestSupport.java` from BE-4 is used for PostgreSQL integration tests; no private container/base fork.
- [ ] AUTH-01→12 pass trên PostgreSQL, log đính kèm.
- [ ] OTP/PIN/recovery matrix pass, DB không plaintext secret.
- [ ] Rate-limit 2 bucket chứng minh độc lập.
- [ ] Mailbox chỉ local/demo, prod tắt.
- [ ] Secret/log scan 0 hit敏感, dependency scan có report.
- [ ] `backend-ci.yml` xanh trên GitHub Actions.
- [ ] Không sửa migration cũ, không đổi OpenAPI lén.

## 7. Nghiệm thu (ai check, check gì)

- BE-4 check: chạy lại lệnh mục 5 trên máy sạch, so output với evidence BE-1 nộp. Khớp pass count + không secret mới ACCEPT.
- BE-3 check boundary: PIN verify API/result, OTP consume once, lock semantics đủ để transfer dùng. Thiếu là BE-1 bổ sung.
- FE/BE check handoff: auth endpoint list + cookie/CSRF sequence chạy được thật, không phải docs suông.

Fail nghiệm thu nếu: test chỉ pass bằng H2/mock cho case cần PG, CI xanh bằng cách skip test, log còn secret, OpenAPI drift không proposal.

## 8. Bàn giao (trả về cái gì, docs nào, cho ai)

Output: `BE1-auth-regression.md`, `BE1-otp-pin-recovery.md`, `BE1-onboarding.md`, `BE1-ci-scan.md`.

- `BE1-auth-regression.md`: hiện trạng + ma trận AUTH-01→12 + exact command + pass/fail + duration.
- `BE1-otp-pin-recovery.md`: ma trận OTP/REC/PIN + DB hash proof + OTP dispatch failure + error code table.
- `BE1-onboarding.md`: registration/counter creation proof, owner boundary, account creation proposal if needed, FE handoff.
- `BE1-ci-scan.md`: CI file path + run link + dependency scan + secret/log scan output (redacted).


Cho BE-3: PIN verify contract, OTP consume boundary, wrong-attempt commit behavior.
Cho BE-4: auth audit events cần log, rate-limit metrics, scan output gốc.
Cho FE/BE: endpoint list, cookie/CSRF sequence, `UserSummary` + staff alias cảnh báo, OTP TTL/attempt, mailbox guard, synthetic credential setup qua env. Không gửi OTP/password/token thật.

## 9. Cấm và chống conflict

- Không chạm `transfer/**`, `account/**`, `audit/**`, `risk/**`.
- Không tạo container riêng ngoài base. Cần helper mới thì proposal.
- Không commit `.env`, secret, OTP thật. Không `docker compose down -v`, `DROP`, `TRUNCATE`.
- 1 PR 1 gói (A/B/C). Không trộn migration + refactor + feature.
