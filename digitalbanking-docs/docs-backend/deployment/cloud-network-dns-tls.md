# Cloud Network, DNS và TLS Decision

> **Mã:** BE-CLOUD-07  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed
> **Applies to:** Option B — AWS Managed Core
> **Source decision:** `digitalbanking-docs/docs-common/12. Cloud Deployment & Runbook.md`

## 1. Network target

```text
Internet
  -> Cloudflare DNS (DNS-only)
  -> AWS EC2 x86_64 public subnet
       -> Nginx :443
            -> Frontend static
            -> Spring Boot :8080 private
                 -> Amazon RDS PostgreSQL 16 :5432
```

RDS uses private subnets across two Availability Zones. Baseline RDS deployment is Single-AZ; Multi-AZ HA is deferred and must not be claimed.

## 2. Port matrix

| Port | Public | Rule |
|---:|---|---|
| 80 | Có | EC2 Security Group; HTTP redirect HTTPS |
| 443 | Có | Nginx only |
| 22 | Không | Admin via AWS Systems Manager Session Manager |
| 8080 | Không | Spring Boot private Docker/network binding |
| 5432 | Không | RDS Security Group allows EC2 only |
| 9090/metrics | Không | Monitoring internal/private |

## 3. DNS candidates

| Option | Technical advantages | Technical disadvantages |
|---|---|---|
| Cloudflare DNS | Provider-neutral, DNSSEC, API, optional proxy/WAF | Separate provider boundary; proxy mode changes client IP/TLS behavior |
| Route 53 | AWS-native DNS/API and IAM integration | AWS coupling; no need to move DNS into AWS for target |
| Cloud DNS | GCP-native integration | GCP coupling while core is AWS |
| DigitalOcean DNS | Simple provider integration | Less useful if compute/database are AWS |

**Proposed:** Cloudflare DNS, DNS-only baseline. Do not count proxy/WAF advantages until proxy mode is separately verified.

- AWS EC2 public subnet uses Internet Gateway for required outbound traffic; do not create NAT Gateway by default.
- If EC2 moves to private subnet, use approved VPC endpoints or NAT path for ECR, S3, SSM, CloudWatch and OS updates.
- RDS private subnet does not receive public IP and does not allow public `5432`.


- Dùng domain đã có hoặc đăng ký domain tại registrar được owner chấp thuận; không bắt buộc một registrar cụ thể.
- Tạo DNS zone tại provider đã chọn.
- Cập nhật nameserver nếu domain dùng DNS provider mới.
- Tạo A/AAAA record tới public IP của Nginx; không tạo record public tới Backend/PostgreSQL.
- Kiểm tra propagation bằng resolver độc lập trước khi issue certificate.
- Ghi domain ownership, nameserver, record, timestamp và provider vào evidence; không ghi account credential.

## 4. TLS candidates

| Option | Quyết định |
|---|---|
| Let's Encrypt + Certbot/Nginx | Chọn baseline, portable, auto-renew |
| Cloudflare Origin Certificate | Chỉ khi proxy luôn bật |
| AWS ACM | Chỉ khi AWS ingress/LB |
| Managed load balancer TLS | Hoãn, không cần một VM |

## 5. TLS rules

- TLS 1.2+; ưu tiên TLS 1.3.
- Redirect HTTP sang HTTPS.
- HSTS sau khi domain ổn định.
- `Secure; HttpOnly; SameSite=Strict` cho session cookie.
- `Cache-Control: no-store` cho API.
- Security headers tại Nginx.
- Certbot renewal dùng systemd timer/package mechanism đã cài; không tạo cron trùng nếu timer đang hoạt động.
- Chạy `certbot renew --dry-run` sau cấu hình.
- Kiểm tra certificate expiry định kỳ; cảnh báo khi còn dưới 14 ngày.
- Khi renewal fail: giữ certificate hiện tại, đọc renewal log, kiểm tra DNS/port 80/challenge, sửa nguyên nhân và chạy dry run lại trước reload Nginx.
- Manual recovery: issue certificate lại, kiểm tra hostname/chain, `nginx -t`, graceful reload, rồi smoke HTTPS.
- Không disable TLS để bypass lỗi.

## 6. CORS và forwarded headers

- `APP_CORS_ALLOWED_ORIGINS` chỉ HTTPS origin Frontend.
- Không dùng `*` với cookie.
- Nginx ghi đè `X-Forwarded-For`, `X-Forwarded-Proto`.
- Chỉ bật forwarded-header handling sau khi backend port private.
- Correlation ID được forward theo contract.

## 7. Verification

- [ ] DNS resolve đúng public IP.
- [ ] Certificate hostname đúng.
- [ ] Renewal dry run pass.
- [ ] HTTP redirect pass.
- [ ] TLS baseline pass.
- [ ] Backend `:8080` không reachable Internet.
- [ ] PostgreSQL `:5432` không reachable Internet.
- [ ] CORS reject origin ngoài allowlist.
- [ ] Cookie flags đúng.
- [ ] Health response không lộ infrastructure detail.
