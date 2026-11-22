# Cloud Compute Decision

> **Mã:** BE-CLOUD-04  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed
> **Applies to:** Option B — AWS Managed Core
> **Source decision:** `digitalbanking-docs/docs-common/12. Cloud Deployment & Runbook.md`

## 1. Requirement

Compute phải chạy Nginx, Frontend static, Spring Boot container, Docker Compose, backup scheduler và log rotation. Target không cần autoscaling hoặc nhiều application instance.

## 2. Candidates

| Option | Technical advantages | Technical disadvantages |
|---|---|---|
| AWS EC2 | VPC, Security Group, IAM, SSM, EBS snapshot, ECR and CloudWatch integration | More network/IAM configuration; multi-dimensional billing |
| DigitalOcean Droplet | Firewall, snapshot, simple VM billing, Docker support | Weaker IAM/managed-service integration; fewer enterprise controls |
| GCP Compute Engine | VPC, IAM, persistent disk, Cloud Monitoring | Similar cloud networking complexity; no advantage required by target |
| Azure VM | VNet, NSG, managed identity, Azure Monitor | Portal/policy complexity; no target-specific advantage |
| Hetzner Cloud | Strong price/performance and portability | More self-managed DB/backup/observability burden |

AWS Lightsail is not baseline because its networking and service integration are less suitable for RDS-style private architecture.

## 3. Criteria

- x86_64 để giảm ARM compatibility risk.
- Baseline 2 vCPU, 4 GB RAM.
- SSD đủ image/log/cache/temporary backup.
- Static public IPv4 hoặc DNS target ổn định.
- Firewall và SSH key.
- Snapshot/rebuild procedure.
- Dễ recreate theo runbook.

## 4. Proposed selection

Chọn **AWS EC2 x86_64**, instance class `t3` hoặc `t3a`, Ubuntu LTS, Docker Engine và Docker Compose plugin.

Không chọn `t4g` vì `t4g` dùng ARM64. Chỉ dùng ARM sau khi kiểm tra toàn bộ image và native dependency.

EC2 được đặt trong public subnet để giảm NAT Gateway cost; chỉ Nginx `80/443` public. Đóng `22/tcp` và dùng AWS Systems Manager Session Manager. RDS nằm private subnet, không public.

Baseline resource không phải capacity proof. D5 phải đo CPU, memory, disk I/O, DB pool và latency trước khi kết luận.

## 5. Security configuration

- AWS Security Group/Cloud Firewall chỉ mở `80/tcp` và `443/tcp` public.
- Không mở `5432/tcp` public.
- Không mở `22/tcp` public; quản trị qua Systems Manager Session Manager.
- EC2 Instance Profile cấp quyền tối thiểu để pull ECR, đọc Secrets Manager, ghi CloudWatch và đọc/ghi S3 backup theo prefix.
- SSH key không dùng làm baseline administration channel.
- User deploy không dùng root.
- Không expose Docker socket Internet; Docker daemon chỉ bind local socket.
- Cài security updates theo policy đã review; ghi cách kiểm tra update status.
- `fail2ban` là tùy chọn, chỉ thêm nếu SSH được bật theo exception.
- Log rotation giới hạn dung lượng.

## 6. Alternatives rejected

- Kubernetes: vượt nhu cầu và tăng failure surface.
- Serverless container: không khớp Docker Compose/Nginx.
- Shared hosting: không kiểm soát private DB/Docker/runbook.
- ARM VM: chỉ chọn sau khi kiểm chứng toàn bộ image/dependency.

## 7. Verification

- [ ] VM x86_64 đúng region/plan.
- [ ] SSH key login pass; password login disabled.
- [ ] Firewall chỉ mở port cần thiết.
- [ ] Docker và Compose version ghi evidence.
- [ ] Nginx nhận HTTPS.
- [ ] Backend bind private network.
- [ ] PostgreSQL port không reachable Internet.
- [ ] Snapshot hoặc rebuild procedure verified.
- [ ] Cost alert enabled.
