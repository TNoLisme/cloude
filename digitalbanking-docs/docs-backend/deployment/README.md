# Hướng dẫn Triển khai & Vận hành Backend (`docs-backend/deployment/`)

> **Dự án:** Digital Banking MVP (Single-Bank, Core Simulated)
> **Mã tài liệu:** `BE-DEP-01`
> **Phiên bản:** v1.2
> **Ngày cập nhật:** 12/11/2026
> **Trạng thái:** Draft — Hướng dẫn triển khai & vận hành; cấu hình chi tiết tại [[digitalbanking-docs/docs-backend/deployment/configuration.md]]
> **Tài liệu cấu hình:** [[digitalbanking-docs/docs-backend/deployment/configuration.md]] (`BE-DEP-02`)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/05. Technical stack.md]], [[digitalbanking-docs/docs-common/06. System architecture.md]] (§9, §11, §12), [[digitalbanking-docs/docs-common/07. API contracts.md]] (§3, §8.9), [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]], [[digitalbanking-docs/docs-common/09. Test plan.md]] (§7–§8, §10), [[digitalbanking-docs/docs-common/10 Master timelines.md]] (W7–W8), [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] (§7–§8), [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] (§10, §12), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§10, §13), [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] (§4–§6), [[digitalbanking-docs/docs-common/design/04. security.md]] (§4–§9)

---

## 1. Mục đích và phạm vi

### 1.1. Mục đích

Tài liệu này là **hướng dẫn triển khai và vận hành** Backend Spring Boot Modular Monolith + PostgreSQL 16 cho các môi trường `local`, `test` và `demo`/Cloud (W7–W8). Tài liệu trả lời:

- Cần chuẩn bị gì trước khi build/deploy?
- Thứ tự triển khai, kiểm tra health và smoke test như thế nào?
- Release gate, backup/restore và rollback được thực hiện ra sao?
- Khi có sự cố, operator làm gì và không được làm gì?

**Toàn bộ giá trị cấu hình** (biến môi trường, `application.yml`, DB role, migration, metric, alert threshold) được chuyển sang [[digitalbanking-docs/docs-backend/deployment/configuration.md]]. README không lặp lại các bảng cấu hình để tránh sai lệch giữa hai tài liệu.

### 1.2. Phạm vi

| Trong phạm vi | Ngoài phạm vi |
|---|---|
| Quy trình build, deploy `local`/`test`/`demo` | Production hardening, HA, multi-region DR |
| Release gate, smoke test, evidence | IaC/terraform và provider-specific deployment |
| Backup/restore, rollback, runbook vận hành | Thiết kế chi tiết module/class |
| Kiểm chứng `DVT-001`–`DVT-010`, `D1–D5` ở mức quy trình | Chạy nghiệm thu thay cho test plan `09` |

### 1.3. Nguyên tắc sử dụng

1. **Đọc `configuration.md` trước khi deploy.** Mọi secret, profile, DB role và threshold phải lấy từ tài liệu cấu hình, không tự suy đoán từ README.
2. **`docs-common/` là chuẩn gốc.** Tài liệu Backend là downstream; khi phát hiện khác biệt, sửa tài liệu Backend và ghi nhận điểm cần đồng bộ. Các điểm đã đối chiếu được liệt kê tại `configuration.md` §9.
3. **Không tuyên bố vượt evidence.** Tài liệu này là baseline thiết kế/hướng dẫn; chưa xác nhận runtime. Không dùng `PRODUCTION-READY`, `PASS` hoặc `VERIFIED` nếu chưa có bằng chứng thực thi (`08` §2.2).
4. **Tiền phải đúng.** Không báo thành công giả, không sửa/xóa `ledger_entry`/`audit_log`, không đánh dấu `COMPLETED` khi thiếu cặp bút toán và audit hợp lệ.

---

## 2. Bản đồ tài liệu và tiên quyết

### 2.1. Bản đồ tài liệu

| Tài liệu | Vai trò |
|---|---|
| [[digitalbanking-docs/docs-backend/deployment/configuration.md]] | Cấu hình môi trường, `.env`, `application.yml`, DB role/migration, observability |
| [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] | Deployment contract, environment, topology, release gate, backup/DR, runbook gốc |
| [[digitalbanking-docs/docs-common/09. Test plan.md]] | Kịch bản `D1–D5`, evidence matrix, acceptance |
| [[digitalbanking-docs/docs-common/10 Master timelines.md]] | Mốc W7 (Cloud deployment) và W8 (Cloud verification/demo/handover) |
| [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] | Owner module và phối hợp triển khai |
| [[digitalbanking-docs/docs-common/05. Technical stack.md]] | Stack baseline, build, container, CI/CD |
| [[digitalbanking-docs/docs-common/06. System architecture.md]] | Topology, transaction, security, observability |
| [[digitalbanking-docs/docs-common/07. API contracts.md]] | Hợp đồng API, health, error, OpenAPI |
| [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] | Audit, hash chain, retention, verification |
| [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] | Lock/retry, reconciliation, alert/runbook |
| [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]] | Fraud rule, threshold, review |
| [[digitalbanking-docs/docs-common/design/04. security.md]] | Session, OTP, crypto, DB role, rate limit |

### 2.2. Tiên quyết môi trường

| Thành phần | Yêu cầu |
|---|---|
| Runtime | Java 21 LTS; Spring Boot 4.x (fallback 3.5.x nếu tương thích thư viện); Gradle Kotlin DSL (`05` §4.1, §8.1) |
| Database | PostgreSQL 16.x; Testcontainers PostgreSQL 16 cho `test` |
| Container | Docker + Docker Compose; Nginx stable cho TLS/static/reverse proxy (`05` §5.7) |
| Build | Gradle Wrapper; dependency pin; không dùng dynamic version (`05` §8.2) |
| Network | HTTPS/domain/TLS cho `demo`; PostgreSQL chỉ trong private network (`08` §3.3) |
| Secret | Secret store/environment variable/Docker Secret; không commit `.env` (`08` §6.1) |
| Evidence | Nơi lưu deployment log, test report, health output, backup checksum, ảnh/video demo |

> **Lưu ý:** Tên file/câu lệnh implementation cụ thể chưa được chốt trong `docs-common` (`OI-DEP-03`). Các lệnh dưới đây là baseline theo stack `05`/`06`; cập nhật khi repository Backend được khởi tạo và CI/CD được chốt.

---

## 3. Quy trình triển khai

### 3.1. Chuẩn bị cấu hình

1. Sao chép `.env.example` thành `.env` cho `local`; trên `demo`/Cloud nạp biến môi trường qua secret store.
2. Đặt `SPRING_PROFILES_ACTIVE` đúng môi trường (`local`, `test`, `demo`).
3. Điền `DB_APP_PASSWORD`, `DB_MIGRATION_PASSWORD`, `APP_PII_AES_KEY_BASE64`, `APP_HMAC_KEY_BASE64`; kiểm tra khóa Base64 đúng 32 byte (`configuration.md` §4).
4. Kiểm tra `APP_CORS_ALLOWED_ORIGINS`, `APP_COOKIE_SECURE`, DB pool và các threshold trong `application.yml` (`configuration.md` §5).
5. Xác nhận `banking_app`/`banking_migrator` tách biệt và quyền runtime đúng least-privilege (`configuration.md` §6.3).
6. Không commit `.env`, secret hoặc khóa vào Git/image.

### 3.2. Build và kiểm thử

```bash
# Chạy toàn bộ kiểm tra backend (unit, slice, integration với Testcontainers)
./gradlew clean check

# Build artifact phát hành
./gradlew clean build
```

- CI phải chạy thêm OpenAPI validation, Docker build và Trivy scan theo `05` §5.7.3/`06` §11.4.
- Không deploy nếu test critical fail, OpenAPI lệch contract hoặc security scan còn Critical/High chưa xử lý (`08` §7.2).
- Build artifact phải bất biến và có version/commit identifier; không deploy mutable artifact (`08` §7.2).

### 3.3. Triển khai `local`

```bash
# Compose hiện có postgres/backend; Nginx và monitoring chưa nằm trong file local
docker compose up -d

# Theo dõi log backend (Flyway chạy lúc khởi động bằng DB_MIGRATION_USER)
docker compose logs -f backend

# Port local mặc định trong compose.yaml là 127.0.0.1:18080
curl -fsS http://localhost:18080/actuator/health
```

Kỳ vọng:

- Flyway chạy sạch trên database `local`; không có migration lỗi/checksum mismatch.
- `/actuator/health` trả `200 {"status":"UP"}` khi ứng dụng và PostgreSQL sẵn sàng; `503 {"status":"DOWN"}` khi mất DB/pool cạn kiệt.
- Chỉ dùng dữ liệu synthetic; không dùng PII thật (`NFR-032`).

### 3.4. Triển khai `demo`/Cloud (W7)

**Gate hiện tại: NO-GO** — [task 07](../modules/common/report/BE-COM-IMP-07-verification.md) còn thiếu consumer thật; owner/URL Cloud chưa được cấp. [Common readiness/runbook](common-readiness.md) và [verification 08](../modules/common/report/BE-COM-IMP-08-verification.md) tách W6 local với W7/W8 chưa chạy. Các bước dưới đây là quy trình sau khi đủ gate, không phải trạng thái đã deploy.


1. Provision PostgreSQL 16, private network, Nginx/TLS, secret store và backup storage.
2. Deploy immutable backend image; nạp biến môi trường/secret theo `configuration.md` §4.
3. Để Flyway chạy migration bằng `banking_migrator` lúc khởi động; ứng dụng runtime dùng `banking_app`.
4. Kiểm tra URL HTTPS, health, kết nối DB, log khởi động và migration version.
5. Ghi lại deployment log, URL, source revision, migration version, cấu hình phiên bản (đã che secret) và kết quả health.
6. W7 chỉ xác nhận **deploy thành công**, chưa kết luận toàn bộ nghiệp vụ đã vượt kiểm thử Cloud (`10` §8.2).

### 3.5. Kiểm tra lại trên Cloud (W8)

- Chạy regression các hành trình critical: đăng nhập, tài khoản, chuyển tiền PIN/OTP, lịch sử, quyền vận hành.
- Kiểm tra lại backup/restore, health, log, metric và alert.
- Sửa lỗi sau deploy, cập nhật phiên bản, deploy lại và xác minh (`10` §4.8).
- Hoàn thiện evidence package, video/slide demo và bàn giao (`10` §8.3).

### 3.6. Smoke test sau deploy

| # | Kiểm tra | Kỳ vọng |
|---:|---|---|
| 1 | `/actuator/health` | `200 {"status":"UP"}`; không lộ chi tiết/secret |
| 2 | `/api/v1/health` với Admin/Auditor | `200` khi `UP`/`DEGRADED`; `503` khi `DOWN` |
| 3 | Đăng nhập thử nghiệm | Session/cookie hợp lệ; audit ghi nhận |
| 4 | Transfer mẫu | Trả kết quả đúng; đủ cặp `ledger_entry`; audit completion; notification PENDING/outbox |
| 5 | Log | Có `correlation_id`, `request_id`, `action`, `outcome`; không có secret/PII |
| 6 | Metrics | Endpoint nội bộ trả metric; không public Internet |
| 7 | Backup | Có backup trước demo/migration; checksum và restore test đã ghi nhận |

Nguồn: `08` §7.2, §9, `07` §8.9.1, `06` §12.

---

## 4. Release gate và evidence

### 4.1. Pre-release gate checklist

| Gate | Bằng chứng bắt buộc | Khi fail |
|---|---|---|
| **Gate 1 — API contract** | OpenAPI `07`/`openapi/digital-banking-v1.yaml` khớp Controller/DTO; CI validation pass | Dừng release; sửa contract trước |
| **Gate 2 — Database migration** | Flyway chạy sạch trên PostgreSQL trống; `ddl-auto=validate` pass | Dừng release; không sửa migration đã chạy |
| **Gate 3 — Automated test** | Unit, slice, integration, concurrency `D1`, idempotency `D2`, recovery `D3`, security/audit `D4`, load `D5` theo phạm vi release | Dừng release nếu Must test fail |
| **Gate 4 — Security configuration** | `DVT-001`, `DVT-008`; không có secret trong Git/image; DB runtime least-privilege | Dừng release nếu còn Critical/High chưa xử lý |
| **Gate 5 — Smoke test sau deploy** | `DVT-003`: health `UP`, đăng nhập, transfer mẫu có ledger + audit | Rollback artifact ứng dụng |

Nguồn: `08` §7.2, `09` §7, `06` §11.4.

### 4.2. Evidence package tối thiểu

- Source revision/commit và image digest.
- Phiên bản cấu hình đã che secret.
- Migration version và kết quả Flyway.
- Kết quả OpenAPI validation.
- Test report `D1–D5` và known limitations.
- Kết quả health/smoke test.
- Backup timestamp, checksum và trạng thái restore verification.
- Quyết định/kết quả rollback nếu có.

Nguồn: `08` §7.4. Evidence phải ghi rõ môi trường, thời điểm, expected/actual và người thực hiện.

---

## 5. Backup và phục hồi

### 5.1. Backup trước demo/migration

```bash
# Tạo backup logic ngoài runtime volume
BACKUP_FILE="backups/digital_banking_$(date -u +%Y%m%dT%H%M%SZ).dump"
pg_dump -h "$DB_HOST" -p "$DB_PORT" -U "$DB_MIGRATION_USER" -d "$DB_NAME" -n banking -Fc -f "$BACKUP_FILE"

# Linux
sha256sum "$BACKUP_FILE" > "${BACKUP_FILE}.sha256"
# macOS (nếu không có sha256sum)
# shasum -a 256 "$BACKUP_FILE" > "${BACKUP_FILE}.sha256"
```

- Bắt buộc backup trước mỗi demo và trước migration có thay đổi schema (`08` §8.3).
- Backup phải nằm ngoài volume runtime, được mã hóa, có checksum và được kiểm tra restore (`08` §8.3–§8.5).
- Không coi backup là evidence nếu chưa restore thử thành công.
- Nếu có backup/restore role riêng, dùng role đó thay `DB_MIGRATION_USER`; không dùng `banking_app` cho backup toàn database.

### 5.2. Quy trình restore

1. Dừng nhận lưu lượng mới (maintenance mode tại Nginx) và giữ nguyên evidence/log.
2. Restore vào database đích đã được phê duyệt; ưu tiên database mới/trống, không ghi đè DB đang chạy khi chưa dừng lưu lượng:
   ```bash
   # RESTORE_DB_NAME là database đích mới/trống đã được phê duyệt
   pg_restore -h "$DB_HOST" -p "$DB_PORT" -U "$DB_MIGRATION_USER" -d "$RESTORE_DB_NAME" -n banking "$BACKUP_FILE"
   ```
3. Kiểm tra trạng thái Flyway (Flyway CLI hoặc Gradle task nếu plugin được cấu hình): không có migration lỗi/checksum mismatch.
4. Chạy kiểm tra bất biến:
   - Không có `available_balance < 0` hoặc `ledger_balance < 0`.
   - `available_balance <= ledger_balance`; MVP không hold nên kỳ vọng hai giá trị bằng nhau và khớp tổng ledger.
   - Tổng `DEBIT == CREDIT` trên mọi transfer `COMPLETED`.
5. Chạy `AuditVerificationJob` để xác nhận chuỗi `prev_hash -> record_hash` liên tục; nếu fail, dừng purge/archive và chạy `R-AUD-02`.
6. Chỉ mở lại lưu lượng sau khi health/smoke test và invariant pass.

Nguồn: `08` §8.3–§8.5, `design/02` §10, `design/01` §7.6.

### 5.3. RTO/RPO và giới hạn

- RTO ≤ 30 phút, RPO ≤ 5 phút là **đề xuất** (NFR-007); chưa phải cam kết cho đến khi đo lường restore/data-loss (`08` §8.4).
- Lịch backup định kỳ/WAL-PITR chưa chốt; không được tuyên bố RPO nếu chưa có evidence (`08` §8.3).
- Backup/restore phải bao gồm `audit_log`, `audit_chain_checkpoint`, `legal_hold`, `retention_policy` (`design/01` §4).

---

## 6. Rollback

- **Ứng dụng:** chuyển về image/bản build bất biến đã được nghiệm thu liền trước; không deploy lại artifact mutable (`08` §7.3).
- **Database:** không đảo ngược migration đã áp dụng bằng cách sửa/xóa migration cũ. Nếu lỗi tương thích schema, dừng lưu lượng, giữ evidence, restore từ backup đã phê duyệt hoặc chạy **forward compensating migration** đã review (`08` §7.3).
- **Sau rollback:** chạy health, kiểm tra schema/API contract và invariant tài chính; chỉ retry client với cùng idempotency key khi kết quả ban đầu chưa rõ (`08` §7.3).
- **Tuyệt đối không** dùng SQL thủ công để đặt `transfer.status='COMPLETED'` khi chưa có đủ cặp `ledger_entry` và `audit_log` hợp lệ (`08` §7.3).

---

## 7. Incident runbooks

### 7.1. Nguyên tắc chung khi xử lý sự cố

1. Bảo toàn evidence: correlation ID, reference, timestamp, log, audit, trạng thái DB.
2. Không sửa/xóa `ledger_entry` hoặc `audit_log`; không che giấu mismatch.
3. Fail-closed khi không chắc kết quả: trả `503`, không báo thành công giả.
4. Chỉ retry/reconcile bằng procedure idempotent, cùng idempotency key.
5. Escalate khi ledger thiếu/không cân, audit chain lỗi hoặc không xác định được trạng thái.

### 7.2. Runbook tóm tắt

| Mã | Tình huống | Hành động đầu tiên | Nguồn |
|---|---|---|---|
| `R-CON-01` | Transfer kẹt `PENDING` quá 5 phút | Tra cứu transfer/idempotency/ledger/audit theo reference/correlation ID; nếu thiếu ledger, giữ PENDING và để reconciliation xử lý; không báo thành công giả | `08` §10, `design/02` §13.5 |
| `R-CON-02` | Idempotency kẹt `RECEIVED`/`IN_PROGRESS` | Xác định giao dịch đã commit/rollback; không tạo transfer mới với key khác; resume/reconcile theo key gốc | `08` §10, `design/02` §10 |
| `R-CON-03` | Deadlock `40P01`/lock timeout `55P03` | Kiểm tra thứ tự khóa `account_id ASC`, limit customer → account; chỉ retry transient có giới hạn | `08` §10, `design/02` §7 |
| `R-CON-04` | Lệch số dư/ledger | Đóng băng tài khoản bị ảnh hưởng, bảo toàn evidence; không sửa ledger/audit; escalate Critical | `08` §10, `design/02` §13.5 |
| `R-CON-05` | Audit gap | Giữ transfer PENDING/FAILED nếu thiếu audit; retry/reconcile; dừng purge nếu ảnh hưởng chain | `08` §10, `design/01` §12 |
| `R-CON-06` | Notification backlog | Kiểm tra outbox/worker/lease/adapter; retry backoff; **không** rollback transfer đã COMPLETED | `08` §10, `design/02` §13.5 |
| `R-CON-07` | Connection pool cạn kiệt | Fail-closed `503`; tìm transaction treo/lock wait; không tăng pool mù khi chưa rõ nguyên nhân | `08` §10, `design/02` §13.5 |
| `R-CON-08` | DB outage/mất kết nối | Health `DOWN`, ngừng route; xác định request nào đã commit; restore/reconnect theo DR; kiểm tra invariant trước khi mở lại | `08` §10 |
| `R-AUD-01` | Audit write failure | Không bypass audit; transfer giữ PENDING/FAILED; retry/reconcile; xác nhận không còn gap | `08` §10 |
| `R-AUD-02` | Audit chain invalid | Dừng retention/purge/archive; giữ backup/evidence; xác định record lệch đầu tiên; không sửa audit | `08` §10, `design/01` §12 |
| `R-AUD-03` | Audit gap (theo `R-AUD-01`) | Xác minh liên kết transfer/ledger/audit/correlation; escalate nếu không tự reconcile được | `08` §10 |
| `R-AUD-04` | Legal hold/purge conflict | Xác nhận hold scope; dừng purge; release chỉ sau approval + strong auth + audit | `08` §10, `design/01` §10 |
| `R-AUD-05` | Export anomaly | Kiểm tra quyền/filter/row count; rate-limit/block export đáng ngờ; không phân phối evidence qua kênh chưa duyệt | `08` §10, `design/04` §9 |

### 7.3. Liên kết runbook gốc

- Toàn bộ procedure chi tiết: [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] §10.
- Recovery/reconciliation: [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §10, §13.5.
- Audit/retention/legal hold: [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §10, §12.
- Security incident/severity: [[digitalbanking-docs/docs-common/design/04. security.md]] §9.

---

## 8. Kiểm chứng và nghiệm thu

### 8.1. Danh mục `DVT-001`–`DVT-010`

| ID | Kịch bản | Kết quả mong đợi |
|---|---|---|
| `DVT-001` | Environment configuration review | Biến bắt buộc, ranh giới mạng và secret rule được ghi nhận |
| `DVT-002` | Topology review | Mỗi component có trách nhiệm và failure behavior |
| `DVT-003` | Health check | Phân biệt `UP`/`DOWN`; không lộ secret |
| `DVT-004` | Migration review | Forward-only; có compatibility/evidence |
| `DVT-005` | Backup/restore exercise | Restore trong RTO hoặc ghi variance |
| `DVT-006` | Failure/recovery exercise | Không false success, double debit, ledger lệch |
| `DVT-007` | Observability review | Log/metric/alert/runbook được liên kết |
| `DVT-008` | Security configuration review | Secret/PII control được ghi nhận |
| `DVT-009` | Release/rollback review | Artifact, migration, rollback, evidence gate rõ |
| `DVT-010` | Cost/scale review | Assumption, trigger, measurement được ghi nhận |

Nguồn: `08` §11. Trạng thái evidence phải theo [[digitalbanking-docs/docs-common/09. Test plan.md]]; không tự đánh `PASS` khi chưa có evidence.

### 8.2. Evidence `D1`–`D5`

| Demo | Nội dung | Evidence tối thiểu |
|---|---|---|
| `D1` | Concurrency | Báo cáo concurrent transfer, balance cuối, ledger count/sum, không âm |
| `D2` | Idempotency | Request cùng key, response, idempotency record, một side effect |
| `D3` | Failure/recovery | Injection log, restart/reconcile, transfer/ledger/audit |
| `D4` | Security/audit/fraud | Access denial, masking, audit query, tamper test, security checklist |
| `D5` | Load | Report p95/p99/error, invariant sau load |

Nguồn: `09` §10.2. Trạng thái baseline hiện tại là **Planned/Not verified** cho đến khi chạy trên môi trường `demo`.

### 8.3. Điều kiện nghiệm thu

- `D1–D5` reproducible và đạt threshold của `09`; blocker phải có quyết định exception rõ ràng.
- Không còn Critical/High chưa xử lý trong phạm vi demo.
- Backup/restore, health, audit chain và invariant tài chính đã được kiểm tra.
- Evidence package đầy đủ và ghi rõ môi trường/source revision/cấu hình.

Nguồn: `09` §8, §10, `08` §8.5, §11.

---

## 9. Tài liệu tham chiếu

- [[digitalbanking-docs/docs-backend/deployment/configuration.md]]
- [[digitalbanking-docs/docs-common/05. Technical stack.md]]
- [[digitalbanking-docs/docs-common/06. System architecture.md]]
- [[digitalbanking-docs/docs-common/07. API contracts.md]]
- [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]]
- [[digitalbanking-docs/docs-common/09. Test plan.md]]
- [[digitalbanking-docs/docs-common/10 Master timelines.md]]
- [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]]
- [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]]
- [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]
- [[digitalbanking-docs/docs-common/design/03. fraud-detection-rules.md]]
- [[digitalbanking-docs/docs-common/design/04. security.md]]
- [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]
