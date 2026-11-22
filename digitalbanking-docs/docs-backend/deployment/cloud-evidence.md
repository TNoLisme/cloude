# Cloud Evidence Package

> **Mã:** BE-CLOUD-15  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed
> **Applies to:** Option B — AWS Managed Core
> **Source decision:** `digitalbanking-docs/docs-common/12. Cloud Deployment & Runbook.md`

## 1. Evidence directory

```text
evidence/cloud/<utc-date>-<release-sha>/
  README.md
  manifest.json
  deployment/
    provider-resources.txt
    pricing-snapshot.md
    firewall.txt
    dns-tls.txt
    tls-renewal.txt
    docker-versions.txt
    migration.txt
    health.txt
  frontend/
    build.txt
    static-deploy.txt
    spa-routing.txt
    browser-smoke.txt
  tests/
    openapi.txt
    backend.txt
    frontend.txt
    smoke.txt
    dvt.txt
    d1-d5-summary.md
  backup/
    backup-manifest.json
    checksum.txt
    restore-result.txt
  screenshots/
  video/
  known-limitations.md
```

Artifact lớn có thể ở CI/object storage. Manifest ghi URL, retention và checksum, không ghi secret.

## 2. Manifest template

```json
{
  "target": "cloud-deployment",
  "environment": "demo",
  "provider": "AWS",
  "services": ["EC2", "RDS", "S3", "ECR", "Secrets Manager", "CloudWatch", "SSM"],
  "region": "<region>",
  "deployedAtUtc": "<timestamp>",
  "frontendCommit": "<sha>",
  "backendCommit": "<sha>",
  "openApiVersion": "<version>",
  "artifactDigest": "<ecr-digest>",
  "migrationVersion": "<version>",
  "databaseService": "Amazon RDS PostgreSQL 16",
  "backupStorage": "Amazon S3",
  "databaseMajorVersion": "16",
  "dataClassification": "synthetic",
  "status": "Pass|Fail|Blocked",
  "reviewer": "<role-or-id>"
}
```

## 3. Required records

- Provider/service/region.
- Pricing URL, plan, timestamp, estimate and billing alert threshold.
- Resource size và billing unit.
- FE/BE commit SHA.
- OpenAPI version.
- Artifact digest.
- Redacted config version.
- Migration version.
- Commands/tool versions.
- Expected/actual/timestamp.
- Health/smoke output.
- Backup checksum.
- Restore result.
- DVT/D1–D5 status.
- Cost estimate and region-specific pricing snapshot from [cloud-cost-estimate.md](cloud-cost-estimate.md).
- Option deployed: `A-VPS`, `B-AWS` or `C-Cost-minimized`.

## 4. Redaction checklist

Không lưu password, OTP, session/cookie/token, AES/HMAC key, full account number, raw customer PII, private SSH key hoặc DB URL chứa credential.

## 5. Evidence rules

- Gắn evidence với commit và test ID.
- Ghi environment và provider.
- Tách `Mock`, `API`, `Browser`, `Integration`, `Cloud`.
- Source review không phải runtime verification.
- Health pass không phải business acceptance.
- Blocked test giữ lý do và reopen trigger.
