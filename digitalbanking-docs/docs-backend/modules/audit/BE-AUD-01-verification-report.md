# BE-AUD-01 — Verification report

> **Dự án:** Digital Banking MVP; **Owner:** Thịnh  
> **Ngày:** 14/11/2026; **Trạng thái:** Component verified; task chưa hoàn thành  
> **Task spec:** [BE-AUD-01-audit-writer.md](BE-AUD-01-audit-writer.md)

## Verification matrix

| Check | Command/procedure | Expected | Actual | Status |
|---|---|---|---|---|
| Audit domain validation | `gradlew.bat test --tests '*AuditEventValidatorTest' --tests '*AuditSnapshotContractTest' --no-daemon` | Catalog, actor/outcome, entity allowlist, required fields, masking, bounds and money rules | 67 tests pass as part of full combined run | Verified component; catalog review gaps listed below |
| Common JCS/hash core | `gradlew.bat test --tests '*AuditCanonicalJsonTest' --tests '*AuditHashingTest' --tests '*JcsCanonicalizerTest' --no-daemon` | JCS vectors, payload formatting and hash fixture pass | 9 audit + 44 Common tests pass as part of full combined run | Verified component |
| PostgreSQL writer | `gradlew.bat test --tests '*AuditWriterIT' --no-daemon` | V1/V2 migration, write/read, MANDATORY TX, caller rollback, app-role mutation denial, timestamp precision, SQL NULL handling, consistency and isolation | 14 tests pass as part of full combined run; disposable PostgreSQL 16.14 | Verified disposable integration |
| Concurrent writer chain | Included in `AuditWriterIT` | 8 concurrent writers create one valid chain; business probe and audit insert same transaction | Pass in latest run | Verified disposable integration |
| DB permission | Included in `AuditWriterIT` | Runtime role cannot UPDATE/DELETE/TRUNCATE audit | Pass in latest run | Verified disposable integration |
| C1/C2 rollback | Included in `AuditWriterIT` | Caller business mutation and audit write rollback together | Pass in latest run | Verified component caller transaction |
| C3 semantics | Standalone transaction test | C3 failure does not rollback security state | Not implemented | Blocked identity/Common integration |
| Real caller integration | Transfer/identity/admin callers | Context, principal, replay and money-path invariants | No business caller implementation | Blocked |
| Performance benchmark | Concurrent writer/transfer workload | Lock wait/latency/pool metrics against team SLO | Not run; no SLO claim | Planned |

## Evidence

- Result at 14/11/2026 00:41Z after `--rerun-tasks`: `BUILD SUCCESSFUL`; 90 audit-owned tests (14 writer + 7 canonical + 40 validator + 27 snapshot contract + 2 hashing), 44 Common JCS tests and 5 architecture tests. Total 139; 0 failures/errors/skips.
- **Supported status note:** `RETENTION_PURGED` and `SECURITY_ACCESS_DENIED` fail closed until owner review. Unit test explicitly covers retention purge; access-denied entity remains unresolved and must not be used through this component.
- PostgreSQL Testcontainers covers caught JDBC insert rollback, lock timeout rollback, READ COMMITTED guard, payload/column equality, owner-level append-only triggers, runtime privileges, SQL NULL, microsecond timestamp, concurrent chain writes.
- Earlier red tests exercised catalog-required client context, retention purge fail-closed, normalization, malformed money, PII-shaped fields, fraud value typing, previous-hash format and transaction failure.
- Environment: Windows, JDK 21.0.13, Gradle 8.14.3, Docker 29.4.1, PostgreSQL 16.14 Testcontainers. Runtime Docker endpoint resolved `npipe:////./pipe/dockerDesktopLinuxEngine`.
- Migration: Flyway applied V1/V2 to disposable PostgreSQL database.
- Scope: component and disposable integration only. No identity/transfer/admin caller integration, C3 fallback, performance SLO or external anchor.

## 11. Phần độc lập đã triển khai và cách triển khai

### 11.1. Snapshot normalization thay vì lưu raw Java values

Audit normalize ID thành string, money thành fixed-two-decimal text và counter thành safe integer ngay khi `AuditEvent` immutable copy được tạo. Cách này khác thiết kế ban đầu chỉ allowlist/type-check nhưng để caller truyền `Long`/`BigDecimal` vào snapshot.

Ưu điểm:
- Hash và persisted `JSONB` nhận cùng representation.
- Không phụ thuộc Jackson/JSONB number rendering cho ID/money.
- Giảm precision drift, scientific notation và overflow giữa JVM/PostgreSQL.
- Reject giá trị không thể biểu diễn trong `NUMERIC(19,2)` thay vì âm thầm round.

Giới hạn: adapter audit-specific; không sửa Common; mapping nghiệp vụ và PII scanner đầy đủ vẫn cần review.

### 11.2. Common JCS/SHA qua adapter

`AuditCanonicalJson` giữ Java-value validation/depth và delegate RFC 8785 sang `common.crypto.JcsCanonicalizer`; `AuditHashing` dùng `common.crypto.HmacService.sha256Hex`. Không sửa Common implementation và không duy trì JCS algorithm thứ hai.

Ưu điểm: một implementation JCS dùng chung, duplicate-key/Unicode/scalar-root behavior tập trung, giảm algorithm drift. Giới hạn: adapter vẫn tồn tại; hash protocol chưa architecture-approved do source conflict.

### 11.3. Chain writer READ COMMITTED + global advisory lock

Writer yêu cầu READ COMMITTED trước advisory transaction lock. REPEATABLE READ có thể giữ snapshot cũ, đọc predecessor thiếu record commit trong lúc chờ và tạo chain fork. Global lock giữ insert order.

Ưu điểm: correctness rõ, rollback theo caller, không tạo protocol per-entity mới. Giới hạn: global lock giới hạn throughput; chưa có SLO benchmark.

### 11.4. Fail-closed C1/C2

`recordInTransaction` chỉ nhận C1/C2; C3/C4 reject trước DB lock. Đây là guard, không phải C3 implementation. Caught validation/JDBC/lock failures mark transaction rollback-only.

### 11.5. Verification độc lập

Coverage gồm cyclic/deep/expanded snapshot, UTF-8 32 KiB, ID/money/counter normalization, user-agent/control/secret-shaped input, typed fraud values, timestamp precision, SQL NULL, canonical row consistency, caught insert failure, lock timeout, isolation guard, append-only trigger và least-privilege checks. Số test chỉ lấy từ XML fresh combined run.

## 12. Items cần owner/architecture chốt

### 12.1. Phương — Common/DB/Crypto

**Cần chốt:** hash v1 và golden vectors; mâu thuẫn `docs-common/04 §4.6.1` với `design/01 §7.2–7.3`/`06`; canonical field/null/type policy; migration number; schema owner; role provisioning; context/error mapping.

**Hướng Thịnh đề xuất:** dùng protocol v1 đang chạy: `SHA-256(UTF8(JCS(canonical_payload)) || "|" || prev_hash)`, `hash_version` nằm một lần trong payload, genesis 64 zero. Giữ `canonical_payload JSONB` cho verifier nhưng hash source là canonical UTF-8, không phải `JSONB::text`. Runtime chỉ SELECT/INSERT + sequence usage; migration fail-fast khi role thiếu.

**Lý do:** payload tự mô tả version; verifier độc lập; tránh nối hash version hai lần; tránh deploy thành công giả khi app thiếu quyền.

**Bằng chứng cần review:** golden vectors, Common JCS tests, disposable migration, privilege matrix, error mapping.

### 12.2. Đạt — Identity/Admin

**Cần chốt:** principal/actor source; role snapshot; anonymous/system representation; `actor_user_id` FK và migration ordering; admin approval fields; `SECURITY_ACCESS_DENIED` entity động; C3 lockout/logout/access-denied; public `AuditModuleApi`.

**Hướng đề xuất:** Audit không tin actor/role request body; lấy từ authenticated identity context. Giữ FK deferred trong component migration. Tách C3 `REQUIRES_NEW`/security-log fallback khỏi C1/C2 MANDATORY writer.

**Lý do:** chống spoof actor; không phụ thuộc schema chưa tồn tại; security control không rollback khi audit best-effort fail.

**Bằng chứng cần review:** identity adapter/authorization tests, C3 failure test, FK migration test.

### 12.3. Vũ — Transfer/Ledger/Fraud money path

**Cần chốt:** T1/T2 boundary; audit insert cuối transaction; transfer before/after/outcome/timing; replay/idempotency/reconciliation; lock order/isolation; fraud C2 fields và C4 error behavior.

**Hướng đề xuất:** C1/C2 MANDATORY; audit failure rollback business; retry không tạo duplicate state transition; global audit lock sau business writes; fraud flag C2 cùng T2; engine error C4 không qua atomic writer.

**Lý do:** không trả COMPLETED trước audit commit; retry giữ idempotency; không trộn C4 với money atomicity.

**Bằng chứng cần review:** transfer integration, failure matrix, replay, concurrent account/limit/fraud workload.

### 12.4. Architecture owner — cross-cutting

**Cần chốt:** hash source priority, public port boundary, retention/checkpoint/external anchor, throughput SLO và release criteria.

**Hướng đề xuất:** approve C1/C2 component trước; defer C3/C4 và external anchor đến khi identity/transfer contracts tồn tại; benchmark global lock trước optimization.

**Lý do:** không chốt protocol/migration/API đơn phương; không claim tamper-proof/capacity thiếu evidence.

## 13. Definition of done hiện tại

AUD-01 chỉ Done sau owner review source conflicts, migration/roles coordination, public port review, C1/C2 caller integration, C3 design, fresh combined report và xử lý critical catalog/PII gaps. Hiện trạng: component work advanced; owner-dependent integration blocked.

## Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial writer/hash/transaction proposal |
| 0.2 | 11/11/2026 | Global-lock/JCS limitations |
| 0.3 | 12/11/2026 | Component implementation and unresolved integration status |
| 0.4 | 12/11/2026 | Aligned spec with Common implementation and merged branch dependencies |
| 0.5 | 13/11/2026 | Independent hardening, normalization, rollback/isolation coverage and owner decision register |
