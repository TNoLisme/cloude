# Cloud Database Decision

> **Mã:** BE-CLOUD-05  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed

## 1. Requirement

PostgreSQL là system of record cho transfer, ledger, idempotency, audit, fraud và notification.

Bắt buộc giữ semantics PostgreSQL 16:

- `JSONB`.
- `pg_advisory_xact_lock`.
- `FOR UPDATE NOWAIT`.
- `FOR UPDATE SKIP LOCKED`.
- Flyway forward-only migration.
- `READ COMMITTED`.
- Backup/restore.
- Private access.
- Runtime/migration privilege separation.

## 2. Candidates

| Option | Technical advantages | Technical disadvantages |
|---|---|---|
| Amazon RDS PostgreSQL 16 | Private subnet, automated backup/PITR, snapshot restore, CloudWatch, VPC/Security Group | Provider role limits, AWS cost/IAM/VPC complexity |
| DigitalOcean Managed PostgreSQL | Simple managed workflow, private network | Role/extension/restore evidence must be verified; fewer enterprise controls |
| GCP Cloud SQL | Managed backup/PITR, private IP, monitoring | GCP IAM/VPC complexity; no target-specific advantage over RDS |
| Azure Database for PostgreSQL | Managed backup, private endpoint, Key Vault integration | Azure policy/identity complexity; no target-specific advantage |
| PostgreSQL container on EC2 | Full role/extension/version control, portable | Self-managed patching, backup, restore, failover and monitoring |
| Neon/Supabase | Developer-oriented workflow | Private access, no-sleep, role and restore semantics need verification |


## 3. Proposed selection

Chọn **Amazon RDS for PostgreSQL 16** nếu pass investigation gates bên dưới. Hiện trạng vẫn là `Proposed`; RDS không được coi là phù hợp chỉ dựa trên product description.

Lý do kỹ thuật:

- Private subnet/VPC và Security Group tạo network boundary rõ.
- Automated backup, snapshot và point-in-time recovery giảm recovery surface tự quản lý.
- CloudWatch cung cấp DB metrics/alarms.
- RDS PostgreSQL giữ PostgreSQL transaction/locking semantics cần test.
- AWS IAM, CloudTrail và Secrets Manager tạo evidence security mạnh hơn baseline self-managed.

RDS role model vẫn phải test thật. RDS master user có giới hạn provider-specific; không giả định native PostgreSQL `SUPERUSER` đầy đủ.

### 3.1. Investigation bắt buộc trước khi chọn

Xác nhận bằng AWS documentation hoặc test trên RDS instance cô lập:

- Có tạo được `banking_app` và `banking_migrator` riêng không.
- Có cấp DDL cho migration role và hạn chế runtime role không.
- Có giữ append-only privilege/trigger policy không.
- Có private endpoint, DB Subnet Group hai AZ và Security Group allowlist không.
- Có PostgreSQL 16.x và required SQL semantics không.
- Có automated backup/PITR và isolated restore không.

Nếu role separation, required SQL semantics hoặc isolated restore không đạt, fallback PostgreSQL 16 container trên EC2 là bắt buộc.

## 4. Role model

- `banking_app`: runtime least privilege.
- `banking_migrator`: Flyway DDL.
- Backup role riêng nếu provider hỗ trợ.
- Không dùng migration credential runtime.

Nếu managed service không cho role model cần thiết, fallback PostgreSQL 16 container trong private Docker network VM.

## 5. Connection policy

- Backend dùng private endpoint.
- TLS DB connection theo provider.
- `currentSchema=banking`.
- Pool size lấy từ `configuration.md`, chốt sau D5.
- Không ghi password vào JDBC URL/command line.
- Không allow `0.0.0.0/0`.

## 6. Verification gates

- [ ] PostgreSQL 16.x.
- [ ] Private endpoint.
- [ ] `JSONB` read/write pass.
- [ ] Advisory lock pass.
- [ ] `NOWAIT` lock pass.
- [ ] `SKIP LOCKED` worker pass.
- [ ] Flyway clean install/upgrade pass.
- [ ] `banking_app` không có DDL privilege.
- [ ] Audit/ledger append-only restriction pass.
- [ ] Backup download pass.
- [ ] Restore isolated database pass.
- [ ] Connection limit/timeout ghi evidence.

## 7. Rejected alternatives

- SQLite/H2: không chứng minh PostgreSQL locking, trigger và JSONB.
- Neon/Supabase: chỉ chọn sau khi kiểm tra private access, no-sleep và restore semantics.
- Public DB: vi phạm network boundary.

## 8. Replacement plan

1. Freeze writes.
2. `pg_dump` schema `banking`.
3. Restore vào PostgreSQL 16 target.
4. Verify Flyway, ledger, audit chain và invariants.
5. Switch endpoint secret.
6. Smoke test.
7. Giữ source DB read-only tới acceptance.
