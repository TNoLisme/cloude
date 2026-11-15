# BE-AUD-02 — Verification report

> **Trạng thái:** Partial component — query/detail/verifier hoàn thiện độc lập; public API/export/RBAC blocked
> **Task spec:** [BE-AUD-02-query-export-verification.md](BE-AUD-02-query-export-verification.md)

## Verification matrix

| Check | Lệnh / thủ tục | Expected | Actual | Status |
|---|---|---|---|---|
| Audit search/detail contract | Chạy unit/integration tests liên quan | Schema/filter/paging/permissions khớp OpenAPI | Service/DTO nội bộ, projection an toàn và JDBC store đã hoàn thiện; public controller chưa bật | Partial component |
| Export bounds/format | Gọi CSV/JSONL và vượt `maxRows` | Format đúng; vượt mức trả 413, không cắt thầm | Chưa triển khai; D2/D3 còn mở | Blocked |
| Export audit-before-response | Inject lỗi audit và quan sát response stream | Lỗi audit không gửi file bytes | Chưa triển khai | Blocked |
| PII and RBAC | Test synthetic fixtures với role được/không được phép | Mask đúng; 401/403 đúng; không lộ SQL/PII | Identity/public API chưa triển khai | Blocked |
| Chain verifier | Testcontainers: valid, tamper, unknown version | Valid pass; mismatch/unsupported phát hiện và cảnh báo theo contract | Bounded DB scan, sequence gap, non-monotonic occurred_at, missing middle row, batch boundary và tamper detection pass trên disposable PostgreSQL; checkpoint/anchor and owner approval remain open | Partial component |

## Current implementation evidence — 14/11/2026

| Component | Files | Evidence |
|---|---|---|
| Query filter contract | `AuditSearchQuery`, `AuditQueryContractTest` | Defaults, independent date bounds, 366-day window, page/size, sort whitelist, safe filters, long offset; targeted test pass |
| Query SQL boundary | `AuditQuerySql`, `AuditQuerySqlTest` | Fixed projection, parameterized predicates, whitelist sort, bounded LIMIT/OFFSET; targeted test pass |
| Safe detail projection | `AuditDetailProjection`, `AuditDetailProjectionTest` | Read-time snapshot and client info safe projection; strictly enforces entity allowlist across 14 entity types, blocking PII (customerName, accountNo, etc.); enforces nested schema allowlist (only `FRAUD_FLAG.matchedValue` with keys `amount`, `count`, `window` is permitted, rejecting unapproved nested objects/keys fail-closed); enforces numeric projection precision (rejects floating-point numbers, scientific notation, unsafe/exceeding integers, and money as JSON numbers; preserves exact decimal strings like "1.0" and safe integer values without precision alteration); enforces resource bounds (<= 32 KiB UTF-8, depth <= 6, total nodes <= 256); fail-closed on malformed JSON or unmasked IP/secrets; targeted test pass |
| Internal query service & DTOs | `AuditQueryService`, `AuditQueryServiceTest` | Maps to OpenAPI-aligned DTO views (AuditPageView, AuditSummaryView, PageMetaView, AuditDetailView with flattened fields matching OpenAPI allOf: AuditSummary); verified with Jackson serialization contract test; deterministic asOf; maps missing records and negative IDs to BankingBusinessException(ErrorCode.AUDIT_NOT_FOUND); targeted test pass |
| Disposable PostgreSQL query | `AuditQueryJdbcStore`, `AuditQueryJdbcStoreIT` | Deterministic asOf via Clock, strict validateHash (lowercase hex 64, no silent trimming/lowercasing of corrupted data), same-timestamp ID tie-break, reference+correlation AND filter, safe detail projection, rollback sequence gap test (PASS), non-monotonic occurred_at test (PASS), batch boundary verifier test (PASS), missing middle row deletion test (MISMATCH); Testcontainers pass |
| Verifier v1 proposal | `AuditChainVerifier`, `AuditChainJdbcVerifier`, `AuditChainVerifierTest` | Full disposable PostgreSQL scan by audit_id watermark, bounded batch (default 1,000, configurable), payload/column comparison, missing middle row, tamper detection, sequence gap, and malformed/unsupported version tests pass. Hash protocol remains proposal; no checkpoint/anchor/purge integration |
| Regression | `AuditWriterIT`, `ModuleBoundaryArchTest` | Targeted query + writer + architecture command pass after all changes |

Fresh targeted command:

```text
gradlew.bat -p digitalbanking-backend test --tests '*AuditQueryContractTest' --tests '*AuditQuerySqlTest' --tests '*AuditQueryJdbcStoreIT' --tests '*AuditDetailProjectionTest' --tests '*AuditQueryServiceTest' --tests '*AuditChainVerifierTest' --tests '*AuditWriterIT' --tests '*AuditEventValidatorTest' --tests '*AuditSnapshotContractTest' --tests '*AuditCanonicalJsonTest' --tests '*AuditHashingTest' --tests '*ModuleBoundaryArchTest' --rerun-tasks --no-daemon
```

Result: `BUILD SUCCESSFUL`; fresh combined `--rerun-tasks` on 14/11/2026; 153 tests, 0 failures/errors/skips from test XML. Combined set: `AuditQueryContractTest`, `AuditQuerySqlTest`, `AuditQueryJdbcStoreIT`, `AuditDetailProjectionTest`, `AuditQueryServiceTest`, `AuditChainVerifierTest`, `AuditWriterIT`, `AuditEventValidatorTest`, `AuditSnapshotContractTest`, `AuditCanonicalJsonTest`, `AuditHashingTest`, `ModuleBoundaryArchTest`. Common JCS standalone suite was not included in this run.

Verifier command:

```text
gradlew.bat -p digitalbanking-backend test --tests '*AuditChainVerifierTest' --no-daemon
```

Result: `BUILD SUCCESSFUL`; verifier remains proposal component, not production chain verifier.

Đã đối chiếu source tại revision `e59aeac` với SRS, 04/06/07/09, OpenAPI, audit/security design, Common TKCT/current state, identity/transfer/ledger/admin overview và BE-ADM-01.

- Sửa blocker JCS đã cũ: AUD-01 đã dùng Common; Common error codes và security endpoint gates có source hiện hữu.
- Bổ sung exact API mapping, separate date defaults, sort asc/desc, AND/reference filters, PageMeta và no-write read path. Không hỏi lại quyền export mặc định: 07 chỉ cho Auditor, không kế thừa Admin export từ design/04.
- Bổ sung safe read-time detail projection: fail-closed khi gặp JSON malformed, unmasked IP, hoặc secrets trong userAgent; chặn rò rỉ PII ở cả root và nested objects (chỉ mở nested `matchedValue` cho `FRAUD_FLAG` với keys `amount`, `count`, `window`; mọi nested object/key khác đều fail-closed); bảo toàn độ chính xác số học (chặn số thực dấu phẩy động, ký hiệu số mũ, số nguyên vượt giới hạn an toàn, và money dạng number; giữ nguyên chuỗi thập phân như "1.0" và số nguyên an toàn).
- Bổ sung `AuditQueryService` nội bộ: map ra DTO views phù hợp contract, trả `AUDIT_NOT_FOUND` an toàn, không expose public controller.
- Bổ sung verifier payload/column equality, exact v1 fields, version/type/null checks, rollback sequence gap hợp lệ, non-monotonic `occurred_at` hợp lệ, missing middle row phát hiện MISMATCH.
- Sửa nhận định snapshot ban đầu: current recorder lock trước INSERT/identity và giữ đến commit. Under agreed write protocol/no purge, committed watermark có thể dùng READ COMMITTED; không bắt buộc REPEATABLE READ vì race ID thấp commit muộn.
- Ghi rõ `SessionAuthenticator.Session` chưa có actorRole/scope đầy đủ; C3 security event chưa có dù 403 handler tồn tại. Không fake production actor.
- Đăng ký D1–D7 tại spec §8.1 với nguồn, đề xuất, lý do và evidence cần có. Facts đã phân xử khác với policy chưa duyệt.
- V2 missing-role fail-fast là đề xuất chưa được code thực hiện; không sửa migration trong lượt này.

### Checklist runtime bổ sung

| Check | Oracle / bằng chứng cần có | State hiện tại |
|---|---|---|
| Query/page/projection | Clock cố định, missing-date độc lập, AND/reference conflict, asc/desc tie, offset overflow, totals, states absent ở list; no audit insert | Query/filter/JDBC/detail/service tests pass on fresh disposable PostgreSQL run (153 tests); no public controller yet |
| Export membership | maxRows+1 probe, writer sau watermark/backdated time bị loại, batch bounded và không giữ advisory lock lúc generate | Chưa triển khai; phương án D2 cần review |
| Export bytes/hash | CSV header, JSONL null/types/micros, fixed encoding/newline, hash độc lập, formula policy | D3 chưa duyệt; chưa chạy |
| Export transaction | generation/insert/commit fail không phát bytes; no self-record; disconnect và orphan cleanup chỉ thư mục owned | Chưa triển khai |
| Verifier | valid/gap/backdated/key order; column-only tamper; missing/extra/null/types; unsupported/partial/empty result không báo full PASS | DB scan, column tamper, sequence gap, non-monotonic occurred_at, missing middle row, batch boundary pass trên disposable PostgreSQL. Proposal not owner-approved |
| Identity/scope/C3 | real authority/role/actor snapshot; denied in filter/service đúng một event; fallback giữ 403 khi audit fail | Blocked D4/D5 |
| Job/alert/checkpoint | lịch full/incremental, state/trust, restart, alert; không advance khi fail | D6 chưa duyệt; checkpoint/anchor ngoài slice hiện tại |

Docs checks: `git diff --check` trên hai file AUD-02 không báo whitespace error; Git cảnh báo LF sẽ chuyển CRLF khi Git chạm file. Kiểm 17 Markdown local links trong spec: 0 đường dẫn thiếu; không bao gồm wikilinks/anchors. `git status --short` trong repo cloude hiển thị các file source/test AUD-02 mới và hai file docs. Không Common/docs-common/OpenAPI changes trong lượt. Handoff local ở `.claude/docs/project_xcreative(datahub+xvideo)/digitalbanking-aud02.md` ngoài repo cloude.

## Open items

- Hash v1 formula/field set and golden vectors remain unapproved; verifier can only report component behavior against AUD-01 proposal.
- Query sort API permits `asc|desc`, while lower design gives descending default; implementation follows `occurredAt,desc` and honors explicit direction, with same-direction audit_id tie-break.
- Export upper-ID watermark depends on AUD-01 lock-before-identity-insert and no purge/write path outside protocol; validate concurrent normal/backdated writer before relying on READ COMMITTED batching.
- Exact CSV newline/escaping, formula-like cell policy, filterHash canonical input and manifest/signature headers require DB/Security/architecture review before bytes become persisted evidence.
- 10,000 rows can approach 313 MiB at 32 KiB canonical payload per row; row count limit does not bound bytes. Review disk/time/concurrent-export budget before enabling endpoint.
- Identity production principal, permission and data scope are absent; fake-principal tests do not verify RBAC integration.
- `SECURITY_ACCESS_DENIED` remains fail-closed in AUD-01; cannot claim unauthorized-access audit acceptance.
- Checkpoint/anchor and post-purge verification remain outside AUD-02.
- Migration V2 grants to `banking_app` only if role exists; no fail-fast role provisioning. Coordinate before deployment.

## Owner decisions

- **Phương / DB/Common:** hash protocol and golden vectors; payload-column comparison; migration/role provisioning; export watermark/resource invariant; exact export bytes and filterHash input. Proposal: Common JCS/SHA reuse; committed upper ID + bounded READ COMMITTED snapshot read followed by separate READ COMMITTED `AUDIT_EXPORTED` transaction, subject to writer-protocol test.
- **Đạt / Identity:** real principal, authorities, scope, actor snapshot and C3 denied-access fallback. Proposal: no public endpoint until real adapter and permission integration tests pass; default `AUDIT_EXPORT` only for Auditor per 01–11/07 baseline.
- **Vũ / Transfer/Ledger:** confirm real reference/correlation investigation examples when those module schemas/ports exist. Not blocking synthetic query tests; no join/enrichment before write-owner schemas exist.
- **Architecture/Security:** verifier scope, manifest/signature/key lifecycle, CSV safe-output policy, checkpoint/anchor and release criteria. Proposal: report internal-chain limits; no tail-truncation/post-purge guarantee without approved anchor/checkpoint.

`AuditChainJdbcVerifier` hiện có bounded DB scan và so payload/columns theo v1 proposal; test disposable DB phát hiện sửa amount và xóa row giữa. Chưa có checkpoint/anchor hoặc owner approval. Detail hiện có safe read-time projection, fail-closed an toàn; `AuditQueryService` nội bộ đã có unit tests. Chưa có public controller, export, C3 và identity integration. AUD-02 chưa hoàn tất; không coi partial component test là owner sign-off.
