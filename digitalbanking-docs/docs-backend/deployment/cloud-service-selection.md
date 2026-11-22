# Cloud Service Selection

> **Mã:** BE-CLOUD-03  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed
> **Applies to:** Option B — AWS Managed Core
> **Source decision:** `digitalbanking-docs/docs-common/12. Cloud Deployment & Runbook.md`

## 1. Decision template

Mỗi service phải có đủ:

- Requirement.
- Candidates.
- Evaluation criteria.
- Comparison.
- Cost model.
- Security impact.
- Operational impact.
- Integration impact.
- Selected option.
- Rejected alternatives.
- Verification.
- Rollback/replacement.
- Owner/approval.

## 2. Selection matrix

| Capability | Proposed selection | Alternatives | Cost model required before confirmation | Technical reason |
|---|---|---|---|---|
| Compute | AWS EC2 x86_64 (`t3`/`t3a`) | DigitalOcean Droplet, GCE, Azure VM, Hetzner | Instance, EBS, public IPv4, snapshot, outbound | VPC, Security Group, IAM, SSM, ECR and CloudWatch integration |
| PostgreSQL | Amazon RDS PostgreSQL 16, conditional | DigitalOcean Managed PG, Cloud SQL, Azure DB, PostgreSQL VM | Instance, storage, backup/PITR, I/O, outbound | Private subnet, managed backup/PITR, snapshot restore and monitoring |
| Object storage | Amazon S3 | R2, Spaces, B2, Blob | Storage, requests, egress, versioning, Object Lock | Versioning, IAM policy, encryption, lifecycle, checksum and restore tooling |
| DNS | Cloudflare DNS, DNS-only | Route 53, Cloud DNS, DO DNS | Domain, DNS plan, query/proxy cost | Provider-neutral DNSSEC/API; does not couple domain to AWS |
| TLS | Let's Encrypt + Nginx | ACM, managed LB, Origin Cert | Renewal and operational cost | Portable termination on existing Nginx topology |
| Registry | Amazon ECR | GHCR, GAR, DOCR, Docker Hub | Private storage, scan, pull egress, retention | EC2 IAM pull, private digest and AWS security evidence |
| CI/CD | GitHub Actions + GitHub OIDC | GitLab CI, Cloud Build, Jenkins | Runner, artifact retention, deploy runs | Existing source/review platform; no long-lived AWS key |
| Runtime secrets | AWS Secrets Manager | SSM Parameter Store, protected file, Vault | Secret count, API calls, KMS, rotation | IAM, versioning, CloudTrail and rotation path |
| Monitoring | CloudWatch + Actuator + k6 | Prometheus/Grafana, Grafana Cloud | Log ingestion, retention, alarms | Native EC2/RDS logs, metrics and alarms; enough for D5 |
| Backup | RDS backup/PITR + encrypted `pg_dump` to S3 | Provider snapshot, R2/Spaces | DB backup, S3 storage/requests, restore egress | Managed recovery plus portable encrypted restore |

## 3. Cross-service gates

| Gate | Điều kiện đạt |
|---|---|
| Network | Compute truy cập DB private; DB không public |
| Database | Flyway, locking và role policy pass |
| Storage | Private upload/download, checksum giữ nguyên |
| Identity | CI push image; VM pull image bằng credential scoped |
| TLS | Cookie `Secure`, CORS và forwarded headers pass |
| Observability | Health/log/metric không lộ secret/PII |
| Recovery | Backup, isolated restore, artifact rollback pass |
| Cost | Resource nằm trong estimate và billing alert |

## 4. Conditional decisions

- If RDS fails role separation, required PostgreSQL semantics or isolated restore, use PostgreSQL 16 container on private EC2 network.
- If Secrets Manager cost blocks the target, use SSM Parameter Store `SecureString` for static secrets after updating IAM/audit/rotation evidence.
- If S3 cost/egress blocks the target, evaluate R2 or Spaces against versioning, encryption, checksum and restore gates; do not switch only on price.
- If Cloudflare proxy changes client IP or TLS flow unexpectedly, keep DNS-only and terminate TLS at Nginx.
- If EC2 is moved to a private subnet, configure approved VPC endpoints or NAT path before ECR/SSM/CloudWatch deployment.


## 6. Cost reference

Service prices, free-tier conditions and zero-cost Option C are documented in [cloud-cost-estimate.md](cloud-cost-estimate.md). Do not confirm a provider or provision resources before recording a region-specific pricing snapshot.
