# 05. Release, Rollback và Evidence

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DEP-CFG-05`
> **Phiên bản:** v2.0
> **Ngày cập nhật:** 09/11/2026
> **Trạng thái:** Baseline đã đối chiếu `docs-common/` — chưa có bằng chứng runtime
> **Nguồn chuẩn:** [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] (§7), [[digitalbanking-docs/docs-common/05. Technical stack.md]], [[digitalbanking-docs/docs-common/07. API contracts.md]], [[digitalbanking-docs/docs-common/09. Test plan.md]]

---

## 1. Release flow (`08` §7.1)

```text
Change proposed
  -> document/API/design review
  -> OpenAPI validation
  -> unit and integration tests
  -> concurrency/failure/security checks
  -> package artifact
  -> image/deployment validation
  -> demo deployment
  -> health check
  -> evidence capture
  -> acceptance review
```

---

## 2. Release gates (`08` §7.2)

| # | Gate | Evidence bắt buộc | Khi fail |
|---:|---|---|---|
| 1 | Requirement traceability | Mapping `BR/FR/NFR/AC` | Quay lại review tài liệu |
| 2 | API contract | OpenAPI validation và contract check | Dừng release; sửa contract nguồn trước |
| 3 | Database migration | Clean migration trên DB trống; review compatibility | Dừng release; không sửa migration đã áp dụng |
| 4 | Automated test | Unit, integration, critical-path report | Dừng nếu Must test fail |
| 5 | Security | Security checklist và scan result | Dừng nếu còn critical issue chưa xử lý |
| 6 | Container/package | Artifact reproducible, có version identifier | Rebuild; không deploy artifact mutable |
| 7 | Deployment smoke test | Health, API reachability, basic business flow | Rollback application artifact |
| 8 | Evidence package | Command, environment, expected, actual, timestamp | Đánh dấu acceptance incomplete |

---

## 3. Evidence package (`08` §7.4)

Mỗi release/demo cần:

- Source revision identifier.
- Configuration version **không chứa secret**.
- Migration version.
- OpenAPI validation result.
- Test report và known limitations.
- Deployment health result.
- Backup timestamp và restore-verification status.
- Quyết định/kết quả rollback nếu có.

---

## 4. Rollback policy (`08` §7.3)

1. **Application rollback:** quay về artifact bất biến đã được nghiệm thu trước đó.
2. **Không rollback database** bằng cách sửa/đảo migration đã áp dụng.
3. Nếu schema/data compatibility bị ảnh hưởng: dừng traffic hoặc đưa hệ thống vào maintenance có kiểm soát, bảo toàn evidence, restore từ backup đã duyệt khi cần.
4. Sau rollback, chạy lại health, schema compatibility, API contract và financial invariant check.
5. Chỉ retry request chưa rõ kết quả với **cùng idempotency key**.
6. Không bao giờ đánh dấu transfer `COMPLETED` thủ công khi thiếu ledger/audit evidence.

### 4.1. Forward compensating migration

Nếu phải sửa schema sau release lỗi, dùng migration mới đã được review. Không xóa/sửa cột/bảng có dữ liệu giao dịch bằng cách chỉnh migration cũ.

---

## 5. Điều kiện trước khi release

- Java 21 LTS; Spring Boot 4.x/3.5.x; PostgreSQL 16.x; Flyway 11.x; Gradle Kotlin DSL.
- Không có secret trong Git, image, test report hoặc evidence.
- `banking_app` là runtime role; `banking_migrator` chỉ dùng cho migration.
- `application.yml` fail-fast khi thiếu hoặc sai secret bắt buộc.
- Migration chạy sạch trên DB trống và `ddl-auto: validate` không phát hiện lệch.
- Các giá trị `Open` ảnh hưởng security/money/retention đã được chốt hoặc release được đánh dấu rõ là chưa đủ điều kiện nghiệm thu.

---

## 6. Điều cấm

- Không dùng `latest` cho image/artifact release.
- Không sửa migration đã áp dụng.
- Không bỏ qua audit để làm release "xanh".
- Không tắt invariant tài chính để chạy demo.
- Không tuyên bố `PASS`/`VERIFIED` khi chưa có evidence runtime.

---

## 7. Kiểm chứng

| ID | Kịch bản | Kỳ vọng | Trạng thái |
|---|---|---|---|
| `DVT-009` | Release/rollback review | Artifact, migration, rollback và evidence gates được định nghĩa | **Not verified** |
| `DVT-006` | Failure/recovery | Không false success/duplicate debit | **Not verified** |
