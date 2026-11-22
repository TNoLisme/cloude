# Cloud Provider Comparison

> **Mã:** BE-CLOUD-02  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed
> **Applies to:** Option B — AWS Managed Core recommendation and alternatives
> **Source decision:** `digitalbanking-docs/docs-common/12. Cloud Deployment & Runbook.md`

## 1. Phạm vi

So sánh nền tảng tổng thể để làm shortlist. Đây không phải quyết định cuối cho từng service. Quyết định cuối nằm trong `cloud-*-decision.md`.

## 2. Tiêu chí và trọng số

Chấm điểm 1–5; điểm cao hơn tốt hơn.

| Tiêu chí | Trọng số | Cách đánh giá |
|---|---:|---|
| Functional fit | 30% | Docker VM, PostgreSQL 16, private network, backup |
| Deployment simplicity | 20% | Provision, firewall, DNS, deploy lần đầu |
| Operational simplicity | 15% | SSH, logs, restart, restore, troubleshooting |
| Security | 15% | Private access, firewall, IAM, secret boundary |
| Evidence quality | 10% | Output dễ ghi vào evidence package |
| Cost predictability | 5% | Pricing rõ, alert, quota |
| Portability | 5% | Docker/S3/PostgreSQL standard |

`Integration cost` và `lock-in` là penalty khi kiểm tra lựa chọn cuối.

## 3. Shortlist

| Provider | Điểm mạnh | Rủi ro | Capability phù hợp |
|---|---|---|---|
| DigitalOcean | UI đơn giản, Droplet, Managed PostgreSQL, Spaces, firewall | Ít enterprise service hơn hyperscaler | Compute, DB, storage |
| AWS | EC2, RDS, S3, IAM, VPC mature | IAM/VPC/billing phức tạp | Khi có account/credit hoặc cần ecosystem |
| GCP | Compute Engine, Cloud SQL, Cloud Storage | IAM/VPC cần cấu hình kỹ | Khi có GCP account |
| Azure | VM, Azure Database, Blob, Key Vault | Portal/policy nhiều lớp | Khi có Azure account |
| Hetzner | VM tốt, giá rõ | Managed DB/object workflow hạn chế hơn | Compute |
| Render/Railway | Deploy application nhanh | Sleep, platform limits, topology khác Compose | Demo ngắn |
| Fly.io | Container/region linh hoạt | Network/volume/debug khó hơn | Edge workload |

## 4. Ma trận định tính

| Tiêu chí | DigitalOcean | AWS | GCP | Azure | Hetzner | Render/Railway |
|---|---:|---:|---:|---:|---:|---:|
| Functional fit | 4 | 5 | 5 | 5 | 4 | 3 |
| Deployment simplicity | 5 | 3 | 3 | 3 | 4 | 5 |
| Operational simplicity | 4 | 4 | 3 | 3 | 3 | 4 |
| Security baseline | 4 | 5 | 5 | 5 | 3 | 3 |
| Evidence quality | 4 | 5 | 4 | 4 | 3 | 2 |
| Cost predictability | 4 | 3 | 3 | 3 | 5 | 3 |
| Portability | 4 | 3 | 3 | 3 | 5 | 2 |
| Weighted baseline | 4.05 | **4.25** | 3.85 | 3.85 | 3.70 | 3.25 |

Các score mới phản ánh AWS capability tích hợp: VPC/RDS/S3/IAM/ECR/CloudWatch/SSM. Score không thay thế pricing, quota, region và runtime verification.

## 5. Recommendation

Chọn **AWS Managed Core** cho compute, PostgreSQL, object storage, registry, secrets, monitoring và administration.

| Capability | AWS service | Quyết định |
|---|---|---|
| Compute | EC2 x86_64 | Chọn |
| Database | RDS PostgreSQL 16 | Chọn có điều kiện theo role/restore gates |
| Object storage | S3 | Chọn |
| Registry | ECR | Chọn |
| Secrets | Secrets Manager | Chọn |
| Monitoring | CloudWatch | Chọn |
| Administration | Systems Manager | Chọn |

Chọn service ngoài AWS khi có lý do capability rõ:

- Cloudflare DNS: provider-neutral DNS, DNSSEC và API.
- Let's Encrypt + Nginx: portable TLS termination, không cần AWS Load Balancer.
- GitHub Actions + GitHub OIDC: source/review/CI đã ở GitHub, không lưu AWS access key dài hạn.

AWS không được coi là `Confirmed` trước khi pass pricing, region, quota, role, backup, restore, OIDC và runtime verification.

## 6. Khi nào không chọn baseline

Đổi provider/service nếu:

- Không có region phù hợp.
- Không hỗ trợ PostgreSQL 16 hoặc required locking.
- Không có private access.
- Không tách được runtime/migration role.
- Restore không kiểm chứng được.
- Billing/quota không kiểm soát được.
- Account verification gây blocker milestone.

## 7. Pricing evidence

Provider score cannot move from `Proposed` to `Confirmed` without a pricing snapshot. Record one row per candidate using actual provider pricing page/calculator at decision time.

| Provider | Region | Compute plan | Database plan | Storage plan | Estimated monthly total | Checked at UTC | Source URL | Status |
|---|---|---|---|---|---:|---|---|---|
| DigitalOcean | `<region>` | `<plan>` | `<plan>` | `<plan>` | `<amount>` | `<timestamp>` | `<URL>` | Pending |
| AWS | `<region>` | `<plan>` | `<plan>` | `<plan>` | `<amount>` | `<timestamp>` | `<URL>` | Pending |
| GCP | `<region>` | `<plan>` | `<plan>` | `<plan>` | `<amount>` | `<timestamp>` | `<URL>` | Pending |
| Azure | `<region>` | `<plan>` | `<plan>` | `<plan>` | `<amount>` | `<timestamp>` | `<URL>` | Pending |
| Hetzner | `<region>` | `<plan>` | N/A or `<plan>` | `<plan>` | `<amount>` | `<timestamp>` | `<URL>` | Pending |

Không xem placeholder là pricing evidence. Khi provider/account/region được chọn, thay từng `Pending` bằng số liệu có URL và timestamp; nếu không lấy được giá chính xác, ghi `Not available` và lý do.

URLs tham khảo:

- <https://www.digitalocean.com/pricing>
- <https://aws.amazon.com/pricing/>
- <https://cloud.google.com/pricing>
- <https://azure.microsoft.com/pricing/>
- <https://www.hetzner.com/cloud/>
- <https://developers.cloudflare.com/r2/pricing/>

Không đưa giá không có ngày kiểm tra vào acceptance evidence.
