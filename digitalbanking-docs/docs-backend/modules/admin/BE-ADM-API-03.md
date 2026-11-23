# BE-ADM-API-03 — Approval list/detail REST API

> **Mã task:** `BE-ADM-API-03` (P1)
> **Ngày:** 18/11/2026
> **Branch:** `datht-backend-rework` (không tạo branch, không commit/push)
> **Trạng thái:** **IMPLEMENTED** — list/detail read-only với filter/page/sort whitelist, scope maker/checker
> server-side trong SQL (kể cả count), 404 anti-enumeration, payload mask, không audit/không mutate. Phần phân công
> checker theo từng request/tenant vẫn mở ở `ADM-OI-06`.
> **Nguồn:** `07` §2.5/§7.2.14/§8.8.3/§8.8.4/§8.8.5, `design/04` §5.1/§5.2 (anti-IDOR), `design/05` §12.1.1,
> `04` §4.5.3, OpenAPI `/admin/approvals[/{approvalId}]`, `BE-ADM-DB-02`.

## 1. Endpoint

| API | Method/path | Controller | Quyền |
|---|---|---|---|
| `API-APR-01` | `GET /api/v1/admin/approvals` | `ApprovalAdminController#list` | Authenticated `OPERATIONS`/`ADMIN` + scope server-side |
| `API-APR-02` | `GET /api/v1/admin/approvals/{approvalId}` | `ApprovalAdminController#detail` | như trên, cùng scope |

Response: `ApprovalPage` = `{items:[ApprovalSummary], page:{number,size,totalElements,totalPages,first,last},
asOf}`; detail = `ApprovalSummary` + `payload` mask (không `expectedVersion`) + `result` nếu provider đã trả
(`ADM-OI-11` vẫn mở cho persist `result` sau APPLIED). `Cache-Control: no-store`, `X-Correlation-ID` echo.

## 2. Filter, phân trang, sort (07 §2.5/§8.8.3)

| Tham số | Quy tắc triển khai |
|---|---|
| `requestType` | `LIMIT_CHANGE`/`ROLE_CHANGE` (loại chưa mở → 400) |
| `status` | enum `PENDING/APPROVED/REJECTED/APPLIED/CANCELLED`; sai → 400 |
| `makerId`, `checkerId` | `ID` dương (`[1-9][0-9]{0,18}`), chỉ là filter **trong** scope |
| `from` / `to` | `from` inclusive, `to` exclusive; mặc định `now-30d .. now`; `from >= to` hoặc > 366 ngày → 400 |
| `page`, `size` | 0-based, mặc định 0/20, size 1–100; vượt biên/không phải số → 400; page vượt cuối → `items: []` |
| `sort` | whitelist duy nhất `createdAt` + `asc/desc` (mặc định desc); tie-breaker `approvalId` để phân trang ổn định |

Validation fail-closed tại adapter (`AdminHttpSupport`): enum/ID/Instant/số sai → `400 VALIDATION_ERROR`, không lộ
binding detail; sort do service whitelist. Query dùng constructor projection chỉ cột summary (**không đọc JSONB
`payload`**), tránh N+1 (một query page + một count, displayName resolve theo batch qua `IdentityUserQuery`).

## 3. Scope enforcement (DEC-ADM-009)

- Actor lấy từ session tin cậy (`AdminAuthorization`), không nhận `makerId`/`checkerId` từ client làm quyền.
- `OPERATIONS`/`ADMIN` role gate (chain + service re-check). Ngoài ra:
  - Actor luôn thấy request `maker_id = actor`.
  - Có `CHECKER` + `ROLE_MANAGE` → thấy mọi `ROLE_CHANGE`; có `CHECKER` + `LIMIT_MAKER|LIMIT_MANAGE` → thấy mọi
    `LIMIT_CHANGE`.
- Predicate scope nằm trong **cùng** JPQL với filter và `countQuery` → `totalElements` không rò record ngoài scope.
  `makerId`/`checkerId` không thể mở rộng scope; filter chéo maker trả rỗng.
- Detail dùng cùng predicate: id ngoài scope và id không tồn tại đều `404 APPROVAL_NOT_FOUND` (chống enumeration).
- Read-only: transaction `readOnly`, không gọi provider, không đổi `approval_request`, không tạo audit C2/C3 thường
  (401/403 vẫn do chain + `SecurityAccessDeniedAuditor` xử lý như các endpoint khác).

## 4. Test evidence

| Lớp | Nội dung | Kết quả |
|---|---|---|
| `ApprovalReadHttpPostgreSqlIntegrationTest` (mới) | 401 không audit; 403 CUSTOMER + đúng 1 C3 `APPROVAL_REQUEST`; maker chỉ thấy request của mình; filter đơn/ghép/from-to; page scoped (`totalElements=2`, page 0/1); 400 cho enum/page/size/sort/from-to sai; detail mask; out-of-scope = unknown = 404; checker LIMIT-only thấy LIMIT rows, không thấy ROLE rows; ADMIN+CHECKER thấy đủ; read không mutate/no audit | 9 PASS |
| `ApprovalAdminControllerTest` (mới) | shape `ApprovalPage` (12 field summary, không payload), `first/last`, no-store, 400 trước/sau service, detail mask, 404 thống nhất, actor lookup dedupe | 8 PASS |
| `ApprovalReadAuthorizationTest` (mới) | 401/403, maker-only, checker theo loại, non-session principal | 7 PASS |
| `ApprovalRequestQueryPostgreSqlIntegrationTest` (+1) | scope SQL: maker-only, reviewer theo loại, count scoped, filter không mở rộng, detail ngoài scope empty | 9 PASS |
| `ApprovalQueryServiceTest` (+1) | visibility bắt buộc, page/sort/window/filter validation | 10 PASS |

Chạy:

```bash
./gradlew test --tests 'com.simulatedbank.banking.admin.*' \
  --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --console=plain
```

Kết quả: **BUILD SUCCESSFUL — 25 classes / 221 tests / 0 failure / 0 error / 0 skip** (PostgreSQL 16 +
Flyway + security chain thật). Broad regression (Admin + Common config/context/logging + ArchUnit + Customer admin
HTTP): **BUILD SUCCESSFUL — 39 classes / 330 tests / 0 failure**; `SafeJsonLogEncoderTest`/observability vẫn PASS
sau khi thêm controller (profile `app.identity.enabled=false` không tạo bean controller). Full build
`./gradlew cleanTest build`: **BUILD SUCCESSFUL** (9m15s) — **111 classes / 1281 tests / 0 failure / 0 error / 0 skip**.

## 5. Acceptance checklist

| # | Yêu cầu | Trạng thái |
|---|---|---|
| 1 | List không filter | PASS |
| 2 | Từng filter + kết hợp | PASS |
| 3 | Biên `from` inclusive / `to` exclusive | PASS (HTTP + DB-02 PG) |
| 4 | Enum sai / pagination sai / sort ngoài whitelist | PASS (400) |
| 5 | Pagination ổn định + count theo scope | PASS |
| 6 | Maker scope / checker scope / loại trừ ngoài scope | PASS |
| 7 | Detail success + mask | PASS |
| 8 | Inaccessible + nonexistent = 404 | PASS |
| 9 | Không mutate, không audit read | PASS |
| 10 | OpenAPI filters/pagination/authorization/schema | PASS (mô tả scope + 404 + mask) |

**Blocker/OPEN:** `ADM-OI-06` — phân công checker theo request/tenant và data-scope đa tenant chưa có model;
implementation hiện dùng scope theo loại approval như đã ghi tại `07` §8.8.5. `ADM-OI-11` — persist `ApprovalDetail.result`
sau `APPLIED` chưa có cột/provider read.
