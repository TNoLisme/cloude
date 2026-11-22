# Cloud Release và Rollback

> **Mã:** BE-CLOUD-12  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed

## 1. Release gates

| Gate | Evidence | Fail action |
|---|---|---|
| Contract | OpenAPI validation pass | Stop release |
| Backend | Relevant tests pass | Stop release |
| Frontend | lint/typecheck/build pass | Stop release |
| Migration | Clean install + upgrade pass | Stop release |
| Security | No unresolved Critical/High in scope | Stop release |
| Artifact | SHA/digest immutable | Rebuild |
| Backup | Pre-release backup + checksum | Stop release |
| Deploy | Health `UP` | Stop routing |
| Smoke | Critical journey pass | Rollback artifact |
| Evidence | Manifest complete | Release incomplete |

## 2. Deploy order

1. Confirm release commit and ECR image digest.
2. Confirm RDS backup/snapshot and restore status.
3. Confirm Secrets Manager references and IAM role.
4. Maintenance mode if migration requires.
5. Pull immutable image from ECR using EC2 Instance Profile.
6. Start Backend profile `demo`.
7. Run Flyway against private RDS using `banking_migrator`.
8. Check health.
9. Serve/update Frontend static files using versioned release directory.
10. Configure SPA fallback, API proxy and cache headers.
11. Run API/FE browser smoke.
12. Remove maintenance mode.
13. Capture evidence.

## 3. Application and Frontend rollback

- Stop new traffic if needed.
- Preserve current logs/evidence.
- Deploy previous verified Backend image digest.
- Switch Nginx to previous verified Frontend release directory.
- Check schema compatibility.
- Start application.
- Validate `nginx -t` and graceful reload.
- Run health, API, browser and business smoke.
- Record result.

## 4. Database rule

Không sửa hoặc xóa migration đã chạy. Không manual `UPDATE` để đưa transfer, ledger hoặc audit về trạng thái mong muốn.

Nếu schema không tương thích: forward compensating migration đã review, restore vào approved target, hoặc giữ maintenance chờ owner decision.

## 5. Release evidence

FE SHA, BE SHA, OpenAPI version, ECR image digest, migration version, redacted config, Secrets Manager version/reference, RDS backup checksum or snapshot identifier, S3 backup object version, health output, smoke output, rollback result và known limitations.
