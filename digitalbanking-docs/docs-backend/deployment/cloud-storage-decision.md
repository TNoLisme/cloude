# Cloud Storage Decision

> **Mã:** BE-CLOUD-06  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed
> **Applies to:** Option B — AWS Managed Core
> **Source decision:** `digitalbanking-docs/docs-common/12. Cloud Deployment & Runbook.md`

## 1. Storage classes

| Dữ liệu | Nơi lưu |
|---|---|
| Transaction data | PostgreSQL |
| PostgreSQL dump | Private object storage |
| Evidence ZIP/report | Object storage hoặc CI artifact |
| Container image | Private registry |
| Runtime log | Rotated local log + optional log service |
| TLS private material | Protected runtime secret path |

Không dùng object storage thay PostgreSQL.

## 2. Candidates

| Service | Technical advantages | Technical disadvantages |
|---|---|---|
| Amazon S3 | Versioning, lifecycle, IAM policy, encryption, checksum, Object Lock option, RDS integration | More AWS billing/IAM configuration; egress/storage/request cost |
| Cloudflare R2 | S3-compatible and egress advantage | Separate provider boundary; AWS IAM/RDS integration absent |
| DigitalOcean Spaces | Simple S3-compatible storage and provider consolidation | Versioning/Object Lock and enterprise policy capability must be verified |
| Backblaze B2 | Cost-oriented object storage, S3-compatible | Smaller ecosystem and fewer AWS-native integrations |
| Azure Blob/GCS | Strong native integration in own ecosystem | Provider lock-in; extra provider if AWS core remains |
| VM local disk | No service cost | Same VM failure domain; not acceptable as only backup |


## 3. Proposed selection

Chọn **Amazon S3** cho backup/evidence private bucket.

Ưu điểm kỹ thuật so với alternatives:

- S3 Versioning bảo vệ object khỏi overwrite/xóa nhầm.
- S3 Lifecycle quản lý retention/tiering.
- Bucket policy và IAM phân quyền chi tiết.
- SSE-S3/SSE-KMS và client-side `age` hỗ trợ encryption layers.
- Checksum, multipart upload và restore tooling mature.
- Object Lock là option khi cần retention/WORM evidence.
- Tích hợp trực tiếp với RDS, IAM, CloudWatch và SSM.

S3 không được coi là confirmed trước khi pass private bucket, versioning, encryption, checksum và isolated restore gates.

## 4. Bucket policy

- Private bucket, không public-read.
- Prefix: `backups/`, `evidence/`, `restore-test/`.
- Upload chỉ từ backup job/deploy operator.
- Download chỉ từ operator role.
- Versioning/lifecycle nếu plan hỗ trợ.
- Encrypt trước upload.
- Không upload `.env`, token, raw PII hoặc DB password.

## 5. Backup object contract

```text
<environment>-<db>-<utc-timestamp>.dump.enc
<environment>-<db>-<utc-timestamp>.dump.enc.sha256
<environment>-<db>-<utc-timestamp>.manifest.json
```

Manifest chứa environment, timestamp, schema, PostgreSQL major version, Git SHA, migration version, encryption key ID và checksum. Không chứa key value.

## 6. Verification

- [ ] Bucket private.
- [ ] Upload/download private pass.
- [ ] Checksum trước/sau download giống nhau.
- [ ] Decrypt và restore isolated DB pass.
- [ ] Object không chứa raw PII/secret.
- [ ] Lifecycle/retention ghi rõ.
- [ ] Delete chỉ object test đã duyệt.

## 7. Rejected alternatives

- Local disk làm backup duy nhất: mất cùng VM.
- CI artifact làm backup duy nhất: retention/platform policy không đủ cho DB recovery.
- Public bucket: vi phạm data protection.
- Provider backup only: không thay thế portable encrypted `pg_dump` restore evidence.