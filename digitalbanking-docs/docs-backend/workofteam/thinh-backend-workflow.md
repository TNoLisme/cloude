# Quy trình và thứ tự task Backend của Thịnh

> **Dự án:** Digital Banking MVP; **Mã tài liệu:** BE-WORK-THINH-01  
> **Phiên bản:** 0.5; **Ngày:** 15/11/2026; **Baseline:** `c570986` + Common implementation trên nhánh `dev`
> **Người đề xuất:** Thịnh; **Reviewer đề xuất:** Đạt, Vũ, Phương  
> **Trạng thái:** Draft — chờ review; implementation task chưa đóng  
> **Nguồn:** [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]]; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]]; [[digitalbanking-docs/docs-common/09. Test plan.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]].

## 1. Scope sau Common merge

Common đã triển khai baseline cross-cutting: config/time, errors, request context/correlation, JCS/HMAC/PII crypto, HTTP security, observability và quality gates. Các module khác phải reuse Common; không copy primitive riêng.

Common **chưa** cung cấp identity production adapter, customer/account/transfer/ledger/fraud/admin/notification Java implementation, session persistence, CSRF persistence, ownership/maker-checker transaction hoặc distributed quota. Common tests/harness không thay consumer integration.

State của Common:
- COM-IMP-01..04/06: Done trong scope implementer, chờ review.
- COM-IMP-05/07/08: Blocked một phần do thiếu consumer/identity/Cloud.
- Reviewer/sign-off: chưa ghi nhận.
- Release: NO-GO cho đến khi consumer integration/quality gates đạt.

## 2. Ownership

Thịnh owner `audit`, `notification`, `fraud`; phối hợp fraud/audit trong `admin`. Đạt owner `identity`, `customer`, `account`, `admin`; Vũ owner `transfer`, `limit`; Phương owner `ledger`, `common`.

Code: `digitalbanking-backend/src/main/java/com/simulatedbank/banking/<module>/`. Tests package tương ứng. Docs: `digitalbanking-docs/docs-backend/modules/<module>/`. README chỉ hướng dẫn/link.

## 3. Task order

| Order | Task | Output | Integration dependency |
|---|---|---|---|
| 01 | BE-AUD-01 writer | Validator/action catalog/hash/writer/migration tests | Common JCS/context/error; identity/transfer callers |
| 02 | BE-AUD-02 query/export/verify | REST/query/export/verifier | AUD-01 + identity/RBAC |
| 03 | BE-NOT-01 outbox | T2 enqueue/T3 worker/retry | Common context/metrics/PII + transfer/identity |
| 04 | BE-FRD-01 fraud | Rule engine/flag/review | Common config/context/error + transfer/limit/identity |
| 05 | BE-ADM-01 collaboration | Admin fraud/audit integration | Common security + Đạt admin/identity |
| 06 | BE-AUD-03 retention | Hold/checkpoint/dry-run | AUD-01/02 + admin approval |
| 07 | BE-AI-01 feasibility | Synthetic offline study | FRD baseline; no MVP block |

Specs có thể viết trước. Component code có thể làm với fake. Không claim Integrated/Verified khi consumer thật chưa chạy.

## 4. Workflow mỗi task

### 4.1. Discovery và contract gate

1. Đọc `docs-common/01–11`, design, OpenAPI, Backend conventions, Common CURRENT-STATE/TKCT và source code Common.
2. Đọc task spec, verification report, handoff `.claude/docs/project_xcreative(datahub+xvideo)/` nếu có.
3. Chạy `git status --short --branch`, xem staged/unstaged/untracked trước sửa. Không reset, stash, checkout hoặc xóa thay đổi user.
4. Phân loại capability đã có trong Common, capability cần consumer owner, gap/open issue.
5. Chốt task contract: mục tiêu, input/output, in-scope, out-of-scope, owner dependency và Definition of Done.

### 4.2. Docs-first và decision register

1. Viết/cập nhật spec: input/output, owner, dependency, transaction, error, security, PII, test, non-goals.
2. Đối chiếu nguồn theo thứ tự: `docs-common/01–11` → `docs-common/design/` → `docs-backend/`; ghi rõ conflict, không tự nâng proposal thành policy.
3. Mỗi quyết định còn mở phải có: owner, bằng chứng, hướng đề xuất, lý do, trade-off, acceptance evidence và điểm dừng.
4. Không sửa Common/docs-common/OpenAPI để né conflict nếu task yêu cầu chỉ làm component; ghi conflict trong report.
5. Tạo handoff task trong `.claude/docs/project_xcreative(datahub+xvideo)/` cho task lớn; cập nhật sau mỗi milestone.

### 4.3. Independent implementation gate

1. Tách phần làm được không cần owner: domain, validator, DTO nội bộ, query, pure evaluator, adapter, disposable fixture.
2. Code core/domain/validator/SQL với test trước; giữ API port nhỏ. Không duplicate Common.
3. TDD bắt buộc: viết RED test, chạy xác nhận failure đúng nguyên nhân, implement tối thiểu, chạy GREEN, refactor sau GREEN.
4. Không expose public controller, identity/RBAC, caller port hoặc production migration contract khi owner chưa review.
5. Test historical/security data fail-closed; không log/echo PII, secret, raw payload.

### 4.4. Integration verification gate

1. Chạy targeted unit/component tests trước.
2. Chạy Testcontainers PostgreSQL cho transaction/lock/constraint/permission/tamper nếu task có DB.
3. Chạy caller thật chỉ khi module owner/schema/port đã tồn tại; fake caller chỉ chứng minh component mapping.
4. Đọc JUnit XML để lấy test count/failures/errors/skips; không dùng số từ run cũ.
5. Ghi environment, revision, command, expected, actual, artifact, status và limitation trong verification report.
6. Chạy `git diff --check`; phân biệt warning LF/CRLF với whitespace failure.
7. Nếu Docker/dependency lỗi, báo BLOCKED và tách unit evidence khỏi integration evidence; không gọi task pass.

### 4.5. Review, staging và commit gate

1. Review full diff theo staged và unstaged riêng; không để implementation mới chỉ nằm unstaged rồi claim đã bàn giao.
2. Kiểm tra file ownership; không trộn task/module khác vào commit.
3. Tách commit logical units: backend code/tests riêng, docs/report/handoff riêng khi user yêu cầu commit.
4. Chỉ `git add` khi user cho phép hoặc cần đánh dấu conflict resolution; sau stage phải báo status.
5. Không tự chạy `git commit`, `git push`, tạo PR hoặc force operation.
6. Trước commit, xác nhận không có secret, debug log, temp artifact, unrelated docs, generated output.

### 4.6. Status gate và handoff

Dùng đúng trạng thái:

- **Planned:** chưa có implementation/evidence.
- **Component Verified:** independent component và targeted evidence pass; owner integration còn mở.
- **Integration Blocked:** component pass nhưng identity/caller/schema/policy/owner evidence thiếu.
- **Done:** Definition of Done, caller integration, security/API contract, fresh evidence và owner sign-off đạt.

Không gọi task `Done` chỉ vì test component pass. Report phải liệt kê phần đã verified, phần blocked, owner decision và next command/file.

### 4.7. Parallel-agent gate

1. Mỗi agent sở hữu module/thư mục/docs riêng; không cùng sửa Common, OpenAPI, build config, migration version hoặc shared fixtures nếu chưa phân công.
2. Trước chạy song song, ghi ownership matrix, migration version reservation, shared API assumptions và file conflict list.
3. Agent giao tiếp qua handoff/message: contract, files changed, tests, blockers, decisions needed. Không tự sửa phần agent khác.
4. Chạy Gradle/test integration lần lượt khi dùng cùng checkout/build output; không lấy XML bị agent khác ghi đè.
5. Merge theo thứ tự dependency: component trước, integration sau; review diff và rerun targeted suite sau merge.
6. Fake integration không thay real owner integration; parallel progress không đóng task owner-dependent.

Output task vẫn phải gồm: implementation, tests, verification report, limitation/owner register, handoff và Git status. README/index chỉ bổ sung link; giữ nội dung chung cũ.

### 4.8. AUD-02 checkpoint — 15/11/2026

| Gate | Hiện trạng | Còn thiếu trước Done |
|---|---|---|
| Discovery/contract | Spec AUD-02 v0.3, Common/API/OpenAPI đối chiếu; D1–D7 ghi proposal | Owner review/sign-off; không sửa source chung đơn phương |
| Independent component | Query/detail/verifier có code và tests; hash v1 vẫn proposal | Review diff và compatibility/security edge cases; không gọi toàn task Done |
| Verification report | Report có matrix và evidence run 14/11/2026: 153 tests | Bổ sung fresh run 15/11/2026 nếu dùng làm evidence chuẩn bị commit; giữ evidence lịch sử và không ghi đè kết quả owner khác |
| DB integration | Fresh targeted suite 15/11/2026: `BUILD SUCCESSFUL`; 153 tests, 0 failures/errors/skips; disposable PostgreSQL Testcontainers | Ghi command, môi trường, revision/working-tree và test scope vào report; không dùng test run này để claim public API/RBAC |
| Integration | Public controller, export, real identity/RBAC/data scope, C3 chưa triển khai | Chốt D1–D5; kiểm tra actual callers và API/security contracts |
| Verifier operations | Component scan theo upper watermark và payload/column comparison | D6 job/checkpoint/anchor; không claim tail-truncation hoặc post-purge verification |
| Commit preparation | 7 AUD-02 source và 5 AUD-02 test files hiện untracked; AUD-02 docs modified; Fraud/Notification docs cũng modified | Phân loại đúng file; review untracked source/test bằng cách đọc trực tiếp vì `git diff` không hiển thị; stage khi user cho phép, xem cached diff và không gom module khác |

Fresh test command scope: `AuditQueryContractTest`, `AuditQuerySqlTest`, `AuditQueryJdbcStoreIT`, `AuditDetailProjectionTest`, `AuditQueryServiceTest`, `AuditChainVerifierTest`, `AuditWriterIT`, `AuditEventValidatorTest`, `AuditSnapshotContractTest`, `AuditCanonicalJsonTest`, `AuditHashingTest`, `ModuleBoundaryArchTest`; `--rerun-tasks --no-daemon`. Lệnh đầy đủ và evidence 14/11 ở AUD-02 report; run 15/11 chưa được ghi vào report tại thời điểm cập nhật workflow này.

Kết luận: AUD-02 **Component Verified / Integration Blocked**, không phải `Done`. Commit component có thể chuẩn bị độc lập sau diff review và test evidence; release/merge acceptance vẫn cần owner review.

## 5. Mapping Common reuse

| Need | Reuse | Không làm lại |
|---|---|---|
| JCS | `common.crypto.JcsCanonicalizer` | Local JSON canonicalizer |
| SHA/HMAC | `common.crypto.HmacService` | Duplicate crypto primitive |
| PII | `PiiMaskingUtils` + module allowlist | Plaintext fallback |
| Correlation | `RequestContextHolder` | Module ThreadLocal |
| Errors | `ErrorCode`, `BankingBusinessException`, Problem writer | Duplicate public error catalog |
| Security | Common filter chain + identity SPI | Fake production authentication |
| Config/time | `AppProperties`, `TimeConfig`, deployment configuration | Hard-coded thresholds |
| Logging/metrics | Common encoder/context/low-cardinality rules | PII/high-cardinality tags |

## 6. Updated AUD-01 rule

Audit-local `AuditCanonicalJson` is an audit-specific Java-value validation adapter that delegates RFC 8785 canonicalization to Common `JcsCanonicalizer`; it is not a second JCS implementation. Audit-owned hash protocol—payload fields, hash_version, delimiter, previous hash and chain lock—still requires audit/architecture review. Common JCS reuse does not approve the hash protocol.

Audit event uses Common context at integration. Unit tests may pass explicit IDs. Identity supplies actor/principal/authority; audit does not authenticate or authorize.

## 7. Known limitations

- Global audit lock may serialize C1/C2 writes; benchmark before TPS claim.
- Common JCS is available, but audit protocol/hash_version remains open.
- Fraud evaluator unit tests do not prove caller query/count correctness.
- Notification remains at-least-once attempts; duplicate/failure/polling delay accepted MVP limits.
- Purge disabled shared/demo/production; checkpoint requires independent verification.
- Common HTTP/security test harness does not prove real identity/session/CSRF/ownership.
- Process-local rate limit is not distributed quota.
- No Cloud/TLS/ingress/release readiness claim.

## 8. Conflict/staging rule

If `git stash pop` conflicts after Common merge:
- Preserve Common version-catalog build as base.
- Convert old direct dependencies to `libs.*` aliases.
- Keep JCS as `libs.jcs`; remove duplicate direct JCS declaration.
- Keep Spring Security/JPA/validation/JaCoCo/readiness/test dependencies from Common.
- Resolve conflict, run targeted build/tests, then `git add` only to mark resolution.
- Stop and report staged state. Do not commit/push automatically.

## 9. Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial docs-first workflow |
| 0.2 | 11/11/2026 | Limitations/evidence boundaries |
| 0.3 | 11/11/2026 | Core/API flexibility before owner review |
| 0.4 | 12/11/2026 | Aligned all task flow with merged Common implementation |
| 0.5 | 15/11/2026 | Added discovery/decision/independent-component/integration/commit/status/parallel-agent gates and evidence-first handoff rules |