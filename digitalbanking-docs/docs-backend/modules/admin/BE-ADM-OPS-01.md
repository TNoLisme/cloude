# BE-ADM-OPS-01 — Observability, retry/recovery và runbook vận hành Admin

> **Trạng thái:** **DONE (18/11/2026)** — phần runtime + cấu hình cảnh báo + runbook trong repository.
> **Nguồn chuẩn:** `docs-common/design/01. audit-traceability.md`, `docs-common/design/02. consistency-concurrency.md`,
> `docs-common/design/04. security.md` §9, `docs-backend/00. architecture & conventions.md` (BE-OBS), `12. admin design §10`.
> **Nguyên tắc:** observability là telemetry best-effort — không được đổi kết quả nghiệp vụ; recovery chỉ là một quyết
> định checker hợp lệ chạy lại qua API chuẩn, không có auto-apply.

## 1. Metric

`AdminApprovalMetrics` (Micrometer; Prometheus export = dấu chấm → `_`, counter có `_total`):

| Meter | Label bounded | Mục đích |
|---|---|---|
| `admin.approval.proposals` | `request_type`, `outcome` | Proposal theo loại/kết quả (`SUCCESS/REPLAYED/REJECTED/FAILURE/CONFLICT`) |
| `admin.approval.decisions` | `request_type`, `decision`, `outcome` | Quyết định checker |
| `admin.approval.processing.duration` | `request_type`, `decision`, `outcome` | Thời gian xử lý (timer) |
| `admin.provider.executions` | `provider`, `operation`, `outcome` | Tỉ lệ lỗi Identity/Limit/Audit |
| `admin.provider.latency` | `provider`, `operation`, `outcome` | Độ trễ provider |
| `admin.approval.version.conflicts` | `provider` | `expectedVersion` stale |
| `admin.approval.retry.attempts` | `request_type`, `outcome` | Bằng chứng retry thất bại |
| `admin.approval.retry.exhausted` | `request_type` | Chạm ngưỡng retry |
| `admin.approval.stuck` | `request_type` | Gauge approval PENDING quá hạn (job quét) |
| `admin.approval.recovery` | `request_type`, `outcome` | Lần quét recovery + recovery thủ công thành công |
| `admin.approval.attempt.write.failures` | `request_type` | Không ghi được bằng chứng attempt |
| `admin.audit.events` / `admin.audit.failures` | `action`, `class`, `outcome` | Ghi audit bền vững (adapter Admin) |
| `audit.written` / `audit.write.failures` | `class`, `action` | Provider Audit (dùng chung) |

**Bounded label:** `AdminApprovalMetrics` từ chối mọi giá trị ngoài catalog (không tạo series mới); không có
`userId`, `approvalId`, `correlationId`, `requestId`, reason text hay exception message.

## 2. Structured logging / trace

- `RequestContextHolder` bind `correlationId` + `requestId` vào MDC; async/scheduled job phải capture context tường minh.
- Logger `com.simulatedbank.banking.admin.WORKFLOW`, event `ADMIN_APPROVAL_WORKFLOW`: key allowlist
  `stage`, `request_type`, `decision`, `provider`, `operation`, `outcome`, `error_code`, `approval_id`, `attempts`,
  `age_bucket`, `correlation_id`, `request_id`.
- `SafeJsonLogEncoder` (stdout fail-closed) chỉ serialize allowlist trên; message/argument/MDC/throwable thô không bao
  giờ được ghi; giá trị secret/PII bị loại.
- Sự kiện: `PROPOSAL`, `DECISION`, `RECOVERY_SCAN`, `STUCK_APPROVAL`, `RETRY_EXHAUSTED`, `RECOVERY_APPLIED`,
  `RECOVERY_SWEEP`.
- Audit mirror `SECURITY_AUDIT` chỉ phát **sau commit** (giữ BE-ADM-TST-05); C3 lỗi phát
  `SECURITY_AUDIT_WRITE_FAILED`.

## 3. Retry và recovery

| Khái niệm | Triển khai |
|---|---|
| Retry | Do client/checker thực hiện lại qua API với **cùng `Idempotency-Key`** (payload không đổi) sau step-up mới; không retry provider trong transaction |
| Retryable | `503/504`, lock timeout `55P03`, DB outage, `AUDIT_WRITE_FAILED` sau khi khắc phục, stale version **sau khi tạo proposal mới** |
| Không retry mù | `400/401/403/404/409` nghiệp vụ; terminal approval; challenge đã dùng |
| Bằng chứng | `banking.admin_approval_attempt` (V11) ghi trong `REQUIRES_NEW`, outcome bounded, giữ lịch sử; lỗi ghi attempt không che lỗi gốc |
| Exhaustion | `max-failed-attempts` (mặc định 5) trong `attempt-window` (mặc định 1h) → metric + alert + log |
| Stuck | Job `AdminApprovalRecoveryJob` quét read-only approval `PENDING` cũ hơn `stuck-after` (mặc định 30m) |
| Manual recovery | Checker hợp lệ quyết định lại qua API (Maker–Checker + step-up + idempotency); Maker có thể reject |
| An toàn restart | Không có state trong bộ nhớ; job/idempotency/provider đều dựa DB; attempt history bền |

Job không đổi trạng thái approval, không gọi provider. Mọi thay đổi trạng thái chỉ đi qua `ApprovalDecisionService`.

## 4. Alert

File version-controlled: `digitalbanking-backend/ops/prometheus/admin-alerts.yml` (7 rule). Severity: audit/recovery
= `critical`; provider failure = `critical`; retry/version/stuck = `warning`. Mỗi rule có `runbook` trỏ
`docs-backend/modules/admin/runbook.md#<anchor>`.

| Alert | Điều kiện rút gọn |
|---|---|
| `AdminProviderIntegrationFailure` | rate failure > 0.2/s trong 10m |
| `AdminApprovalRepeatedFailures` | > 5 attempt/15m |
| `AdminApprovalVersionConflictSpike` | > 2 conflict/15m |
| `AdminApprovalRetryExhausted` | > 0 exhausted/30m |
| `AdminApprovalStuck` | gauge > 0 trong 30m |
| `AdminAuditPersistenceFailure` | `admin_audit_failures_total` tăng |
| `AdminApprovalRecoverySweepFailure` | `admin_approval_recovery_total{outcome="ERROR"}` tăng |

## 5. Runbook

[`runbook.md`](runbook.md) — metric/dashboard, log/correlation, severity/response, C2/C3 triage, Identity/Limit failure,
retry exhaustion, stuck approval, audit/hash-chain failure, manual review an toàn, escalation, checklist kiểm chứng và
phòng chống áp dụng kép. Mỗi quy trình có Detection → Investigation → Authorized action → Expected result → Verification.

## 6. Source

| File | Vai trò |
|---|---|
| `admin/application/ops/AdminApprovalMetrics.java` | Toàn bộ meter bounded + `metricNames()` |
| `admin/application/ops/AdminApprovalAttemptRecorder.java` | Ghi attempt bền vững (`REQUIRES_NEW`), `failedAttempts` |
| `admin/application/ops/AdminApprovalRecoveryService.java` | Scan read-only stuck/exhausted, gauge/metric/log, recovery evidence |
| `admin/application/ops/AdminWorkflowLog.java` | Structured workflow log allowlist |
| `admin/application/ops/AdminOpsProperties.java` | `app.admin.ops.*` (enabled, stuck-after, attempt-window, max-failed-attempts, sweep-delay, sweep-initial-delay) |
| `admin/adapter/in/AdminApprovalRecoveryJob.java` | `@Scheduled` read-only sweep, lỗi → recovery ERROR |
| `common/config/SafeJsonLogEncoder.java` | Allowlist workflow event; workflow không phát throwable |
| `src/main/resources/application.yml` | Default `app.admin.ops.*` + env alias `APP_ADMIN_OPS_*` |
| `ops/prometheus/admin-alerts.yml` | Rule Prometheus |

Tích hợp vào service: `ApprovalProposalService` (proposal metric/log/stage), `ApprovalDecisionService`
(decision duration/failure, provider latency + version conflict, attempt evidence, recovery record),
`DurableAdminAuditAdapter` (audit events/failures, mirror sau commit).

## 7. Test

| Test | Nội dung |
|---|---|
| `AdminApprovalMetricsTest` (5) | Counter/timer tăng; label ngoài catalog bị bỏ (không tạo series) |
| `AdminApprovalAttemptRecorderPostgreSqlIntegrationTest` (6) | Insert/count thật, outcome bounded, lỗi ghi bị nuốt an toàn, không PII |
| `AdminApprovalRecoveryServicePostgreSqlIntegrationTest` (4) | Stuck/exhausted, gauge, **không đổi trạng thái approval**, không provider call |
| `SafeJsonLogEncoderTest` (+3) | Allowlist workflow, secret bị loại, logger khác vẫn generic |
| `AdminAlertRulesTest` (4) | Mọi metric trong `expr` thuộc catalog; rule có severity + runbook |
| `LimitApprovalHttpPostgreSqlIntegrationTest` | Attempt `VERSION_CONFLICT` được ghi khi stale version; metric/log vận hành |

Lệnh và kết quả cuối: xem `tasks/README.md` §0.16.

## 8. Giới hạn

- Alert rules chưa được deploy lên Prometheus thật (repo không có stack monitoring); file là cấu hình version-controlled
  theo quy ước deployment.
- Email/Slack routing, dashboard JSON và on-call schedule thuộc hạ tầng Ops, ngoài repository.
- Quota/rate limiter vẫn per-instance (giới hạn đã biết, xem `design/05` §12); không suy ra quota phân tán.
