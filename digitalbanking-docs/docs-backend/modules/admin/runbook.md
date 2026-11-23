# Admin Operations Runbook — Phê duyệt Maker/Checker

> **Mã tài liệu:** BE-ADM-OPS-01 · **Cập nhật:** 18/11/2026
> **Phạm vi:** vận hành luồng phê duyệt `ROLE_CHANGE`/`LIMIT_CHANGE` của module Admin, provider Identity/Limit/Audit.
> **Nguyên tắc bất biến:** recovery **chỉ** là một quyết định checker hợp lệ chạy lại qua API chuẩn (step-up +
> `Idempotency-Key` + Maker–Checker). Không có endpoint/job nào được tự đổi trạng thái approval, tự áp provider hoặc
> bypass audit. Không retry với key khác khi chưa xác định giao dịch trước đã commit hay chưa.

## 1. Tín hiệu vận hành

### 1.1. Metric (Micrometer → Prometheus)

| Metric (dotted → exported) | Kiểu | Label bounded | Ý nghĩa / dùng khi |
|---|---|---|---|
| `admin.approval.proposals` → `admin_approval_proposals_total` | counter | `request_type`, `outcome` | Maker tạo proposal: `SUCCESS`/`REPLAYED`/`REJECTED`/`FAILURE`/`CONFLICT` |
| `admin.approval.decisions` → `admin_approval_decisions_total` | counter | `request_type`, `decision`, `outcome` | Quyết định checker theo kết quả cuối |
| `admin.approval.processing.duration` | timer | `request_type`, `decision`, `outcome` | p95/p99 xử lý quyết định (gồm provider + audit) |
| `admin.provider.executions` | counter | `provider` (IDENTITY/LIMIT/AUDIT), `operation`, `outcome` | Tỉ lệ lỗi provider |
| `admin.provider.latency` | timer | như trên | Độ trễ provider, phát hiện lock/timeout |
| `admin.approval.version.conflicts` | counter | `provider` | Stale `expectedVersion` — dấu hiệu tranh chấp cấu hình |
| `admin.approval.retry.attempts` | counter | `request_type`, `outcome` (PROVIDER_FAILURE/…) | Bằng chứng retry thất bại bền vững |
| `admin.approval.retry.exhausted` | counter | `request_type` | Approval chạm ngưỡng `max-failed-attempts` |
| `admin.approval.stuck` | gauge | `request_type` | Số approval `PENDING` quá `stuck-after` (job quét) |
| `admin.approval.recovery` | counter | `request_type`, `outcome` | Lần quét recovery (`SUCCESS`/`ERROR`) và recovery thủ công thành công sau thất bại |
| `admin.audit.events` / `admin.audit.failures` | counter | `action`, `class`, `outcome` | Ghi audit bền vững thành công/thất bại |
| `admin.idempotency.replay` / `conflict` / `in_progress` | counter | — | Hành vi idempotency (không chứa ID) |
| `audit.written` / `audit.write.failures` | counter | `class`, `action` | Sức khỏe writer Audit (dùng chung toàn hệ thống) |

**Không** đưa `userId`, `approvalId`, `correlationId`, `requestId`, nội dung tự do vào label. Mọi metric ghi lỗi đều
fail-open với telemetry, không ảnh hưởng quyết định.

### 1.2. Dashboard tối thiểu

1. **Throughput/latency**: `sum(rate(admin_approval_decisions_total[5m])) by (request_type,outcome)` và p95
   `admin_approval_processing_duration_seconds`.
2. **Provider health**: `sum(rate(admin_provider_executions_total{outcome="FAILURE"}[5m])) by (provider,operation)`.
3. **An toàn nhất quán**: `increase(admin_approval_version_conflicts_total[15m])`,
   `admin_approval_retry_exhausted_total`, `admin_approval_stuck`.
4. **Bằng chứng**: `increase(admin_audit_failures_total[15m])`, `increase(audit_write_failures_total[15m])`.
5. **Recovery**: `increase(admin_approval_recovery_total{outcome="ERROR"}[15m])`.

### 1.3. Log có cấu trúc

| Event | Logger | Key allowlist | Dùng để |
|---|---|---|---|
| `HTTP_REQUEST_COMPLETED` | `common.context.HttpRequestLog` | route/method/status/latency_ms/error_code | Tìm theo correlation, 4xx/5xx |
| `ADMIN_APPROVAL_WORKFLOW` | `admin.WORKFLOW` | stage/request_type/decision/provider/operation/outcome/error_code/approval_id/attempts/age_bucket | Theo dõi transition, retry, quét stuck |
| `SECURITY_AUDIT` | `admin.AUDIT` | action/audit_class/outcome/actor_user_id/entity_type/entity_id/request_type/reason_code | Mirror của audit bền vững sau commit |
| `SECURITY_AUDIT_WRITE_FAILED` | `admin.AUDIT` | action/audit_class/outcome=WRITE_FAILED | C3 lỗi hoặc C2 fail-closed |
| `ADMIN_ATTEMPT_WRITE_FAILED` | recorder | request_type/outcome | Không ghi được bằng chứng retry |
| `ADMIN_RECOVERY_SWEEP_FAILED` | `admin.RECOVERY` | — | Job quét lỗi, alerting bị mù |

**Truy vết:** dùng `correlation_id` (client hoặc server sinh, `X-Correlation-ID`) và `request_id` (server sinh,
`X-Request-ID`) trong cùng một request; ghép với `approval_id` (digits) trong log. Không bao giờ log payload, note,
OTP, token, hash challenge.

```bash
# Tìm toàn bộ dấu vết một request
journalctl -u digitalbanking-backend | jq -c 'select(.correlation_id=="<corr>")'
# Các lần quyết định lỗi của một approval
journalctl -u digitalbanking-backend | jq -c 'select(.event=="ADMIN_APPROVAL_WORKFLOW" and .approval_id=="<id>")'
```

## 2. Phân loại cảnh báo và phản ứng

| Alert (`ops/prometheus/admin-alerts.yml`) | Severity | Phản ứng đầu tiên |
|---|---|---|
| `AdminProviderIntegrationFailure` | critical | Xác định provider/operation; kiểm tra DB lock/timeout và contract; **không** đổi key |
| `AdminApprovalRepeatedFailures` | warning | Xem `admin_approval_attempt`, phân loại outcome, tư vấn retry đúng key |
| `AdminApprovalVersionConflictSpike` | warning | Đọc lại version hiện tại; tạo proposal mới; không bypass conflict |
| `AdminApprovalRetryExhausted` | warning | Manual review theo §5; approval vẫn PENDING |
| `AdminApprovalStuck` | warning | Kiểm tra tuổi/stuck theo §6; quyết định lại bởi checker hợp lệ |
| `AdminAuditPersistenceFailure` | critical | Dừng thay đổi nghiệp vụ nếu C2 lỗi; xử lý theo §8 |
| `AdminApprovalRecoverySweepFailure` | critical | Job quét mù → kiểm tra DB/health; xử lý theo §9 |

Severity theo `docs-common/design/04. security.md` §9 và thông lệ deployment hiện có: lỗi audit/khôi phục hệ thống
= critical; retry/kẹt/version = warning (cần người vận hành).

## 3. Tra cứu dữ liệu (read-only)

```sql
-- Approval đang chờ, tuổi và số lần thất bại trong 1 giờ
SELECT a.approval_id, a.request_type, a.status, a.created_at,
       count(t.attempt_id) FILTER (WHERE t.attempted_at >= now() - interval '1 hour') AS failed_attempts
FROM banking.approval_request a
LEFT JOIN banking.admin_approval_attempt t ON t.approval_id = a.approval_id
WHERE a.status = 'PENDING'
GROUP BY a.approval_id ORDER BY a.created_at;

-- Bằng chứng retry gần nhất (mã bounded, không PII)
SELECT approval_id, request_type, outcome, error_code, attempted_at
FROM banking.admin_approval_attempt ORDER BY attempted_at DESC LIMIT 50;

-- Limit pending/active của một approval
SELECT limit_id, status, version, per_transaction_limit, daily_limit, approved_by, approved_at
FROM banking.limit_config WHERE approval_id = :approvalId;

-- Chuỗi audit của một approval (hash phải liên tục)
SELECT audit_id, action, outcome, prev_hash, record_hash
FROM banking.audit_log WHERE entity_type='APPROVAL_REQUEST' AND entity_id = :approvalId
ORDER BY audit_id;
```

`audit_log` là append-only (trigger chặn UPDATE/DELETE/TRUNCATE). Không dùng `DELETE` để “dọn” bằng chứng.

## 4. Provider integration failure (Identity / Limit)

**Detection:** `AdminProviderIntegrationFailure`, `admin.provider.executions{outcome="FAILURE"}`, log
`ADMIN_APPROVAL_WORKFLOW` outcome `FAILURE`, attempt row `PROVIDER_FAILURE`/`TECHNICAL_FAILURE`.

**Investigation**

1. Xác định `provider` + `operation` (`APPLY_ROLE_CHANGE`, `APPLY_LIMIT_CHANGE`).
2. Đọc approval: vẫn `PENDING`? (C2 fail-closed ⇒ mọi thứ rollback).
3. Kiểm tra DB: lock/timeout (`55P03`), connection pool, migration V9/V10 hiện hành.
4. Identity: `USER_NOT_FOUND`/`ROLE_NOT_FOUND`/`ROLE_CONFLICT`; Limit: `ACCOUNT_NOT_FOUND`, `LIMIT_OUT_OF_POLICY`,
   `INVALID_STATE_TRANSITION`.
5. **Không** sửa bảng provider bằng tay để “cho khớp”.

**Authorized action:** checker hợp lệ quyết định lại qua `POST /api/v1/admin/{roles|limits}/{id}/approve` với **cùng
`Idempotency-Key`** và proof step-up mới cho cùng payload/decision. Maker phải tạo proposal mới nếu payload đổi.

**Expected result:** 200 `APPLIED`; provider áp một lần; audit C2 mới; attempt cũ được giữ làm lịch sử.

**Verification:** approval `APPLIED`, `limit_config` ACTIVE đúng một bản/version tăng 1 (hoặc role/version +1), audit
`*_CHANGE_APPROVED` đúng 1 cho approval, replay cùng key trả `Idempotent-Replayed: true` và không tăng bản ghi.

## 5. Repeated failures / Retry exhaustion

**Detection:** `AdminApprovalRepeatedFailures` (>5 attempts/15m), `AdminApprovalRetryExhausted`
(failed attempts ≥ `app.admin.ops.max-failed-attempts` trong `attempt-window`), attempt rows liên tiếp.

**Investigation**

1. Phân loại outcome: `VERSION_CONFLICT`, `STEP_UP_FAILURE`, `PROVIDER_FAILURE`, `AUDIT_FAILURE`, `TECHNICAL_FAILURE`.
2. `VERSION_CONFLICT` lặp → xem §7. `STEP_UP_FAILURE` lặp → challenge hết hạn/bị dùng lại; cấp challenge mới.
3. `AUDIT_FAILURE` → §8. `TECHNICAL_FAILURE` → §4.
4. Kiểm tra client có đang đổi payload/key giữa các lần retry (sai contract idempotency).

**Authorized action:** dừng retry tự động; điều tra nguyên nhân gốc; sau khi khắc phục, checker hợp lệ quyết định
lại với cùng key (nếu payload không đổi) hoặc Maker tạo proposal mới.

**Expected result:** không có lần áp dụng thứ hai; approval terminal đúng một trạng thái; attempt history đầy đủ.

**Verification:** đếm `admin_approval_retry_attempts_total` không tăng thêm sau khi đã xử lý; approval terminal;
`admin_approval_attempt` giữ lịch sử; audit không nhân đôi.

## 6. Stuck approval (kẹt PENDING)

**Detection:** `AdminApprovalStuck` (gauge > 0 quá 30m), `admin.approval.stuck`, log `RECOVERY_SCAN`/`STUCK_APPROVAL`.

**Investigation**

1. Tuổi approval > `app.admin.ops.stuck-after` (mặc định 30m) và vẫn `PENDING`.
2. Có attempt gần đây? Nếu có, đọc outcome (§5). Nếu không, khách/checker chưa hoàn tất step-up.
3. Challenge còn hiệu lực? Step-up đã verify chưa? Maker/checker có quyền loại tương ứng?
4. Không có dấu hiệu provider đã áp dụng (đây là điều kiện bắt buộc trước khi quyết định lại).

**Authorized action:** checker có `CHECKER` + quyền loại (`ROLE_MANAGE` hoặc `LIMIT_MAKER/LIMIT_MANAGE`) thực hiện
quyết định lại qua API chuẩn với step-up mới + `Idempotency-Key`. Maker có thể `REJECT` để đóng an toàn nếu không
còn nhu cầu.

**Expected result:** `APPLIED` hoặc `REJECTED`; limit pending không còn `PENDING_APPROVAL` mồ côi.

**Verification:** `SELECT status FROM approval_request` terminal; `limit_config` không còn pending cho approval;
`admin.approval.stuck` giảm sau lần quét kế tiếp; audit C2 đúng 1.

## 7. Version conflict

**Detection:** `AdminApprovalVersionConflictSpike`, `admin.approval.version.conflicts`, `409 ROLE_CONFLICT`.

**Investigation:** đọc version hiện tại của role (`app_user.role_assignment_version`) hoặc Limit
(`limit_config.version` ACTIVE). Xác nhận approval payload mang `expectedVersion` cũ (đã bị thay đổi giữa step-up và
apply).

**Authorized action:** Maker tạo proposal mới (version mới được chụp lại); checker duyệt. **Không** sửa payload
JSONB, không tắt kiểm version.

**Expected result:** proposal mới `PENDING`; approval cũ vẫn `PENDING` (có thể reject để đóng).

**Verification:** proposal/approve mới trả 200; `limit_config`/role version tăng đúng 1; audit có
`*_CHANGE_REQUESTED` + `*_CHANGE_APPROVED` cho approval mới; không có áp dụng kép.

## 8. Audit persistence / hash-chain failure

**Detection:** `AdminAuditPersistenceFailure` (`admin.audit.failures`), `audit.write.failures`,
`SECURITY_AUDIT_WRITE_FAILED`, C2 trả 5xx cho quyết định.

**Investigation**

1. Xác định `class`: C2 ⇒ approval/provider đã rollback (không có trạng thái sai, nhưng không được báo thành công).
2. Kiểm tra `banking.audit_log`: insert được không? Trigger append-only có bị UPDATE/DELETE vô tình không? Khóa
   advisory chain có bị giữ lâu bởi transaction treo?
3. Kiểm tra chuỗi: `prev_hash` của record N = `record_hash` của record N−1 (theo `audit_id`); `record_hash`
   khớp SHA-256(JCS || '|' || prev_hash) khi chạy verifier ngoài (không sửa dữ liệu).
4. Không xóa/sửa record để “sửa” chain. Nếu phát hiện đứt gãy, escalate Security/Audit ngay.

**Authorized action**

- C2: khắc phục nguyên nhân (DB/khóa/pool), sau đó checker quyết định lại cùng key (transaction cũ đã rollback).
- C3: 403/409 đã trả đúng; ghi nhận gap bằng chứng; Security quyết định điều tra bổ sung. Không đổi response.

**Expected result:** audit row mới bền vững, chain liên tục; approval/provider khớp trạng thái.

**Verification:** `SELECT count(*) FROM audit_log WHERE entity_id=... AND action=...` = 1 (không nhân đôi);
`prev_hash` liên kết; replay không tạo thêm; response/log không chứa secret.

## 9. Recovery sweep failure

**Detection:** `AdminApprovalRecoverySweepFailure` (`admin.approval.recovery{outcome="ERROR"}`),
`ADMIN_RECOVERY_SWEEP_FAILED`.

**Investigation:** kiểm tra DB connectivity, quyền SELECT trên `approval_request`/`admin_approval_attempt`, migration
V11 tồn tại, lỗi scheduler/thread pool. Job không giữ trạng thái nên lần chạy sau tự thử lại.

**Authorized action:** khôi phục DB/health; không chạy SQL UPDATE thay job. Sau khi job xanh, kiểm tra lại danh sách
stuck/exhausted bằng §3.

**Expected result:** gauge `admin.approval.stuck` được cập nhật trở lại; không có thay đổi nghiệp vụ nào do job.

**Verification:** `admin_approval_recovery_total{outcome="SUCCESS"}` tăng; log `RECOVERY_SCAN` xuất hiện; trạng thái
approval trước/sau quét không đổi (job read-only).

## 10. Manual review an toàn

Được phép:

- Đọc approval/limit/audit/attempt (read-only).
- Yêu cầu checker hợp lệ quyết định lại qua API chuẩn (step-up mới, cùng key nếu payload không đổi).
- Maker `REJECT` proposal không còn nhu cầu (không áp provider; limit pending → REJECTED).

Không được phép:

- `UPDATE`/`DELETE` `approval_request`, `limit_config`, `audit_log`, `admin_approval_attempt`.
- Tự áp role/limit bằng SQL hoặc gọi thẳng provider ngoài transaction approval.
- Dùng token/session của người khác; bỏ qua Maker–Checker/step-up.
- Tạo key mới cho cùng thao tác đã có bằng chứng commit (nguy cơ áp dụng kép nếu chưa xác minh).

Phân quyền: chỉ checker có `CHECKER` + quyền loại mới được quyết định; Maker không thể tự duyệt; mọi hành động đều
đi qua security chain và để lại audit C2/C3.

## 11. Escalation

| Mức | Điều kiện | Liên hệ |
|---|---|---|
| L1 Ops | Warning retry/stuck/version, không có lỗi audit | Admin on-call |
| L2 Security/Audit | `AdminAuditPersistenceFailure`, hash-chain bất thường, nghi C3 gap | Security owner + Audit owner |
| L3 Provider owner | Lỗi lặp Identity/Limit, conflict không giải thích được | Identity/Limit owner |
| L4 Incident | Áp dụng kép/không rõ commit, mất bằng chứng, ảnh hưởng nhiều approval | Incident commander + DB owner |

Escalation phải kèm: correlation_id, approval_id, khoảng thời gian, metric/alert name, outcome attempt, trạng thái DB
đã kiểm (không kèm payload/PII/token).

## 12. Kiểm chứng sau recovery (checklist)

- [ ] Approval terminal đúng (`APPLIED`/`REJECTED`), không còn `PENDING` ngoài chủ ý.
- [ ] Provider áp đúng một lần: role version +1/assign đúng; `limit_config` chỉ một ACTIVE, version tăng 1.
- [ ] `limit_config` không còn `PENDING_APPROVAL` mồ côi cho approval đã terminal.
- [ ] Audit: đúng một `*_REQUESTED` + một `*_APPROVED`/`*_REJECTED`; `prev_hash` liên tục; không UPDATE/DELETE.
- [ ] Replay cùng key → `Idempotent-Replayed: true`, không tăng bản ghi/bản áp dụng.
- [ ] Key khác trên approval terminal → `409 APPROVAL_ALREADY_DECIDED`.
- [ ] Metric/alert liên quan trở về ngưỡng bình thường; `admin.approval.stuck` giảm sau lần quét.
- [ ] Log không chứa secret/PII; response không lộ `expectedVersion`.

## 13. Phòng chống áp dụng kép

1. Chỉ một đường ghi: `ApprovalDecisionService` + provider `MANDATORY` + C2 audit cuối transaction.
2. `expectedVersion` khóa lạc hậu; lock hàng pending/ACTIVE trước khi transition.
3. `UNIQUE (limit_config.approval_id)` + transition một lần; idempotency insert-first unique theo actor/operation/key.
4. Replay trả body đã lưu; key khác trên terminal bị chặn; C3 cho conflict.
5. Recovery không tự chạy; chỉ checker hợp lệ, cùng key khi payload không đổi.
