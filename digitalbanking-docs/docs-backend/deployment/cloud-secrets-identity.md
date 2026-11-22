# Cloud Secrets và Identity Decision

> **Mã:** BE-CLOUD-08  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed

## 1. Secrets cần quản lý

- `DB_APP_PASSWORD`.
- `DB_MIGRATION_PASSWORD`.
- `APP_PII_AES_KEY_BASE64`.
- `APP_HMAC_KEY_BASE64`.
- Registry pull token.
- Object storage access key/secret.
- SSH deploy key.
- TLS private material.

Không đưa secret vào source, image, command line, log, screenshot hoặc evidence.

## 2. Candidates

| Option | Technical advantages | Technical disadvantages |
|---|---|---|
| AWS Secrets Manager | Versioning, IAM, CloudTrail, runtime integration, rotation path | Per-secret/API/KMS cost |
| SSM Parameter Store SecureString | Lower cost for static values, IAM/KMS integration | Rotation workflow less native; must build more procedure |
| Protected file trên VM | Provider-neutral, simple fallback | Manual rotation/audit; host compromise exposes file |
| Docker Compose secret file | Keeps value outside image, scoped mount | Host/file lifecycle still self-managed |
| HashiCorp Vault | Strong policy/rotation/dynamic secrets | Separate service to operate; excessive scope |
| `.env` trong repository | None acceptable | Secret exposure |


## 3. Proposed model

- AWS Secrets Manager là baseline runtime secret store.
- GitHub Actions dùng GitHub OIDC để assume AWS IAM role; không lưu AWS access key dài hạn.
- EC2 Instance Profile đọc secrets theo ARN/prefix tối thiểu.
- Secrets Manager quản lý DB passwords, AES/HMAC keys và backup encryption identity reference.
- AWS CloudTrail ghi access/configuration event.
- Secret tách theo environment.
- Key ID ghi config/evidence; key value không ghi.

SSM Parameter Store `SecureString` là cost fallback cho static secret nếu Secrets Manager cost trở thành blocker. Protected file chỉ dùng fallback provider-independent, không phải baseline AWS.

## 4. Database identity

- `banking_app`: runtime DML tối thiểu.
- `banking_migrator`: Flyway DDL.
- Backup identity riêng nếu có.
- Không dùng root/superuser cho application.
- Password các role khác nhau.

## 5. Rotation

1. Tạo secret version mới.
2. Ghi change record không chứa value.
3. Update runtime secret.
4. Restart/reload.
5. Smoke test.
6. Revoke old secret sau verification.
7. Ghi key ID/version.

MVP chưa automatic key rotation. Manual rotation cần owner approval.

## 6. Verification

- [ ] Secret scanning pass.
- [ ] Image layer không chứa secret.
- [ ] CI log đã kiểm tra redaction.
- [ ] Runtime đọc secret thành công.
- [ ] Missing/invalid secret fail-fast.
- [ ] SSH password login disabled.
- [ ] Deploy key scope tối thiểu.
- [ ] DB roles tách biệt.
- [ ] Log không chứa password, OTP, token, raw PII.
