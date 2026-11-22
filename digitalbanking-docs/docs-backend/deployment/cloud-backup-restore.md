# Cloud Backup và Restore Decision

> **Mã:** BE-CLOUD-11  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed
> **Applies to:** Option B — AWS Managed Core
> **Source decision:** `digitalbanking-docs/docs-common/12. Cloud Deployment & Runbook.md`

## 1. Backup layers

1. RDS automated backup/PITR.
2. RDS manual snapshot trước migration/risky change.
3. Encrypted logical backup bằng `pg_dump`.
4. Private Amazon S3 bucket with versioning.
5. Checksum và manifest.
6. Restore drill vào database cô lập.

Managed RDS backup không thay thế portable encrypted `pg_dump` evidence.

## 2. Backup events

- Trước migration.
- Trước demo có mutation chuẩn bị dữ liệu.
- Theo lịch vận hành đã chốt.
- Trước rollback có rủi ro schema/data.

## 3. Command baseline

```bash
pg_dump \
  --format=custom \
  --no-owner \
  --no-acl \
  --schema=banking \
  --file="$BACKUP_FILE" \
  "$DATABASE_URL"

sha256sum "$BACKUP_FILE" > "$BACKUP_FILE.sha256"
age -r "$BACKUP_AGE_RECIPIENT" -o "$BACKUP_FILE.age" "$BACKUP_FILE"
sha256sum "$BACKUP_FILE.age" > "$BACKUP_FILE.age.sha256"
```

`age` là baseline portable cho authenticated encryption. `BACKUP_AGE_RECIPIENT` là public recipient; private identity file nằm trong protected secret mechanism và chỉ dùng khi restore. Tách backup encryption key khỏi DB credentials. Manifest chỉ ghi encryption key ID, không ghi key/passphrase.

Nếu môi trường chưa có `age`, phải cài binary/package đã pin version và ghi version vào evidence; không tự thay bằng `openssl enc -aes-256-cbc` vì CBC không cung cấp authenticated encryption.

Backup schedule baseline: chạy `pg_dump` hằng ngày lúc `02:00 UTC` và chạy thêm trước migration/demo. Schedule là operational proposal tới khi owner chốt timezone/retention.

## 4. Restore procedure

1. Stop mutation traffic tại Nginx.
2. Bảo toàn logs/evidence.
3. Tạo target DB cô lập.
4. Download private backup.
5. Verify checksum của encrypted file.
6. Decrypt bằng private identity đã được cấp qua secret mechanism.
7. `pg_restore` vào target.
8. Chạy Flyway/schema validation.
9. Kiểm tra ledger balance, transfer state, audit chain và row counts.
10. Chạy read-only smoke.
11. Ghi expected/actual.
12. Chỉ switch traffic sau approval.

## 5. Retention và scheduled backup

- Backup object dùng append-only name gồm UTC timestamp và release SHA; không overwrite.
- Bucket/object lifecycle phải khớp retention đã được owner duyệt.
- Giữ encrypted dump, encrypted checksum và manifest cùng release record.
- Xóa plaintext dump sau khi encryption và encrypted checksum đã được kiểm tra.
- Backup thất bại hoặc quá hạn phải tạo alert và giữ release ở `NO-GO` nếu backup là release gate.

## 6. Acceptance

- Checksum pass.
- Restore exit code 0.
- Schema đúng migration.
- Audit chain verify pass hoặc limitation rõ.
- Ledger invariant pass.
- Không raw secret output.
- Target DB không phải shared DB.

## 7. Failure cases

| Failure | Action |
|---|---|
| Checksum mismatch | Dừng restore, đánh dấu invalid |
| Decrypt failure | Dừng, kiểm tra key ID/version |
| Migration mismatch | Dừng traffic, không sửa migration cũ |
| Missing audit rows | Giữ isolated target, manual review |
| Storage unavailable | Dùng provider backup đã verified |
| Restore partial | Hủy isolated target, không nhận traffic |
