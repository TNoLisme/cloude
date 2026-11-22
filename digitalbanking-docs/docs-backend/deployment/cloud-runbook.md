# Cloud Deployment Runbook

> **Mã:** BE-CLOUD-13  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed

## 1. Preconditions

- Release commit đã review.
- Artifact digest đã có.
- Provider resources đúng decision.
- DNS/TLS sẵn sàng.
- Secrets nạp qua kênh riêng.
- Backup pass checksum.
- Synthetic seed chuẩn bị.
- Không có migration đang chạy.

## 2. Start baseline

```bash
# Run on EC2 through AWS Systems Manager Session Manager.
docker compose pull
docker compose up -d backend nginx
docker compose ps
curl -fsS https://$DOMAIN/actuator/health
```

RDS is managed outside this Compose project. Compose service names and commands must match the actual deployment file. ECR pull uses EC2 Instance Profile; runtime secrets come from AWS Secrets Manager. Do not run unverified commands.

## 3. Frontend deploy/update

1. Check actual Frontend package manager and build script from repository.
2. Build from reviewed FE commit.
3. Record build output, FE SHA and dependency lock version.
4. Copy output to a versioned release directory on Nginx host.
5. Update Nginx release path and SPA fallback.
6. Validate `nginx -t`; graceful reload.
7. Verify asset load, direct route/reload, `/api/v1` proxy, cookie flags and browser console.
8. Keep previous FE release directory for rollback.

Do not hard-code `pnpm build` unless actual Frontend repository defines that command.

## 4. Health checklist

- Nginx healthy.
- Backend healthy.
- PostgreSQL private endpoint reachable.
- Flyway current.
- `/actuator/health` `UP`.
- `/api/v1/health` đúng permission.
- Startup log không có error.

## 5. Restart

1. Stop routing tại Nginx.
2. Graceful shutdown Backend.
3. Chờ connection drain.
4. Start cùng immutable artifact.
5. Check health.
6. Run smoke.
7. Restore routing.

## 6. Backup

1. Freeze mutation nếu cần.
2. Run encrypted `pg_dump`.
3. Generate checksum.
4. Upload private object storage.
5. Verify download.
6. Write manifest.

## 7. Restore

Chỉ restore vào isolated target trước. Không restore đè shared DB khi chưa có approval và maintenance plan.

## 8. Incident actions

| Symptom | First action | Không làm |
|---|---|---|
| Backend `DOWN` | Stop routing, inspect safe logs, check DB/secret | Không mark transaction completed thủ công |
| DB unavailable | Stop mutation, check provider/network | Không retry vô hạn |
| Migration fail | Keep app out of traffic, preserve logs | Không sửa migration applied |
| TLS fail | Block HTTPS traffic, inspect certificate | Không disable TLS |
| Certificate renewal fail | Check systemd timer/log, DNS/challenge và expiry; chạy dry run trước reload | Không chờ certificate hết hạn |
| Disk full | Stop non-essential logs, preserve evidence | Không xóa audit/ledger |
| FE/BE mismatch | Revert incompatible artifact hoặc align contract | Không workaround response bằng FE |
| Backup fail | Mark release `NO-GO`, use verified alternative | Không coi backup chưa verify là evidence |

## 9. VM rebuild runbook

Kịch bản mất toàn bộ VM phải dùng resource mới hoặc snapshot đã được kiểm chứng:

1. Apply AWS Security Group/approved firewall rules; public ports only `80/443`.
2. Create replacement EC2 x86_64 instance and attach least-privilege Instance Profile.
3. Register instance with AWS Systems Manager; do not enable public SSH baseline.
4. Install pinned Docker Engine/Compose versions.
5. Restore runtime secret references from AWS Secrets Manager; do not copy secret from evidence.
6. Pull verified Backend image from ECR using Instance Profile.
7. Retrieve Frontend release artifact and configure Nginx/TLS; run `nginx -t`.
8. Connect private RDS or restore latest verified encrypted dump into PostgreSQL 16 isolated target.
9. Run Flyway/schema validation.
10. Start containers and health checks.
11. Restore synthetic seed only when required by acceptance scope.
12. Run smoke, DVT-005/DVT-006/DVT-009 and record elapsed time.
13. Switch DNS/routing only after approval.

Record actual rebuild duration against `RTO ≤ 30m`; if target misses, mark limitation and do not claim RTO compliance.

## 10. Stop

- Disable public routing.
- Stop application/worker.
- Keep PostgreSQL only khi retention yêu cầu.
- Preserve logs/evidence.
- Stop VM khi không cần để kiểm soát cost.
- Record resource state và restart command.
