# BE-AUD-02 — Query, export và chain verification

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-AUDIT-02; **Task:** BE-AUD-02  
> **Phiên bản:** 0.1; **Ngày:** 11/11/2026  
> **Trạng thái tài liệu:** Draft — chờ review; **Implementation:** Planned; **Verification:** Not verified  
> **Người đề xuất / Owner:** Thịnh; **Reviewer đề xuất:** Đạt (identity/RBAC), Phương (DB/JCS); chưa có approval  
> **Nguồn:** [[digitalbanking-docs/docs-common/07. API contracts.md]] §8.6; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] paths `/audit*`; [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §7, §9, §13; [[digitalbanking-docs/docs-common/09. Test plan.md]]; [[digitalbanking-docs/docs-backend/deployment/configuration.md]] §5.

## 1. Phạm vi và dependencies

FR-036–038, AC-020–022 và NFR-021: search/detail/export audit đã mask, kiểm tra integrity hash chain. Input nền là AUD-01 writer/hash v1; identity cung cấp authentication/authorization, common cung cấp errors/correlation. Không xây retention/purge, external anchor/WORM hay đổi OpenAPI trong task này.

Thịnh chủ động thiết kế query, test harness và export giả lập. Identity chưa xong có thể dùng mock principal **chỉ trong test**; không dùng mock để tuyên bố RBAC end-to-end đã đạt. Caller review trước tích hợp; không chặn component code.

## 2. Interface và file map đề xuất

Root `digitalbanking-backend/src/main/java/com/simulatedbank/banking/audit/`; không thay port writer của AUD-01:

| File | Vai trò |
|---|---|
| `adapter/in/AuditController.java` | Parse/validate request, permission, gọi service |
| `adapter/in/dto/*` | Records theo OpenAPI `AuditPage`, `AuditDetail`; không trả entity |
| `application/AuditQueryService.java` | Search/detail, permission/data scope |
| `application/AuditExportService.java` | Materialize file, hash, C2 audit, trả artifact |
| `application/AuditChainVerifier.java` | Verify fixed chain snapshot theo hash version |
| `application/port/AuditQueryStore.java` | Query abstraction |
| `adapter/out/AuditQueryJdbcStore.java` | Parameterized SQL/pagination |
| `adapter/out/LocalExportArtifactStore.java` | Temp artifact có cleanup/permission theo runtime |

```java
public record AuditSearchQuery(
    String correlationId, String reference, Long actorUserId,
    String actorType, String action, String entityType, String entityId,
    String outcome, Instant from, Instant to, int page, int size, String sort) {}
public interface AuditQueryService {
    AuditPage search(AuditSearchQuery query);
    AuditDetail get(long auditId);
}
public interface AuditExportService {
    ExportArtifact prepare(AuditSearchQuery query, ExportFormat format, int maxRows);
}
```

Tên services/records là proposal; test với safe fixtures. ExportArtifact đóng resource và xóa temp sau response hoặc lỗi; không expose filesystem path hay quyền truy cập artifact qua REST khác.

## 3. Dữ liệu, SQL và consistency

Chỉ đọc `banking.audit_log`, không ghi/update record. `search` bind toàn bộ giá trị, sort whitelist chỉ `occurredAt`, tie-break `audit_id` để ổn định thứ tự; thời gian from inclusive/to exclusive, `page >= 0`, `size 1..100`, default khoảng 30 ngày, tối đa 366 ngày theo audit design. `reference` mapping cần xác nhận vì entity_id trong một số event có thể là ID thay vì reference; không đoán JOIN bảng transfer.

Đề xuất search query skeleton:

```sql
SELECT audit_id, occurred_at, recorded_at, actor_type, actor_user_id,
       actor_role, action, entity_type, entity_id, amount, currency,
       correlation_id, outcome, prev_hash, record_hash, hash_version
FROM banking.audit_log
WHERE occurred_at >= :from AND occurred_at < :to
  AND (:actor_user_id IS NULL OR actor_user_id = :actor_user_id)
  AND (:correlation_id IS NULL OR correlation_id = :correlation_id)
ORDER BY occurred_at DESC, audit_id DESC
LIMIT :size OFFSET :offset;
```

Skeleton chỉ minh họa 2 filters; implementation phải xử lý **toàn bộ** OpenAPI filters, count/pagination nhất quán, whitelist sort, không string-concatenate input. `GET` detail lookup ID dương. Không dùng long transaction DB kéo dài khi client download.

**AUD-Q01 — export snapshot proposal:** chuẩn hóa filter, chốt max audit_id tại thời điểm bắt đầu để loại record mới và chính AUDIT_EXPORTED; lấy tối đa `maxRows+1` records trên snapshot nhất quán. Nếu vượt maxRows, dừng và trả 413, không trả file một phần. Materialize CSV/JSONL trong vùng temporary private với giới hạn 10.000 rows và giới hạn byte cấu hình được duyệt; hash đúng bytes file và hash canonical filter; đóng DB transaction trước response. Không dùng streaming trực tiếp từ DB vì cần tính fileHash trước audit export và không được gửi bytes trước C2 success. Tạm liệu encrypted-at-rest/secure cleanup, kích thước byte limit, đĩa đầy, crash và scale cần review với Đạt/Phương. Partial file bị xóa khi validation, serialize, storage hoặc audit failure; khi process crash cần orphan cleanup, không log content/path nhạy cảm.

**AUD-Q02 — verifier proposal:** ghi nhận upper bound audit_id khi bắt đầu, đọc theo audit_id ASC với deterministic canonical v1; verify prev_hash + record_hash; identity gap không phải lỗi. Genesis là 64 số 0 khi chưa purge; sau purge chỉ dùng checkpoint đã verify theo AUD-03. Mismatch/unsupported hash version dừng, phát metric/alert và report range/ID an toàn. Chain nội bộ không phát hiện chắc chắn tail truncation; chỉ claim phát hiện khi external anchor được triển khai và test.

## 4. REST contract và errors

| Endpoint | Quyền | Thành công | Kiểm tra chính |
|---|---|---|---|
| `GET /api/v1/audit` | `AUDIT_READ` | `AuditPage` + correlation | filter, sort, range, pagination |
| `GET /api/v1/audit/{auditId}` | `AUDIT_READ` | `AuditDetail` | 404 nếu không tồn tại; snapshots masked |
| `GET /api/v1/audit/export` | `AUDIT_EXPORT` | CSV `text/csv` hoặc JSONL `application/x-ndjson`; Content-Disposition | `format=CSV|JSONL`, maxRows 1–10000 |

Ví dụ query: `GET /api/v1/audit?from=2026-11-01T00:00:00Z&to=2026-11-02T00:00:00Z&page=0&size=20&sort=occurredAt,desc`. Response 200 theo OpenAPI: object `items`, `page`, `asOf`; `auditId` là string; không tự bọc thêm `data`.

OpenAPI liệt kê `X-Correlation-ID`, `Content-Disposition`; manifest headers trong design/01 là đề xuất, không tuyên bố đã là bắt buộc. Range/page/sort lỗi 400, không login 401, thiếu quyền 403, không có auditId 404, quá maxRows 413, rate limit 429, lỗi hệ thống 500/503 theo endpoint contract. Mọi response chứa correlation; không tiết lộ SQL/stack/PII; `Cache-Control: no-store` theo security convention.

## 5. Luồng export và failure

1. Xác thực/authorize `AUDIT_EXPORT` và validate filters; reject trước khi tạo file.
2. Freeze snapshot như AUD-Q01, serialize masked data; đạt maxRows và fileHash/filterHash. Không ghi `AUDIT_EXPORTED` vào chính file này.
3. Ghi `AUDIT_EXPORTED` C2 trong **transaction riêng fail-closed**; lưu `exportId`, rowCount, from/to, format, fileHash/filterHash theo allowlist.
4. Chỉ sau commit audit thành công mới mở response stream. Nếu audit write fail, xóa artifact, trả lỗi; **không gửi file bytes trước commit**.
5. Kết thúc hoặc client disconnect thì close/xóa artifact. Nếu response bị ngắt sau commit, audit chỉ chứng minh export đã được cho phép/chuẩn bị, không chứng minh client đã nhận đủ; cần ghi semantics phù hợp trước khi claim delivery evidence.

Không dùng `recordStandalone` C3 best-effort cho export; có thể dùng `TransactionTemplate` riêng kết hợp `recordInTransaction` MANDATORY. AUD-Q03 ghi đề xuất này, chờ review với AUD-01 và identity owner.

## 6. Audit/metrics

Search/detail thành công không tạo audit event; denied access là C3 theo catalog; export thành công ghi C2 như §5. Verification mismatch có metric/alert, dừng các bước retention liên quan; không log raw snapshots hoặc dùng audit ID/correlation ID làm metric tag.

## 7. Thứ tự làm và acceptance

- [ ] `AuditQueryIT`: seed synthetic events; every filter, `from`/`to` boundary, stable ordering, page > cuối, sort injection, range >366 ngày.
- [ ] `AuditApiContractTest`: role matrix Operations/Admin/Auditor/Customer; list/detail schema & headers; 400/401/403/404; fake identity được đánh dấu component-only.
- [ ] `AuditExportIT`: CSV/JSONL bytes, known file/filter hashes, 0/10000/10001 rows, no export self-record, masked fields, cleanup.
- [ ] `AuditExportFailureIT`: injected audit fail, disk full, serialization fail, client disconnect; audit fail không có file byte và không để orphan file.
- [ ] `AuditChainVerifierIT`: valid chain, sequence gap, modified interior record, missing record, invalid/unknown version; verify fixed upper boundary dưới concurrent append. Tail truncation test chỉ đạt với anchor thật.
- [ ] Integration với identity thật và AUD-01 writer; map `T-AUD-01/02/10/11/15/16/17/20/21`, ghi expected/actual tại [report](BE-AUD-02-verification-report.md).

Lệnh dự kiến từ Backend: `./gradlew test --tests '*audit.AuditQueryIT' --tests '*audit.AuditApiContractTest' --tests '*audit.AuditExportIT' --tests '*audit.AuditExportFailureIT' --tests '*audit.AuditChainVerifierIT'`. Chưa có các class này.

## Quyết định mở và handoff

AUD-Q01 snapshot/temp artifact và byte cap; AUD-Q02 verifier/checkpoint; AUD-Q03 transaction export C2; cách map reference và contract manifest cần review. Những điểm này là đề xuất, không coi là behavior đã có. Component done khi acceptance/test thật có evidence; integration done khi RBAC/caller thật đã ghép.

## Change log

| Version | Ngày | Nội dung |
|---|---|---|
| 0.1 | 11/11/2026 | Đề xuất query, export fail-closed và chain verifier |
