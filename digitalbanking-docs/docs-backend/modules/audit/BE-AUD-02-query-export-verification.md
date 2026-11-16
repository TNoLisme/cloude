# BE-AUD-02 — Audit query, export và verification

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-AUDIT-02; **Task:** BE-AUD-02  
> **Phiên bản:** 0.3; **Ngày:** 14/11/2026
> **Trạng thái:** Draft — query/verifier component đang triển khai; export/public integration blocked
> **Owner:** Thịnh; **Reviewer đề xuất:** Đạt (identity/RBAC), Phương (common/DB); chưa sign-off  
> **Nguồn:** [[digitalbanking-docs/docs-common/07. API contracts.md]] §8.6; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]; [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §7, §9, §13; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]].

## 1. Mục tiêu và Common integration

FR-036–038, AC-020–022, NFR-021: search/detail/export audit đã mask, verify hash chain. Phạm vi implementation trong AUD-02 là component và disposable integration; không coi là RBAC/end-to-end verified trước identity integration.

Reuse Common đã có trong code:
- `JcsCanonicalizer` cho filter hash và canonical payload verification. Filter-hash field/byte contract remains unapproved.
- `HmacService.sha256Hex` cho SHA-256 bytes nhỏ. Source hiện chưa có incremental/stream API; với export lớn, đánh giá JDK `MessageDigest`/`DigestOutputStream` adapter trong audit, không `readAllBytes`, không sửa Common, không tự viết thuật toán SHA. Golden tests nhỏ phải đối chiếu Common với đúng bytes.
- `RequestContextHolder`/correlation filter cho response trace; `Clock` UTC từ Common cho default thời gian.
- Common Problem writer; source `ErrorCode` đã có `AUDIT_NOT_FOUND` (404), `EXPORT_TOO_LARGE` (413), `AUTH_FORBIDDEN` (403), `AUDIT_WRITE_FAILED` (500), `VALIDATION_ERROR`, `INTERNAL_ERROR`, `SERVICE_UNAVAILABLE`. Không thêm catalog lỗi public riêng.
- Security chain/CSRF/CORS/headers/no-store từ Common; identity vẫn phải provide real principal/permission.

Không tạo JCS, Problem, correlation hoặc authorization duplicate trong audit. Common primitives không cung cấp identity, data scope, audit transaction, export storage hay chain protocol.

## 2. Scope và dependency

Phụ thuộc AUD-01 writer/hash protocol. Không bao gồm retention/purge, external anchor/WORM, identity implementation hoặc thay đổi OpenAPI. Mock principal chỉ dùng component test; không claim RBAC integration.

### 2.1. Có thể triển khai độc lập

- Immutable query/filter DTO và strict validation.
- Parameterized JDBC query với explicit projection, bounded `LIMIT` và stable tie-break `(occurred_at, audit_id)` theo chiều sort.
- Search/detail service trên synthetic/disposable PostgreSQL data.
- CSV/JSONL fixed projection, masking, bounded rows và cleanup có thể được đặc tả/test bằng fixture. Chốt byte contract tại D3 trước khi dùng file/filter hash như protocol lưu bền vững.
- Component chain verifier cho hash v1 proposal, gồm payload/column consistency checks. Không cần chờ public port của module khác để thử component nội bộ audit.

### 2.2. Chờ owner decision hoặc integration

- Public controller exposure với real principal, permission và data scope.
- Final hash formula/field set/golden vectors.
- Production acceptance của chain verifier.
- Manifest/signature headers và external key handling.
- Checkpoint/anchor verification sau purge.
- Customer-specific scope hoặc enrichment join với transfer/ledger.

## 3. Component proposal

| Component | Layer / Package | Responsibility |
|---|---|---|
| `AuditController` | `adapter.in.web` | Parse/validate OpenAPI request; delegate; no SQL; public exposure waits for identity review |
| `AuditQueryPort` | `application.port` | Outbound port interface: `search(AuditSearchQuery, Instant)`, `findDetail(long)`. Decouples application layer completely from persistence; uses pure data records |
| `AuditQueryService` | `application` | Search/detail orchestration, fixed projection, OpenAPI DTO mapping, permission/data-scope boundary; depends only on `AuditQueryPort`; wired as `@Service` in Spring application context |
| `AuditDetailProjection` | `application` | Safe read-time projection: entity allowlist (14 types), nested allowlist (`FRAUD_FLAG.matchedValue`), numeric precision protection, synchronized 4096 node bound (supports up to 512 roleCodes) |
| `AuditRecordRef` | `application.port` | Immutable pure canonical record reference: `(long auditId, String recordHash, short hashVersion)` |
| `AuditRecordWriterPort` | `application.port` | Outbound port interface: `append(AuditEvent, Instant)` for appending audit records |
| `AuditRecorder` | `application` | Orchestrates write validation (`AuditEventValidator`), normalization, MANDATORY transaction boundary, delegates to `AuditRecordWriterPort` |
| `AuditExportService` | `application` | Stream bounded export from committed watermark, private artifact/hash and AUDIT_EXPORTED before response; snapshot read ends before separate C2 writer transaction |
| `AuditChainVerifier` | `verification` | Recanonicalize payload, compare payload/columns, verify hash version, prev hash and record hash |
| `AuditChainJdbcVerifier` | `adapter.out` | Bounded keyset scan (`audit_id ASC`, batch 1,000) over `banking.audit_log` with native SQL and JSONB extraction |
| `AuditQueryJdbcStore` | `adapter.out` | Implements `AuditQueryPort`: parameterized SQL, explicit projection, bounded pagination, deterministic order, native JSONB queries |
| `AuditJdbcWriter` | `adapter.out` | Implements `AuditRecordWriterPort`: native SQL, `JdbcTemplate`, `pg_advisory_xact_lock(hashtextextended('audit:chain', 0))`, READ COMMITTED check, hash calculation |
| `LocalExportArtifactStore` | `adapter.out` | Candidate private artifact storage, exact-byte hashing, cleanup and orphan cleanup; class name not fixed |

Component boundary (Hexagonal Architecture per `00. architecture & conventions.md §4.2`):
```
adapter/in -> application -> domain
                         |
                         -> application/port <- adapter/out
```

- Query/detail store is read-only.
- Detail projection bounds raw JSONB text to 64 KiB before parsing and compact JSON to 32 KiB afterward; PostgreSQL whitespace expansion is not a reason to reject an otherwise valid writer snapshot. These are read-time resource limits, not hash input or a mutation of stored evidence.
- Application layer contains zero native SQL, zero `JdbcTemplate` imports, and does not depend on concrete persistence classes.
- Persistence implementations in `adapter/out` implement ports defined in `application/port`.
- `AuditExportService` uses a bounded committed-ID watermark under current append-only writer assumptions; read/cursor phase ends before a separate READ COMMITTED C2 `AUDIT_EXPORTED` transaction. See §6.2 for why a mandatory repeatable-read snapshot is not required by current writer protocol.
- `AuditChainVerifier` does not write `audit_log`; checkpoint writes remain outside AUD-02 until checkpoint contract is approved.
- No controller/service accepts actor identity from request parameters. Principal and scope come from reviewed identity integration.

Do not expose service/API until AUD-01 hash protocol and identity permission contract are reviewed.

## 4. Data and hash verification

Query `banking.audit_log` read-only. Never hash PostgreSQL JSONB `::text` directly. Read stored `canonical_payload`, parse as JSON-compatible values, and recanonicalize through Common `JcsCanonicalizer`. Calculate record digest through Common `HmacService.sha256Hex` using the AUD-01 proposal; do not treat the proposal as approved protocol.

Verifier checks:
- `hash_version` is supported and agrees between row column and payload.
- Canonical payload rehash matches `record_hash`.
- `prev_hash` matches previous verified record hash, or genesis for a full genesis scan.
- Canonical payload fields agree with corresponding stored columns: timestamp, actor, action/entity, snapshots, amount/currency, correlation/request IDs, outcome and masked client info.
- `audit_id` and `recorded_at` are not covered by v1 payload/hash; report this scope limitation.
- Unsupported hash version, malformed payload, payload/column mismatch, missing middle record, prev-hash mismatch or digest mismatch fails closed.

Stored JSONB may reorder keys; JCS recanonicalization handles key order, but does not prove the stored payload matches the indexed/display columns unless explicit comparison above passes.

Field set v1 trong 04 §9.4/design/01 §7.2/AuditHashing hiện gồm: `occurred_at`, `actor_type`, `actor_user_id`, `actor_role`, `action`, `entity_type`, `entity_id`, `before_state`, `after_state`, `amount`, `currency`, `correlation_id`, `request_id`, `outcome`, `client_info_masked`, `hash_version`. Mỗi key có mặt kể cả null. ID/money string, counters integer, occurred_at UTC 6 fractional digits. Không thêm subject_customer_id hoặc field mới vào v1. Payload thiếu/thừa key hay sai type phải được báo, không sửa rồi verify.

Đề xuất component report: phạm vi genesis đến upperAuditId, row count thực kiểm, last verified ID/hash, first failing ID và loại lỗi; không log payload. Bảng rỗng báo EMPTY, không chứng minh chưa từng có dữ liệu. Unknown version báo UNSUPPORTED, không skip. Đoạn chain bắt đầu bằng nonzero prev_hash nhưng thiếu checkpoint đáng tin phải báo không thể xác minh phạm vi, không tự đặt genesis mới. Interrupted/partial scan không được coi là full pass. Đây là kết quả nội bộ, không bổ sung REST endpoint hoặc public error code.

Verify runner có thể scan từng batch 1.000 với upper bound ở đầu run và keyset audit_id. Kết quả/metric failure là yêu cầu; lịch job, alert routing, incremental checkpoint persistence cần D6. Không ghi thêm audit_log cho mỗi lần verify. Unit test dùng fixture có digest tính độc lập; disposable DB test sửa cột/payload ở test-only connection, không chạy tamper SQL trên DB local/shared.

A filtered search/export set is not a complete chain and cannot be used for contiguous-chain verification. Full-chain verification reads rows in `audit_id ASC`; sequence gaps are allowed because identity sequences can gap on rollback. `occurred_at` may be non-monotonic and does not define chain order. Without an approved external anchor, internal hash-chain verification cannot detect tail truncation. Checkpoint/purge verification remains out of scope.

Search:
- Parameterized filters; `reference` maps to `entity_type=TRANSFER`, `entity_id=reference`.
- `from` inclusive, `to` exclusive; default `now - 30 days` to `now`; maximum 366 days.
- Page is 0-based, default 0; size default 20, range 1–100. Out-of-range page returns empty `items`.
- Sort whitelist exactly `occurredAt`; OpenAPI shared `Sort` mô tả `field,asc|desc`. Dùng default `occurredAt,desc` từ design/01 §9.3; explicit `asc` không bị bỏ qua. Tie-break `audit_id` cùng chiều là chi tiết triển khai ổn định, không thêm sort field public và không cần xin lại quyền chọn field.
- Lấy một `now` từ Clock cho request; chỉ điền field thời gian bị thiếu: `from = now - 30 days`, `to = now` theo 07 §2.5 và YAML. Không tự đổi `from` thành `to - 30 days` khi client chỉ gửi `to`; sau default vẫn kiểm `from < to` và maximum 366 ngày.
- Mọi filter kết hợp AND. `reference` bổ sung ràng buộc TRANSFER/reference, không ghi đè `entityType`/`entityId` client gửi; filter hợp lệ nhưng mâu thuẫn trả kết quả rỗng. Không join fraud/ledger để tự mở rộng ý nghĩa reference.
- Tính offset bằng arithmetic không overflow. Count và page dùng cùng predicate; `AuditPage` gồm `items`, `page`, `asOf`; PageMeta có number/size/totalElements/totalPages/first/last, không trả null totals theo YAML hiện tại. Stable order không đảm bảo snapshot xuyên nhiều HTTP page; `asOf` không phải snapshot token.
- Mandatory bounded `LIMIT`; no `SELECT *`; list does not select snapshot columns.

## 5. REST and security

- `GET /api/v1/audit`: `AUDIT_READ`, OpenAPI `AuditPage`.
- `GET /api/v1/audit/{auditId}`: `AUDIT_READ`, `AuditDetail`, 404 `AUDIT_NOT_FOUND`.
- `GET /api/v1/audit/export`: `AUDIT_EXPORT`, CSV/JSONL, maxRows 10,000, 413 `EXPORT_TOO_LARGE`.

Common security chain supplies headers, correlation, CORS/CSRF and Problem output. Identity must supply real authorities and data scope. No component fixture can prove customer/staff/auditor access without identity adapter.

### 5.1. Baseline không cần hỏi lại

- Roles theo SRS §4.1, 06 §9.2, 07 §3.4: `CUSTOMER`, `OPERATIONS`, `ADMIN`, `AUDITOR`. Read: Operations/Admin/Auditor với AUDIT_READ. Export: Auditor với AUDIT_EXPORT; Admin không có quyền export mặc định.
- Không dùng `RISK_COMPLIANCE`/`ADMIN_SECURITY` hoặc cấp export cho Admin từ design/04 §5.1 hay module overview cũ. Những nguồn đó trái baseline 01–11. Không thêm role alias trong audit để che sự lệch này.
- Common chain hiện check `AUDIT_EXPORT` trước matcher `/audit/*`; không tạo chain thứ hai. Identity phải cung cấp authorities phù hợp role/permission baseline. Service enforce quyền khi gọi nội bộ; không tự truy vấn identity tables.
- List có `AuditSummary`, detail thêm requestId/beforeState/afterState/clientInfo. ID là string; amount fixed-two-decimal string, currency/null đúng contract. Không expose canonical_payload hoặc field nội bộ ngoài DTO.
- `maxRows` default/max 10.000, minimum 1 theo YAML; format default CSV, enum CSV/JSONL. Export không pagination. `/audit/export` literal không được parse thành auditId.
- Common quota export theo user và quota global đã có, process-local; 5/giờ/user và 120/phút vẫn mang nhãn ngưỡng đề xuất từ 07 §2.8. Reuse, không tạo limiter thứ hai.
- Request `X-Correlation-ID` là trace hiện tại; query `correlationId` là filter record lịch sử. Không lấy filter làm trace hoặc actor của AUDIT_EXPORTED.

### 5.2. Read-time protection và historical compatibility

Read projection/allowlist là defense in depth ngoài write validator. Không serialize entity/raw JSON trực tiếp. Không dùng current `AuditEventValidator.validate` như bộ lọc lịch sử: action catalog có thể đổi và hiện chặn một số action chờ review, còn read/verifier phải hiểu contract version đã lưu. Verifier kiểm field set v1, types và equality trước normalization; không dùng `AuditEvent` để sửa `"1.0"` thành `"1.00"`, chèn field thiếu hoặc bỏ unknown field rồi báo pass.

Nếu snapshot có field/value không an toàn, proposal là fail response/export với lỗi hệ thống an toàn và telemetry, không echo value, không che mất corruption bằng sửa nội dung dưới recordHash cũ. Compatibility policy D3/D4 phải được review trước phục vụ historical dataset. CSV không có states; JSONL AuditDetail chỉ tái tạo được v1 payload nếu giữ đầy đủ field, null, microsecond timestamp và types. CSV không đủ payload để tự recompute record hash.

### 5.3. Scope và integration chưa có

SRS DAR-002/003 và 07 §3.6 bắt buộc scope/mục đích hợp lệ. Design/01 §9.1 đề xuất MVP đọc toàn bộ audit đã mask và để row-level scope cho production; không lấy câu này làm quyết định mở toàn bộ dữ liệu khi nguồn cấp cao chưa xác định scope chi tiết. D4 cần chốt scope với Đạt.

`SessionAuthenticator.Session` hiện có userId/customerId/authorities/csrfTokenHash, không có actorRole riêng hoặc grant về row-level scope. Không chọn phần tử đầu tiên của Set authorities làm actorRole, không dùng header/client body, không infer scope từ filter. Role snapshot cho export cần adapter identity đã review.

C3 access-denied hiện chưa có: Common accessDeniedHandler chỉ tạo Problem; AUD-01 từ chối SECURITY_ACCESS_DENIED. Controller advice không bắt được mọi denial ở filter. D5 phải chỉ rõ callback/filter/method-security integration, single event và fallback giữ nguyên 403; không giả đã đạt AC-020 từ HTTP 403 đơn thuần.

## 6. Export flow

### 6.1. Invariant bắt buộc và phương án đề xuất

Bắt buộc: authorization trước đọc dữ liệu; vượt maxRows phải 413 thay vì cắt thầm; không byte file nào rời server trước khi `AUDIT_EXPORTED` commit; file không chứa record export của chính nó. GET export vẫn theo 07/YAML, không tự chuyển sang POST, thêm CSRF/step-up requirement hoặc job endpoint.

Phương án đề xuất (D2/D3, chưa phê duyệt byte contract hoặc policy vận hành):

1. Authorize qua Common + trusted identity; resolve filter/default một lần, không dùng actor từ query.
2. Chốt `upperAuditId` bằng audit ID lớn nhất đã nhìn thấy. Bảng rỗng cho kết quả rỗng.
3. Đọc chỉ các row `audit_id <= upperAuditId`, cùng filter/order bất biến cho mọi batch. Keyset nội bộ theo `(occurred_at, audit_id)`; không đổi API search offset thành cursor.
4. Đọc tối đa `maxRows + 1` matching rows. Row thứ `maxRows + 1` chỉ là overflow probe; discard artifact và trả 413 trước stream. Không dùng unbounded COUNT cho export.
5. Tạo file private ngoài static/web root, theo batch tối đa 1.000, giữ memory bounded. CSV/JSONL format và digest theo D3. Không giữ global audit advisory lock trong lúc đọc/tạo file.
6. Flush/close file hoàn chỉnh; hash đúng bytes file. Đóng cursor/read transaction trước ghi export audit.
7. Mở transaction ngắn READ COMMITTED, gọi `AuditRecorder.recordInTransaction(event, occurredAt)` trong cùng module audit. Đợi transaction boundary trả thành công sau commit, không chỉ đợi method recorder trả về.
8. Set response attachment và stream file; không vừa sinh lại nội dung vừa gửi. Xử lý lỗi write/commit trước response theo Common Problem, không bọc DB exception thành 400 validation.
9. Cleanup khi xong, disconnect, generation/audit failure; orphan cleanup chỉ xóa file do export subsystem sở hữu, không quét/xóa temp của module khác, không xóa file còn đang dùng. Không log path/payload bí mật.

### 6.2. Snapshot phù hợp với AUD-01 thực tế

AUD-01 lấy transaction advisory lock **trước** INSERT sinh identity ID, giữ đến commit. Với mọi writer tuân thủ protocol này, default identity sequence hiện tại, bảng append-only và purge chưa bật: không có writer ID nhỏ chưa commit nằm dưới một writer ID lớn đã commit. Vì vậy watermark committed `upperAuditId` kết hợp các predicate cố định và READ COMMITTED đủ cố định tập row ở trạng thái hiện tại; không cần REPEATABLE READ chỉ vì đọc theo batch.

Nhận định rằng writer AUD-01 có thể nhận ID thấp, chờ commit trong khi writer ID cao đã visible là không đúng với lock-before-INSERT hiện tại. Race đó chỉ xảy ra khi bỏ qua protocol, preallocate ID ngoài lock hoặc thay sequence semantics. Không dùng nhận định đó để bắt buộc đổi isolation.

**Đề xuất D2:** giữ synchronous export, committed `upperAuditId`, read bounded theo batch 1.000, probe `maxRows + 1`, temp artifact riêng và C2 commit trước bytes. Không cần REPEATABLE READ khi mọi writer giữ lock-before-INSERT; đổi write path/retention phải review lại. Chốt disk/time/concurrent-export limits trước enable.

Sau commit, client có thể disconnect. `AUDIT_EXPORTED` chỉ chứng minh file đã chuẩn bị và được ghi nhận trước khi phát bytes, không chứng minh download hoàn tất. Không retry HTTP stream hoặc sửa audit record đã commit; technical telemetry phân biệt disconnect, không tạo action mới tùy ý.

10.000 row × canonical payload tối đa 32 KiB đã gần 313 MiB, chưa tính wrapper/escaping; giới hạn row không đồng nghĩa file nhỏ. Không `byte[]` toàn file, không `readAllBytes` chỉ để gọi SHA helper. D2 phải review giới hạn disk, generation time, concurrent exports và cleanup trước enable; rate limit 5/giờ/user không thay giới hạn tổng tài nguyên.


## 7. Tests

### 7.1. Independent component tests

- Filter validation: exact filters, defaults, null/default dates, inclusive/exclusive bounds, 366-day maximum, page/size bounds, `occurredAt,asc|desc`, invalid field rejection and request-time `now` consistency.
- Query safety: bind values; reject sort injection; bounded limit; no `SELECT *`; verify large offset cannot overflow. Search/detail do not write `audit_log`. Summary omits snapshots; detail uses safe projection. Assert page metadata, ID/money/time formats and `asOf`.
- Query semantics: combined filters use AND; `reference` maps to transfer entity; contradictory filters return empty results; no enrichment join changes meaning.
- TDD query tests: synth audit rows with IDs intentionally not matching occurredAt, including same timestamp; verify both sort directions plus stable ID tie-break, filters, no data leak, empty result when combined filters contradict; compare items/page/asOf to OpenAPI.
- Export 0/10,000/10,001 rows; verify `maxRows+1` overflow rule, deterministic CSV/JSONL bytes, escaping, UTF-8/no BOM, fixed newline, stable row order, hash golden vectors, and that self-generated AUDIT_EXPORTED row is excluded from artifact. Test memory remains bounded using batch behavior rather than a 10,000×32 KiB fixture.
- Disposable DB concurrency tests: writer obtains lock before INSERT, export captures committed upper ID, writer commits later with normal or backdated occurredAt, new row stays outside watermark; explicitly test timestamp tie and ensure no lock is held during artifact generation. Add negative test/documentation for unsupported writer that inserts outside protocol if feasible; do not claim guarantee for it.
- Test response starts only after C2 transaction proxy returns successfully after commit; inject validation, insert, commit and file-generation failure. Assert zero bytes/artifact cleanup for failures before response; test disconnect cleanup and orphan cleanup only against dedicated temp directory.
- Test export size/resource bounds and concurrent exports using configurable test thresholds; operational thresholds remain D2 decision before enable.
- Verifier independent fixtures: use externally computed golden payload/hash, valid chain, unknown version, missing/extra key/null/type mismatch, row column mutation without payload mutation, payload mutation with stale hash, middle-row omission, wrong prev hash, sequence gap valid after rollback, non-monotonic occurredAt valid, JSONB key reorder valid. Test summary detail/export projection do not repair corrupted record.
- CSV formula tests: use inputs beginning `=`, `+`, `-`, `@`, tab, CR and whitespace variants. Until D3 decides policy, keep test marked blocked; never weaken the security finding or silently change artifact bytes.
- Security harness tests with fake authority validate local controller gate only. Do not mark real identity, DAR scope or C3 AC-020 verified.
### 7.2. Blocked integration tests

- Real principal/permission/data-scope matrix: blocked until identity adapter exists.
- Real 403 plus `SECURITY_ACCESS_DENIED`: blocked because AUD-01 action remains owner-review fail-closed.
- Final manifest/signature headers and external key: blocked until architecture/security contract exists.
- Checkpoint/anchor/post-purge verification: outside AUD-02; covered by AUD-03 after contract approval.

## 8. Decisions, blockers and handoff

### 8.1. Quyết định cần người dùng/owner xem xét

Các lựa chọn sau là đề xuất, chưa được phê duyệt. Không thay docs-common hoặc code Common. Công việc component synthetic chỉ được triển khai trong ranh giới đã nêu; không coi việc ghi đề xuất là owner sign-off. Người dùng có thể chọn hướng component D2/D3/D6 để chuẩn bị implementation; việc đó không thay review của Phương/Đạt/architecture cho protocol, scope hoặc release. D4/D5/D7 chủ yếu là hợp đồng cần owner phối hợp, không yêu cầu người dùng tự thiết kế identity hoặc cung cấp fixture ngay trong lượt docs.

**D1 — Hash v1 và phạm vi verifier — Phương + architecture.**
- Bằng chứng: 04 §4.6.1 nối thêm `hash_version`; 04 §9.4/06 §8.5 đặt version trong payload; design/01 §7.3 thêm dấu phân cách `|`. AUD-01 code chạy công thức có `|`. Các nguồn cấp cao không mô tả delimiter nhất quán.
- Đề xuất: duyệt đúng proposal đang chạy `SHA-256(UTF8(JCS(payload)) || "|" || prev_hash)`, version nằm một lần trong payload; frozen v1 field set và golden vectors. Rebuild/compare các cột với payload trước kết luận chain.
- Lý do: khớp writer hiện tại; phát hiện cả trường hợp cột trả API bị đổi mà canonical_payload không đổi. Không chứng minh `audit_id`/`recorded_at`, tail truncation hoặc DBA rewrite.
- Cần bằng chứng: golden vectors độc lập/cross-language, malformed/missing/extra fields, column-only mutation, unsupported version, full-chain scan. Trước khi chốt chỉ gọi là component proposal.

**D2 — Snapshot export và tài nguyên — Phương + architecture.**
- Bằng chứng: AUD-01 serializes writer bằng advisory xact lock trước INSERT; append-only trigger, purge chưa bật. 07 yêu cầu synchronous export tối đa 10.000, 413 nếu vượt; OI-API-11 còn ghi sync/async cần chốt.
- **Đề xuất:** synchronous bounded materialization với committed `upperAuditId` và `maxRows + 1` probe; batch 1.000, private temp file; transaction ghi C2 READ COMMITTED riêng sau materialization. Verify concurrent writer/backdated `occurred_at`; không giữ global audit lock trong khi làm file.
- Giới hạn: phụ thuộc mọi writer tuân thủ lock-before-ID, không purge/update khi export. Không triển khai timeout/concurrent quota/disk budget bằng số tùy ý; owner chốt thông số deployment sau đo/test. Nếu retention/multiple writer paths thay invariant, review lại read-only snapshot. Async export chỉ là phương án khác, cần đổi API riêng.

**D3 — Bytes, CSV safety và manifest — Phương + Security/architecture.**
- Bằng chứng: 07 §8.6 yêu cầu CSV header cố định/JSONL và AUDIT_EXPORTED; design/01 §9.6 đề xuất manifest/HMAC; OpenAPI không khai báo manifest. Validator AUD-01 yêu cầu fileHash/filterHash là 64 lowercase hex, trong khi ví dụ manifest dùng `sha256:`.
- Đề xuất byte contract để duyệt: UTF-8 không BOM; CSV CRLF theo convention CSV, JSONL LF, có newline cuối mỗi record; CSV có header cả khi rỗng, JSONL rỗng là zero bytes. Tiền fixed 2 decimals, timestamp UTC 6 fractional digits. Digest SHA-256 đúng toàn bộ bytes file sau escaping, lowercase hex trong audit state. Không dùng platform newline.
- Đề xuất filterHash: JCS object gồm đủ keys `correlationId`, `reference`, `actorUserId`, `actorType`, `action`, `entityType`, `entityId`, `outcome`, `from`, `to`, kể cả null; dates là effective UTC bounds, IDs dạng string. Không đưa trace request hiện tại vào object. Format/maxRows/sort không phải filter lựa chọn row: giữ metadata export riêng; đừng gọi hash của toàn request là filterHash. Nếu D4 cần scope hạn chế, phải bổ sung representation scope đáng tin trước chốt protocol, không tự nhận scope từ client.
- Golden vectors cho exact bytes và normalized filter trước khi persist. Chọn khác vẫn được nhưng phải version hóa contract trước khi tạo bằng chứng dùng lâu dài.
- CSV injection: quote theo CSV không tự ngăn spreadsheet chạy công thức. Đề xuất fail-closed đối với cell nguy hiểm sau khi xác định rõ prefix/whitespace policy, không tự thêm apostrophe làm thay đổi evidence. Thay đổi representation chỉ sau review. JSONL cần giữ null/types và timestamp chính xác để người nhận có thể tái tạo payload v1; CSV chỉ summary không đủ để recompute.
- HMAC manifest ký bằng key ngoài DB chỉ có thể được xác thực độc lập bởi bên có key, không phải chữ ký công khai. Key separation, rotation, distribution và headers chưa chốt; không dùng `APP_HMAC_KEY` mặc định hoặc xuất headers vì Common CORS đã expose.

**D4 — Authorization, scope và actor — Đạt.**
- Baseline 03 §4.2/07 §3.4/§8.6: Operations/Admin/Auditor AUDIT_READ; chỉ Auditor mặc định có AUDIT_EXPORT; Customer không có. design/04 §5.1 và overview identity dùng role khác/cho Admin export; không ưu tiên các role đó so với 01–11.
- Vẫn cần Đạt chốt principal mapping, policy scope cụ thể phù hợp DAR-002/003, quyền cấp động và actorRole snapshot khi user có nhiều role. Session SPI hiện không có role snapshot hoặc scope đầy đủ.
- Đề xuất: không public endpoint khi scope hoặc role snapshot không xác định; deny by default, authenticated identity cung cấp snapshot đáng tin, không tự suy từ Set authorities hoặc client filter. Test positive/negative, cross-scope, revoked session và multi-role.

**D5 — C3 denial và export semantics — Đạt + architecture.**
- Bằng chứng: 07/SRS AC-020 yêu cầu 403 + SECURITY_ACCESS_DENIED; Common handler hiện chỉ trả Problem; AUD-01 action bị chặn và không có C3 writer.
- Đề xuất: hook tại đúng security denial boundary, best-effort transaction độc lập + structured security fallback/metric khi lỗi, vẫn giữ nguyên 403; phân biệt 401, 403 và business validation; tránh double event giữa filter/service.
- Không chặn unit/query component vì thiếu C3, nhưng không đánh dấu AC-020 pass hoặc public integration ready.

**D6 — Verification job/anchor — architecture + Phương.**
- Bằng chứng: 04 §9.4/06 §8.5 yêu cầu verification định kỳ, alert; design/01 §7.6 đề xuất checkpoint, incremental 15 phút và full verify. Checkpoint chưa tồn tại, AUD-03 chặn purge.
- Đề xuất: trước mắt synchronous component full verify từ genesis đến frozen upperAuditId theo batch; không tự thêm job/checkpoint/table. Chốt scheduling, ownership, metric, alert, restart và trust anchor trước release; không báo PASS cho partial scan hay retained segment chưa có trusted checkpoint.
- Tail truncation và rewrite toàn chain không phát hiện được bằng internal chain. External signed anchor là phần khác, không khẳng định đã có trong AUD-02.

**D7 — Transfer/ledger trace — Vũ + Phương.**
- Bằng chứng: 07 §8.6 yêu cầu reference map TRANSFER/entity_id; transfer overview yêu cầu propagate correlation qua T1/T2; ledger overview chỉ ra ledger là module write-owner riêng.
- Đề xuất: giữ query audit top-level và kết hợp filters AND, không tự join ledger/transfer hoặc suy ra ownership qua reference. Khi module thật sẵn sàng, chạy test reference/correlation/actor với synthetic T1/T2, retry và reconciliation; cần owner cung cấp fixture/ports.

### 8.2. Confirmed from authoritative contracts

- `03` §4.2/§4.3, `07` §3.4–§3.6 and §8.6 define `AUDIT_READ` for Operations/Admin/Auditor, and `AUDIT_EXPORT` for Auditor, not Admin. Access uses permission/authority, not role name alone; ownership/data scope remains required by SRS DAR-002/003. `07` §3.4 and §3.6 take precedence over conflicting lower-priority role summaries; no role translation.
- `07` §2.5 defines page default 0, size default 20, size 1–100, audit sort field `occurredAt`; shared OpenAPI `Sort` permits `asc|desc`. Default descending từ design/01 §9.3 là bổ sung không trái API; explicit asc vẫn phải được tôn trọng. Tie-break ID cùng chiều là chi tiết triển khai, không phải permission/protocol cần người dùng chọn lại.
- `07` §2.5/YAML define from inclusive, to exclusive, missing `from` defaults to now-30d, missing `to` defaults to now, max 366 days. Apply defaults independently from one request-scoped `now`; if resulting range invalid, reject validation.
- OpenAPI filter set and design semantics define exact `reference` transfer mapping. Combined filters use AND; no cross-module join is required for base API.
- `07` §7.2.15/OpenAPI define summary/detail/page schemas; `07` §8.6 defines CSV, JSONL, maxRows 10,000, 413 overflow. ID schemas use decimal string; page metadata has concrete totals.
- OpenAPI `AuditSummary.prevHash` accepts null, but AUD-01 migration enforces non-null; response must reflect DB truth, with first/genesis row returning 64-zero hash, not null.
- OpenAPI declares `Content-Disposition`, `text/csv`, `application/x-ndjson`, but not export hash/audit ID/manifest headers. Common CORS currently exposes `X-Export-SHA256` and `X-Export-Audit-Id`; that does not make those headers public AUD-02 contract. Do not send until schema/API owner approves.
- `AUDIT_NOT_FOUND`, `EXPORT_TOO_LARGE`, `AUTH_FORBIDDEN`, `AUDIT_WRITE_FAILED` exist in Common `ErrorCode`. OpenAPI `PayloadTooLarge` mô tả body/export vượt giới hạn và tham chiếu generic Problem. Với overflow export dùng 413/EXPORT_TOO_LARGE theo 07 §8.6; đây là mapping đã rõ, không cần owner chọn lại.
- Common JCS and SHA primitives are already reused by AUD-01. No Common change is needed.

### 8.3. Owner decisions

- **Phương / architecture:** hash v1 source conflict and golden vectors; migration/role provisioning; export snapshot/resource strategy. Sort/default date xử lý theo baseline tại §4.
- **Đạt:** real identity adapter, principal authorities, staff/customer scope, actor snapshot và C3 denied-access fallback; service must not infer role/scope from current `Session` fields or query filters.
- **Architecture/Security:** manifest/signature headers and key lifecycle; verifier acceptance scope; external anchor/checkpoint policy; CSV safe-output policy and exact export byte contract.
- **Vũ:** review real transfer reference/correlation query examples when transfer/ledger schemas and ports exist; not a blocker for synthetic component work.

### 8.4. Current implementation gaps and limits

- AUD-01 hash version/formula conflict in docs-common remains unresolved. AUD-02 may test the running proposal, but must label it proposal and not production-approved.
- AUD-01 public `AuditModuleApi` is not reviewed for cross-module callers. Intra-module export có thể dùng `AuditRecorder` với transaction boundary đã kiểm thử, không cần tạo public interface chỉ để gọi trong cùng module. Production acceptance vẫn chờ hash/identity/actor/security gates; không gộp mọi blocker thành public port.
- Identity provides no production principal/permission/data-scope implementation. Controller exposure and real RBAC acceptance remain blocked.
- `SECURITY_ACCESS_DENIED` remains fail-closed in AUD-01; AC-020 unauthorized-access audit cannot be claimed complete yet.
- Migration V2 grants to `banking_app` only if role exists; it does not fail fast when role is absent. Role provisioning must be coordinated before deployment.
- Design manifest headers/signature are proposals and absent from OpenAPI. Do not add them as public response contract without approval.
- CSV formula-like values: export requirements demand fixed columns/byte hash, but no source settles spreadsheet formula safety. Ask Architecture/Security to choose a deterministic CSV representation before contract hash is finalized; tests must cover values beginning `=`, `+`, `-`, `@`, tab and carriage return. Do not silently prefix apostrophe or mutate authenticated evidence bytes.
- No approved checkpoint/external anchor contract. No tail-truncation or post-purge verification claim.

### 8.5. Nguồn đã đối chiếu và giới hạn kế thừa

| Nguồn | Phần áp dụng / điều không kế thừa |
|---|---|
| [SRS](../../../docs-common/03.%20SRS.md) §4.1–4.3, AC-020–022 | Role, scope, audit denial và traceability; không coi component là full AC |
| [API contracts](../../../docs-common/07.%20API%20contracts.md) §2.5–2.8, §3.4–3.6, §7.2.15, §8.6, §10.1 | Nguồn ngữ nghĩa API; parameter defaults, roles, overflow/error |
| [OpenAPI](../../../openapi/digital-banking-v1.yaml) | Paths, Sort, From/To, PageMeta, AuditSummary/Detail/Page, generic Problem |
| [DB design](../../../docs-common/04.%20Database%20design%20%26%20analysis.md) §4.6.1/9.4 và [architecture](../../../docs-common/06.%20System%20architecture.md) §8.5/9.2/9.5 | Field set/hash conflict; role và job requirements |
| [Audit design](../../../docs-common/design/01.%20audit-traceability.md) §7/9/13 | Chain order, CSV columns, JSONL, tests; manifest/checkpoint vẫn proposal |
| [Security design](../../../docs-common/design/04.%20security.md) §5.1 | Role/export matrix khác 01–11; không cấp export cho Admin theo nguồn này |
| [Identity overview](../_overview/01-identity-auth.md) §2/4 và [admin integration](../admin/BE-ADM-01-fraud-audit-integration.md) §2–4 | Ownership principal/actor/C3; overview identity còn role names khác baseline, admin integration yêu cầu theo 07 |
| [Transfer overview](../_overview/04-transfer.md) §4 và [ledger overview](../_overview/05-ledger.md) §4 | Trace và write-owner; không dùng schema summary làm schema thật cho join. Transfer overview có scoped idempotency key/reference naming khác DB 04; ledger overview thêm reference/counterparty columns không có baseline 04 §4.4.2 |
| [Common TKCT](../common/TKCT.md) §1.2/1.5 và [current state](../common/CURRENT-STATE.md) | Thứ tự ưu tiên nguồn và primitive/identity/observability boundaries; snapshot cũ không thay source hiện tại |
| [AUD-01 spec](BE-AUD-01-audit-writer.md), [report](BE-AUD-01-verification-report.md), [AUD-03](BE-AUD-03-retention-legal-hold.md) | Proposal v1, C1/C2 only, C3 guard, no purge/checkpoint integration |
| [Test plan](../../../docs-common/09.%20Test%20plan.md) §7.5/8 | Contract/status/masking/evidence gates; không tự nâng Planned thành Verified |

## 9. Thứ tự thực hiện và điểm dừng

1. **Chuẩn bị docs (lượt hiện tại):** đối chiếu nguồn, sửa facts đã rõ, ghi D1–D7 với đề xuất/lý do/evidence. Không code.
2. **Query contract:** sau lệnh triển khai, TDD DTO/filter/page/projection theo §4/5; unit synthetic và JDBC Testcontainers. Không bật endpoint public, không cần đợi transfer/ledger API hay toàn AUD-01 Done.
3. **Verifier component:** TDD frozen v1 payload/column/chain checks; report internal scope, unknown/partial/empty. Không chuyển hash proposal thành approval hoặc thêm checkpoint/job tự chạy.
4. **Export component:** chốt D2/D3 làm baseline trước khi tạo byte fixtures; sau đó TDD bounded read/file/digest/cleanup và C2 commit-before-response. Identity giả chỉ trong test; không nối fake actor vào production.
5. **Integration và release:** D4/D5 identity/scope/denial, D1 hash review, provisioning và D6 vận hành phải có evidence. D7 cung cấp cross-module trace test; không gắn tiến độ vào W4 hoặc đợi tất cả module mới làm component.

Tên component ở §3 là responsibility map, không bắt buộc tạo đủ lớp/interface ngay. Dùng các lớp domain/application/infrastructure/api theo conventions hiện hữu khi thiết kế implementation; không tạo dependency ngược vào identity/admin hoặc mở package tùy ý mà chưa kiểm architecture rules.

Chưa tạo implementation test classes hoặc chạy runtime tests trong lượt chuẩn bị. Report ghi rõ tài liệu đã rà soát khác với runtime Planned. Không stage/commit/push.

## Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Initial query/export/verifier proposal |
| 0.2 | 12/11/2026 | Aligned with Common JCS/context/error/security implementation |
| 0.3 | 14/11/2026 | Reconciled API/Common/module contracts; added independent scope, payload-column verification, snapshot export proposal and owner blockers |