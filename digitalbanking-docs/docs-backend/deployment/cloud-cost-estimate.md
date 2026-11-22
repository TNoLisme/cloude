# AWS Cost Estimate và Cost-minimized Deployment Option

> **Mã:** BE-CLOUD-16  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed — pricing snapshot cần chốt theo AWS Region/account  
> **Applies to:** Option B — AWS Managed Core  
> **Source decision:** `digitalbanking-docs/docs-common/12. Cloud Deployment & Runbook.md`

## 1. Mục đích

Tài liệu này ước tính chi phí cho Option B — AWS Managed Core và mô tả một Option C tối thiểu hóa chi phí.

Giá cuối phụ thuộc AWS Region, account age, billing plan, free credits, instance plan, storage, backup retention, log volume và data transfer. Placeholder không phải pricing evidence. Trước khi provision, thay placeholder bằng AWS Pricing Calculator snapshot có URL và timestamp.

## 2. Option B — AWS Managed Core

### 2.1. Architecture

```text
Cloudflare DNS
  -> AWS EC2 x86_64
       -> Nginx + Let's Encrypt
            -> Frontend static
            -> Spring Boot Docker Compose
                 -> Amazon RDS PostgreSQL 16 private subnet

RDS backup/PITR + encrypted pg_dump
  -> Amazon S3

GitHub Actions + OIDC
  -> Amazon ECR
  -> AWS Systems Manager

CloudWatch
  -> logs, metrics and alarms
```

### 2.2. Services and billing

| Service | Required | Billing unit | Official known rate/condition | Monthly estimate |
|---|---|---|---|---:|
| EC2 `t3.small`/`t3a.small` | Có | Instance-hour | Region-specific; Pricing Calculator required | `<EC2>` |
| EBS root/data volume | Có | GB-month + I/O where applicable | Volume type/size dependent | `<EBS>` |
| Public IPv4 | Có nếu EC2 public subnet | IP-hour | `$0.005/hour` ≈ `$3.65/month` per address at 730h | `$3.65` |
| RDS PostgreSQL 16 Single-AZ | Có | DB instance-hour | Region/instance-specific; Pricing Calculator required | `<RDS>` |
| RDS storage | Có | GB-month | Storage class/size dependent | `<RDS storage>` |
| RDS backup/snapshot | Có | GB-month over allowance | Depends on retained backup/snapshot size | `<RDS backup>` |
| S3 Standard | Có | GB-month | Region/storage-class dependent | `<S3 storage>` |
| S3 requests | Có | Request count | GET example: `$0.0004/1,000`; PUT/POST/LIST tier-specific | `<S3 requests>` |
| S3 Internet transfer | Có thể | GB transferred | Same-region AWS transfer often $0; Internet allowance/rates depend on policy/region | `<S3 transfer>` |
| ECR private repository | Có | GB-month | `$0.10/GB-month` storage | `<ECR>` |
| Secrets Manager | Có baseline | Secret-month + API calls | `$0.40/secret/month` + `$0.05/10,000 API calls` | `<Secrets>` |
| CloudWatch | Có baseline | Logs, metrics, alarms, queries | 5 GB logs/month, 10 custom/detailed metrics and 10 standard alarms free allowance shown; overage region-specific | `<CloudWatch>` |
| Systems Manager Session Manager | Có | Usage | Basic EC2 Session Manager: no additional usage charge | `$0` usage charge |
| NAT Gateway | Không baseline | Gateway-hour + GB processed | `$0.045/hour` ≈ `$32.85/month` at 730h + `$0.045/GB` in US East example | `$0` baseline |
| KMS | Có nếu customer-managed key | Key-month + API calls | Key/API pricing region-specific | `<KMS>` |
| Domain | Tùy chọn | Domain-year | Registrar/TLD dependent | `<domain>/12` |
| TLS | Có | Certificate | Let's Encrypt: free | `$0` |

### 2.3. Known fixed/minimum components

Known direct floor before EC2/RDS/storage:

```text
Public IPv4:          ≈ $3.65/month
ECR 5 GB:             ≈ $0.50/month
Secrets 4 items:      ≈ $1.60/month
Secrets 5 items:      ≈ $2.00/month
Systems Manager:      $0 usage charge for basic EC2 Session Manager
Let's Encrypt:        $0
NAT Gateway:          $0 baseline
```

Approximate known subtotal:

```text
4 secrets + 5 GB ECR + 1 public IPv4
= $1.60 + $0.50 + $3.65
≈ $5.75/month
```

This subtotal excludes EC2, RDS, EBS, S3, CloudWatch, KMS, domain and data transfer.

### 2.4. Cost formula

```text
AWS monthly estimate
= EC2 instance
+ EBS
+ public IPv4
+ RDS instance
+ RDS storage
+ RDS backup/snapshot
+ S3 storage
+ S3 requests
+ S3 transfer
+ ECR storage
+ Secrets Manager
+ CloudWatch overage
+ KMS
+ domain
+ other data transfer
```

## 3. Required pricing snapshot

Complete this table before provision:

| Field | Value |
|---|---|
| AWS account type | `<new/existing>` |
| AWS Region | `<region>` |
| Currency | `<currency>` |
| EC2 plan | `<instance>` |
| EC2 monthly hours | `730` or actual schedule |
| EBS type/size | `<type>/<GiB>` |
| RDS engine/version | `PostgreSQL 16` |
| RDS plan | `<instance>` |
| RDS deployment | `Single-AZ` |
| RDS storage | `<GiB/type>` |
| RDS backup retention | `<days>` |
| S3 stored data | `<GiB>` |
| S3 versions retained | `<GiB>` |
| ECR image storage | `<GiB>` |
| Secrets count | `<count>` |
| CloudWatch log volume | `<GB/month>` |
| CloudWatch metrics/alarms | `<count>` |
| NAT Gateway | `No` unless approved |
| Expected monthly total | `<amount>` |
| Budget alert | `80% / 100% / 120%` |
| Pricing checked at UTC | `<timestamp>` |
| Calculator/source URL | `<URL>` |

## 4. Cost controls for Option B

- Do not create NAT Gateway by default.
- Use EC2 public subnet with public `80/443`; use SSM instead of public SSH.
- Keep RDS Single-AZ; do not claim HA/Multi-AZ.
- Do not use Read Replica.
- Keep RDS backup retention at minimum approved period.
- Keep S3 and RDS in the same Region.
- Enable S3 lifecycle for old evidence/test objects.
- Enable ECR lifecycle to remove unreferenced images.
- Keep CloudWatch log retention finite.
- Do not enable cross-Region replication.
- Do not deploy Prometheus/Grafana baseline.
- Stop EC2/RDS when environment is not needed, if backup/availability scope permits.
- Delete only approved test resources; preserve release evidence and approved backups.
- Set budget alerts at `80%`, `100%` and `120%` of approved estimate.

## 5. Option C — Cost-minimized single-VM deployment

Option C minimizes paid managed services. It does not provide equivalent recovery/security evidence to Option B.

### 5.1. Architecture

```text
Cloudflare DNS
  -> One x86_64 VPS/VM
       -> Nginx + Let's Encrypt
       -> Frontend static
       -> Spring Boot Docker Compose
       -> PostgreSQL 16 container

Encrypted pg_dump
  -> external object storage or manually retained backup
```

### 5.2. Service choices

| Capability | Cost-minimized selection | Cost |
|---|---|---:|
| Compute | Free-tier eligible x86_64 VM if available; otherwise low-cost VPS | `$0` only while eligibility/quota holds |
| Database | PostgreSQL 16 container on same VM | No separate DB fee |
| Storage | Provider free block storage within quota | `$0` only within quota |
| Backup | Encrypted `pg_dump`; external free quota if available | Not guaranteed `$0` |
| DNS | Cloudflare DNS Free | `$0` plan charge; domain registration may cost |
| TLS | Let's Encrypt | `$0` |
| Registry | GitHub Container Registry or build/pull from approved artifact path | Plan/quota dependent |
| Secrets | Protected file permission `0600` on VM | No separate service fee; weaker audit |
| Monitoring | Actuator + local JSON logs + uptime free allowance | No separate service fee; limited evidence |
| CI/CD | GitHub Actions included allowance or local/manual build | Plan/quota dependent |
| Database HA | None | Not available |
| Managed PITR | None | Not available |

### 5.3. Candidate free/low-cost platforms

#### Google Cloud Free Tier

Official free allowance includes:

- One non-preemptible `e2-micro` VM per month.
- Only `us-west1`, `us-central1` or `us-east1`.
- `30 GB-month` standard persistent disk.
- `1 GB/month` outbound transfer from North America to allowed destinations.

Limitations for this project:

- `e2-micro` is too small for Backend + PostgreSQL + Nginx + Frontend under D5.
- Free regions are not Singapore.
- Free allowance does not include managed PostgreSQL equivalent.
- No free managed RDS-like recovery.
- Performance evidence would not represent target topology/resource baseline.

**Decision:** not suitable as primary zero-cost target; possible smoke-only experiment if resource pressure is accepted.

#### Oracle Cloud Always Free

Potentially attractive because Always Free compute/storage can support a small self-managed stack. Before using, verify current official quota, region capacity, architecture, ARM64 compatibility and account availability.

Risks:

- Ampere A1 uses ARM64; all Docker images/native dependencies must support ARM64.
- Capacity may be unavailable in selected region.
- Registration/account verification can block deployment.
- PostgreSQL, backup, monitoring and recovery remain self-managed.
- Free quota is not a substitute for RTO/RPO evidence.

**Decision:** candidate for Option C only after architecture and quota verification; not primary recommendation.

#### AWS Free Tier/credits

New AWS accounts may receive credits/free allowances depending on account date and current AWS terms. Free credits do not make all services permanently free. RDS, public IPv4, EBS, S3, CloudWatch, Secrets Manager, KMS and data transfer can consume credits or become billable after allowance expires.

**Decision:** treat free credits as cost offset, not as a zero-cost architecture guarantee.

### 5.4. Option C technical limitations

- One VM is one major failure domain.
- No HA.
- No managed PITR.
- Backup durability depends on external storage workflow.
- Secret audit is weaker than Secrets Manager.
- Monitoring evidence is weaker than CloudWatch.
- VM loss can stop both application and database.
- RTO/RPO remain unverified until rebuild/restore drill passes.
- D5 on undersized free VM cannot prove normal target capacity.
- Provider free-tier quota/region may change.

### 5.5. Option C acceptance gates

- [ ] VM architecture is x86_64 or every image/dependency passes ARM64 verification.
- [ ] PostgreSQL 16 role/locking/Flyway tests pass.
- [ ] Backend and PostgreSQL are not public.
- [ ] Protected secrets are outside Git/image and permission `0600`.
- [ ] Encrypted backup is stored outside VM failure domain.
- [ ] Checksum and isolated restore pass.
- [ ] VM rebuild drill passes.
- [ ] Frontend/Nginx/HTTPS/same-origin smoke passes.
- [ ] Known limitations explicitly exclude HA/PITR claims.
- [ ] Cost remains `$0` only with current provider quota/credit evidence.

## 6. Option comparison

| Dimension | Option B — AWS Managed Core | Option C — Cost-minimized VM |
|---|---|---|
| Monthly cost | Paid, predictable after calculator | `$0` only while quota/credits hold |
| Database | RDS managed | PostgreSQL container |
| Backup/PITR | RDS backup/PITR + S3 dump | Manual/scheduled encrypted dump |
| Security | IAM, SG, SSM, Secrets Manager | Firewall, protected file, SSH/console |
| Evidence | Stronger managed-service evidence | More self-built evidence |
| Performance | Configurable baseline | Free-tier resource may be too small |
| Region | Chosen AWS region | Free tier may restrict region |
| Portability | Medium | High |
| Operations | More initial setup, less DB operations | Less initial setup, more ongoing operations |
| HA | Deferred Single-AZ | None |
| RTO/RPO | Must measure; capability stronger | Must measure; capability weaker |
| Recommendation | Primary | Cost fallback/smoke option |

## 7. Final recommendation

### Primary

Use **Option B — AWS Managed Core** when the target requires convincing evidence for security, recovery, storage integrity and operational controls.

### Cost fallback

Use **Option C — Cost-minimized single VM** only when zero/near-zero cost is mandatory and the review accepts:

- No HA.
- No managed PITR.
- Self-managed PostgreSQL.
- Weaker monitoring/audit.
- Provider free-tier limitations.
- Unverified RTO/RPO until drills pass.

### Do not claim

- “Free deployment” without current quota/credit evidence.
- “Production-ready” for Option C.
- “RTO/RPO achieved” without restore/rebuild timing.
- “AWS service free” because account has credits.
- “D5 capacity achieved” on an undersized free VM without resource/latency evidence.

## 8. Required next actions

1. Select one AWS Region for pricing comparison.
2. Run AWS Pricing Calculator for Option B.
3. Record pricing snapshot in this file and `cloud-provider-comparison.md`.
4. Check current Google/Oracle free-tier eligibility only for Option C.
5. Decide whether zero-cost limitation is acceptable for audit.
6. Keep Option B as recommendation unless cost owner explicitly selects Option C.
7. Obtain Cloud Owner approval before provisioning.

## 9. Official sources

- [AWS EC2 On-Demand Pricing](https://aws.amazon.com/ec2/pricing/on-demand/)
- [Amazon RDS for PostgreSQL Pricing](https://aws.amazon.com/rds/postgresql/pricing/)
- [Amazon S3 Pricing](https://aws.amazon.com/s3/pricing/)
- [Amazon ECR Pricing](https://aws.amazon.com/ecr/pricing/)
- [AWS Secrets Manager Pricing](https://aws.amazon.com/secrets-manager/pricing/)
- [Amazon CloudWatch Pricing](https://aws.amazon.com/cloudwatch/pricing/)
- [AWS Systems Manager Pricing](https://aws.amazon.com/systems-manager/pricing/)
- [AWS VPC Pricing](https://aws.amazon.com/vpc/pricing/)
- [Google Cloud Free Tier](https://docs.cloud.google.com/free/docs/free-cloud-features)
- [Oracle Cloud Free Tier](https://www.oracle.com/cloud/free/)
- [Let's Encrypt](https://letsencrypt.org/about/)
- [Cloudflare DNS](https://developers.cloudflare.com/dns/)
