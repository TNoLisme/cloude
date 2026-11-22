# Cloud Observability Decision

> **Mã:** BE-CLOUD-10  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed
> **Applies to:** Option B — AWS Managed Core
> **Source decision:** `digitalbanking-docs/docs-common/12. Cloud Deployment & Runbook.md`

## 1. Baseline signals

| Signal | Source | Access |
|---|---|---|
| Application health | `/actuator/health` | Public minimal probe |
| Business health | `/api/v1/health` | Authenticated/internal |
| Application metrics | Actuator/Micrometer | Private |
| Request logs | Spring structured JSON | VM/archive |
| Proxy logs | Nginx access/error | VM/archive |
| DB metrics | Managed DB/provider | Private console |
| VM metrics | Provider monitoring | Provider console |
| External uptime | Uptime check | External dashboard |

## 2. Candidates

| Option | Selection |
|---|---|
| AWS CloudWatch | Required for EC2/RDS logs, metrics and alarms |
| Spring Actuator/Micrometer | Required for application/DB pool health and metrics |
| k6 | Required for D5 latency/error output |
| Prometheus/Grafana | Deferred; add only for dashboard/custom metric requirement |
| Grafana Cloud | Optional alternative; adds external account/cost |
| UptimeRobot/Better Stack/Healthchecks.io | Select one external HTTPS check after interval, alerting, retention and cost comparison |
| Sentry | Optional; redact PII |

CloudWatch is selected over a self-managed Prometheus baseline because AWS already owns EC2/RDS signals and D5 can combine CloudWatch metrics, Actuator metrics and k6 JSON output.

- CloudWatch Logs: Nginx, Spring Boot and deployment logs.
- CloudWatch metrics: EC2 CPU/network/disk and RDS health/connections/storage.
- Actuator/Micrometer: application and DB pool metrics.
- k6 JSON output: request count, p95, p99, error rate and thresholds.

Ghi rõ source, timestamp, region của load generator và giới hạn đo. Không gọi D5 complete nếu thiếu resource metrics hoặc financial invariant.

## 3. Alert baseline

- Public health fails.
- Backend health `DOWN`.
- Database unavailable.
- Disk usage high.
- Memory pressure.
- Certificate expiry near threshold.
- Backup stale/fails.
- Error rate exceeds release threshold.
- Unexpected restart.

- Connection pool alert baseline: `db_pool_active > 80%` trong 5 phút, theo baseline đã chốt tại `docs-common/08. Deployment infrastructure.md` §9.4.
- Nếu muốn dùng ngưỡng khác, ghi deviation và owner approval; không tự đổi baseline sang 90%.

Thresholds khác lấy từ `configuration.md` hoặc ghi `Operational proposal` tới khi đo được.

## 4. Safe log fields

`timestamp`, `level`, `service`, `environment`, `version`, `correlation_id`, `request_id`, `action`, `outcome`, safe error code.

Never log password, OTP, session token, CSRF token, encryption key, full account number, raw PII hoặc DB password.

## 5. Verification

- [ ] Health response tối giản.
- [ ] Correlation ID xuyên HTTP/log/audit nếu contract yêu cầu.
- [ ] Metrics endpoint private.
- [ ] Nginx/app logs rotate.
- [ ] Alert test có notification và không lộ secret.
- [ ] Backup failure alert test ghi evidence.
- [ ] D5 evidence có latency, error, resource metrics.
