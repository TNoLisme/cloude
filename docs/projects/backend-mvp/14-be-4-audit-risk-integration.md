# BE-4 — Kế hoạch Audit, Risk, Integration và Acceptance

**Người phụ trách:** BE-4 / Integration Owner  
**Reviewer:** Tất cả module owner  
**Phối hợp:** BE-1 security; BE-2 DB metrics; BE-3 financial evidence; FE/BE k6/CI/E2E  
**Trạng thái:** Kế hoạch thực thi; BE-4 điều phối, không tự làm toàn bộ execution.  
**Mục tiêu:** Bảo đảm audit/risk đúng và chuyển toàn bộ code/test thành acceptance package có evidence thật.

---

## 1. Phạm vi công việc

### 1.1 Bao gồm

- Audit append/query/redaction.
- Risk rules và AFTER_COMMIT.
- Cursor pagination review.
- Acceptance matrix.
- Evidence naming/index.
- Merge gate.
- CI review.
- Load-result review.
- Security/log scan review.
- Final local acceptance report.
- Documentation synchronization.

### 1.2 Không bao gồm

- Viết toàn bộ k6 script.
- Viết toàn bộ GitHub Actions.
- Sở hữu identity/account/transfer implementation.
- Thêm ML/review workflow/risk blocking.
- Cloud provisioning trước local gate.
- Đánh dấu `ACCEPTED` khi P0 chưa đủ evidence.

---

## 2. File và vùng code

```text
be/src/main/java/com/bank/simulator/audit/**
be/src/main/java/com/bank/simulator/risk/**
be/src/test/java/com/bank/simulator/audit/**
be/src/test/java/com/bank/simulator/risk/**
docs/projects/backend-mvp/evidence/**
docs/projects/backend-mvp/09-acceptance-gap-and-team-plan.md
docs/projects/backend-mvp/10-detailed-team-task-handover.md
```

Shared ownership:

| Khu vực | Owner | Quy tắc |
|---|---|---|
| `contracts/openapi.yaml` | BE-4 merge | Module owner proposal |
| Flyway numbering | BE-4 | Không sửa migration cũ |
| `pom.xml`/application config | BE-4 điều phối | Affected owner review |
| `docker-compose.yml`/`Dockerfile` | BE-4 | FE/BE hỗ trợ |
| `09`/`10` docs | BE-4 | Nhận input từ owner |

---

## 3. Kết quả phải bàn giao

```text
- Audit/risk test output.
- Acceptance matrix.
- Evidence index.
- Merge review result.
- FE handoff package.
- Final local acceptance report.
- Open gaps/blocked decision list.
```

Mẫu evidence:

```text
Requirement:
Owner:
Environment:
Database isolation:
Command:
Tool version:
Expected:
Actual:
Status:
Evidence file:
Security review:
Known limitation:
Next action:
```

Không ghi password, PIN, OTP, JWT, refresh token, secret, full account number.

---

## 4. Kế hoạch thực thi theo bước

### Bước 1 — Chuẩn hóa status matrix

Tạo bảng:

```text
Requirement | Owner | Endpoint/module | Command | Evidence | Status | Gap | Next action
```

Status hợp lệ:

```text
PLANNED
IMPLEMENTED
VERIFIED
BLOCKED
NOT_ACCEPTED
```

Không dùng `ACCEPTED` trong từng row. Chỉ final report mới kết luận `ACCEPTED` hoặc `NOT ACCEPTED`.

### Bước 2 — Audit integrity

Kiểm tra audit events cho:

- Registration.
- Counter customer creation.
- Account creation.
- Seed.
- Block/unblock.
- Transfer success/failure cần audit.
- PIN setup/change/reset.
- Recovery initiate/verify/confirm.
- Auth outcome cần audit.

Mỗi row phải có:

- actor.
- event type.
- target.
- outcome.
- occurredAt.
- correlationId.
- safe summary.

Không được có:

- password/hash.
- PIN/OTP.
- access/refresh/CSRF token.
- secret.
- full account number.
- raw credential identifier.
- PII không cần thiết.

Kiểm tra append-only:

- Không có update/delete route.
- Customer không query audit.
- Auditor/Admin query đúng role.
- Cursor/filter ổn định.

### Bước 3 — Risk integrity

Rules phải verify:

- `LARGE_TRANSFER v1`: `5,000,000` không flag.
- `5,000,001` có flag.
- `HIGH_FREQUENCY v1`: 5 outgoing completed không flag.
- 6 outgoing completed trong 10 phút có flag.
- Incoming không tính.
- Pending/failed không tính.
- Chạy sau transfer commit.
- Risk failure không rollback transfer.
- Re-evaluation không duplicate `(transfer_id, rule_id, rule_version)`.
- Không có review state/mutation endpoint.

### Bước 4 — Evidence review từ BE-1/2/3

Mỗi owner gửi:

1. Command.
2. Actual output.
3. Database/profile.
4. Test count.
5. Security review.
6. FE impact.
7. Known limitation.

BE-4 kiểm tra:

- Output có thật.
- Không lấy planned docs làm pass.
- Không chứa secret.
- Test đúng database isolation.
- Status trong matrix đúng.

### Bước 5 — Merge gate review

Mỗi PR kiểm tra:

1. Đúng owner.
2. Scope bounded.
3. Test command/output.
4. OpenAPI impact.
5. DB/migration impact.
6. Role/ownership impact.
7. Money/idempotency impact.
8. FE handoff.
9. Secret/log/privacy.
10. Unrelated format/generated files.

### Bước 6 — Review k6/CI từ FE/BE

BE-4 không viết thay FE/BE. Review:

- k6 workload đúng 50 VU/20 RPS/10 phút.
- Disposable DB.
- Expected business 4xx tách unexpected 5xx.
- p50/p95/p99.
- CPU/RAM/pool/lock metrics.
- Financial invariant.
- CI OpenAPI/FE jobs.
- Artifact không có secret.

### Bước 7 — Final local acceptance

Chỉ chuyển final state sau khi có evidence:

- Rollback.
- Timeout-after-commit.
- Restart/retry.
- DB outage.
- Concurrent overdraft.
- Concurrent idempotency.
- Opposite-direction lock.
- Block-vs-confirm.
- k6.
- Security/dependency/image/log scan.
- FE Customer/Operator/Auditor E2E.
- OpenAPI/build/test.

### Bước 8 — Cloud decision gate

Chưa triển khai cloud trước local gate.

Khi local pass, team chốt một provider:

```text
AWS ECS/EC2
hoặc GCP Cloud Run
hoặc Render/Railway
```

Chốt thêm:

- region.
- account/project owner.
- budget cap.
- alert.
- TLS.
- database backup.
- secret manager.
- same-origin ingress.
- rollback.
- teardown.

---

## 5. Lệnh kiểm thử và review

Audit/risk:

```powershell
cd D:\work\Xgame\XCreative\yuiyL\Cloud\cloude\be
mvn -q -Dtest="*Audit*Test,*Risk*Test,*Transfer*Test" test
```

Backend gate:

```powershell
mvn -q -DskipTests package
mvn -q test
```

Contract/FE gate:

```powershell
cd ..
python be/scripts/validate-openapi.py
cd frontend
npm run api:check
npm run typecheck
npm test
npm run build
```

Diff:

```powershell
cd ..
git diff --check
```

Nếu một command fail:

- Ghi exact error.
- Phân loại code/test/environment/tooling.
- Không retry vô hạn.
- Không đổi status thành pass.

---

## 6. Acceptance criteria

- Audit/risk tests pass.
- P0 evidence links đầy đủ.
- Planned/implemented/verified tách biệt.
- Contract/DB/security impact reviewed.
- Migration baseline V1–V7 đồng bộ.
- k6/CI/security có owner riêng.
- Final report ghi rõ `ACCEPTED` hoặc `NOT_ACCEPTED`.
- Known limitations được liệt kê.

---

## 7. FE handoff package

Gửi một package duy nhất:

1. OpenAPI path/schema/version.
2. Error code matrix.
3. Role/ownership matrix.
4. Demo setup bằng environment credentials.
5. Local mailbox setup.
6. Synthetic test-data instructions.
7. State transition examples.
8. Expected audit rows.
9. Expected risk rows.
10. Health/Swagger/base URL.
11. API smoke command.
12. Browser E2E command.
13. k6 report path.
14. Known limitations:

```text
Best-effort risk flagging
No real SMS/Email delivery
No ML
No cancel/resend transfer endpoint
No cloud acceptance before provider evidence
```

---

## 8. Handoff cho owner khác

### BE-1

- Sensitive-log checklist.
- Auth audit events.
- Security scan format.
- Auth gaps.

### BE-2

- Account/seed/block audit.
- DB metric fields.
- Schema/index evidence.

### BE-3

- Transfer audit/risk requirements.
- Financial invariant format.
- Failure/recovery report template.

### FE/BE

- Final contract/error matrix.
- E2E matrix.
- Screenshot policy.
- CI artifact path.
- k6 review checklist.

---

## 9. Definition of Done

- Audit/risk verified.
- Matrix current.
- Every P0 item has owner/status/evidence.
- Merge gate reviewed.
- FE handoff delivered.
- Final local report ready.
- No cloud `ACCEPTED` before provider-specific evidence.
- No commit/push without explicit instruction.
