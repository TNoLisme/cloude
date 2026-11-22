# Cloud CI/CD và Artifact Decision

> **Mã:** BE-CLOUD-09  
> **Ngày:** 22/11/2026  
> **Trạng thái:** Proposed

## 1. Pipeline

```text
Pull request
  -> OpenAPI validation
  -> Backend tests
  -> Frontend lint/typecheck/unit/build
  -> Docker build
  -> Secret/dependency/image scan
  -> Review/approval

Release tag
  -> Push immutable image to Amazon ECR using GitHub OIDC
  -> Record commit SHA/digest
  -> Build Frontend static artifact using repository package manager/scripts
  -> Publish FE artifact to controlled Nginx deploy path
  -> Backup target DB
  -> Deploy Backend digest to VM
  -> Flyway
  -> Configure SPA fallback and cache headers
  -> Health check
  -> FE/API smoke test
  -> Evidence
```

## 2. Candidates

| Option | Decision |
|---|---|
| GitHub Actions + GitHub OIDC | Baseline; source/review/PR ở GitHub, AWS access dùng temporary IAM role |
| GitLab CI | Thay thế nếu repository chuyển GitLab |
| Cloud Build/CodeBuild | Không cần thêm CI provider; AWS CodeBuild chỉ cân nhắc nếu native AWS runner là requirement |
| Jenkins | Không chọn; cần CI server riêng |

### Registry decision

Chọn **Amazon ECR** thay GHCR cho AWS core:

- EC2 pull image bằng Instance Profile.
- Không lưu registry password/token dài hạn trên VM.
- Private repository, digest và image scan nằm trong AWS evidence.
- ECR tích hợp IAM/Security Group/VPC endpoint path.

GHCR là fallback nếu cần provider portability hoặc AWS ECR access gây blocker.

## 3. Artifact rules

- Không deploy `latest`.
- Image có commit SHA tag.
- Deployment dùng immutable digest.
- JAR/image checksum ghi evidence.
- Build reproducible từ commit đã review.
- Runtime config ngoài image.

## 3.1. Frontend deployment contract

- Use package manager and build script from actual Frontend repository; do not assume `pnpm` or another toolchain from stale stack text.
- Publish `dist/` or actual build output to versioned release directory.
- Nginx serves the selected release directory.
- SPA fallback uses `try_files $uri $uri/ /index.html` or equivalent tested rule.
- HTML must not receive long immutable cache; hashed static assets may receive immutable cache headers.
- `/api/v1/**` is proxied to Backend, not served by SPA fallback.
- FE rollback switches Nginx symlink/path to previous verified release.
- Verify asset loading, direct route, reload, API proxy, cookie flags and console errors.

## 4. Required checks

- Backend `./gradlew clean check`.
- Frontend scripts thực tế: lint/typecheck/test/build.
- OpenAPI validation.
- Docker build.
- Secret scan.
- Dependency/image scan.
- Migration clean install.
- Artifact smoke.

## 5. Deployment authentication

- GitHub Actions dùng OIDC trust policy giới hạn repository, branch/tag và environment.
- Không lưu `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` dài hạn trong GitHub.
- EC2 Instance Profile pull ECR image và read required AWS resources theo least privilege.
- AWS Systems Manager Session Manager là admin channel baseline; không cần public SSH `22/tcp`.
- CI không echo secret.


## 6. Rollback

Rollback application bằng digest trước đó đã verified. Không sửa migration đã chạy. Schema incompatible thì forward migration hoặc restore procedure đã duyệt.

## 7. Evidence

Ghi workflow URL/run ID, commit SHA, image digest, tool versions, test summary, scan summary, deploy timestamp và health/smoke output.
