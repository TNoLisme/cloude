# BE-CUS-IMP-10 — Staff search / full-PII access

> **Ngày:** 17/11/2026 · **Branch:** `datht-backend-rework` · **Baseline:** `60809371ca97b6c9e2f9859d2cb7cc3c22d9ec01`
> **Trạng thái:** **P2 BLOCKED** — `OI-API-08/09` vẫn là open issue; không có BA/Security approval cho staff search hoặc full-PII disclosure.
> **Quyết định:** [DEC-CUS-008](../../../../DECISIONS.md) · Modules contract §6.6.

## A. Repository và contract findings

Working tree ban đầu có 27 file tracked thay đổi và 36 path untracked từ các task Customer/Identity/Common đang thực hiện. Task này không overwrite hoặc xóa thay đổi đó. Phần Customer đã hoàn tất có liên quan là:

- `GET /api/v1/admin/customers/{customerId}` (`API-ADM-01`): tra cứu trực tiếp bằng `customerId`, bắt buộc `CUSTOMER_READ`, trả `CustomerProfile` đã mask toàn bộ PII cho staff.
- `CustomerPiiService.getForStaff`: decrypt trong module Customer rồi mask `fullName`, phone và email; không có projection full-PII cho staff.
- Identity RBAC chỉ seed `CUSTOMER_READ`; không có permission staff-search hoặc full-PII riêng.
- `CustomerProfileStore` chỉ có `findById`; không có query search, pagination, blind index theo tên/phone/email hoặc API enumeration.
- Audit production vẫn là skeleton; Customer hiện chỉ có C3 `SECURITY_ACCESS_DENIED` bằng structured security log + metric cho truy cập bị từ chối.

Nguồn authoritative đã đối chiếu:

- `docs-common/03. SRS.md`: FR-039, FR-049, FR-052; DAR-002/004; AC-023/027/029 chỉ chốt masking, access trong scope, quyền và mục đích hợp lệ. Không định nghĩa search field, result field, page bound hoặc full-PII purpose workflow.
- `docs-common/07. API contracts.md`: §3.4–3.6, §8.2.4 chốt `API-ADM-01` masked detail; §11.3 vẫn liệt kê `OI-API-08` và `OI-API-09` là open issue.
- OpenAPI v1 chỉ có `/admin/customers/{customerId}` và `/admin/customers/{customerId}/accounts`; không có collection search hoặc full-PII operation/schema.
- `docs-common/design/01. audit-traceability.md`: read thông thường không tạo audit action; deny dùng `SECURITY_ACCESS_DENIED` C3; catalog không có action full-PII disclosure.
- `docs-common/design/04. security.md`: PII mặc định mask, staff cần RBAC; audit cấm `fullName`, email, phone. Tài liệu không cấp quyền full-PII.
- `docs-common/design/05. Modules contract.md` §6: chỉ có minimal lookup và masked view; staff scope vẫn thiếu.

## B. Mandatory gate classification

`APPROVED` nghĩa là nguồn chuẩn đã chốt policy đủ dùng; `IMPLEMENTED` nghĩa là runtime hiện có đúng phạm vi đã duyệt. Một gate có nền tảng chung đã duyệt nhưng thiếu contract riêng cho IMP-10 vẫn được phân loại `BLOCKED`.

| Gate | Phân loại | Repository evidence | Điều kiện còn thiếu |
|---|---|---|---|
| Staff-search scope và business use case | **BLOCKED** | FR-049 chỉ cho staff xem dữ liệu trong scope; `OI-API-08` vẫn nằm ở §11.3 Open issues | BA/Admin chốt use case, search fields/match semantics, minimum specificity, anti-enumeration, result fields, sort/page/max size |
| Staff roles và explicit permissions | **BLOCKED** | `CUSTOMER_READ` được APPROVED/IMPLEMENTED cho masked detail; V2/JPA authority allowlist không có search/full-PII permission | Permission riêng cho search nếu policy yêu cầu; bắt buộc permission riêng cho full PII; role mapping và deny semantics |
| Full-PII disclosure policy | **BLOCKED** | DAR-004 chỉ nêu nguyên tắc quyền + mục đích; `07` §3.6/§8.2.4 cấm full PII trong MVP và dẫn `OI-API-09` | Security/BA approval cho purpose, eligible actor, target scope, field allowlist, retention/session/step-up nếu có |
| Field-level visibility và masking | **BLOCKED** | Masked `CustomerProfile` đã IMPLEMENTED; staff `fullName` oracle vẫn tạm theo CUS-OI-02; không có full-PII DTO | Approved search-result projection, exact field mask, full-PII allowlist; owner chốt CUS-OI-02 |
| `OI-API-08` search contract | **MISSING** | Chỉ có đề xuất `GET /admin/customers?q=`; không có API ID, parameters, schema, validation, pagination, errors hoặc OpenAPI path | API/Admin/BA phê duyệt contract đầy đủ |
| `OI-API-09` full-PII contract | **MISSING** | Chỉ có đề xuất “permission + audit”; không có route, schema, permission code, purpose metadata hoặc audit action | API/Security/Audit/BA phê duyệt contract đầy đủ |
| API versioning và compatibility baseline | **APPROVED** | `07` §1.4/§2.1: `/api/v1`; breaking change phải dùng `/v2`; contract-first | Khi mở gate, owner phải phân loại endpoint/schema mới là v1 additive hay v2; hiện không được tự chọn route |
| Audit logging requirements | **BLOCKED** | Deny C3 đã IMPLEMENTED cho masked detail; success read mặc định chỉ technical log; Audit module/durable `audit_log` chưa có; catalog thiếu full-PII action | Audit owner chốt success/deny event, class/failure policy, actor/target/purpose fields và provider durable; payload luôn cấm raw PII |
| Privacy và customer/data scope | **BLOCKED** | DAR-002/004 và 07 §3.6 APPROVED; service permission check đã IMPLEMENTED; CUS-OI-05 chưa có model/runtime | Admin/Security/BA chốt organizational/data scope và purpose validation trước lookup/decrypt |

Kết luận gate: versioning baseline là `APPROVED`; masked-detail permission/masking/deny controls là `IMPLEMENTED`. Tất cả gate tạo staff search hoặc full-PII response vẫn `MISSING` hoặc `BLOCKED`, nên không đủ điều kiện thêm production code.

## C. Authorized functionality và clean exclusion

Không có phần staff-search/full-PII mới nào được authorized độc lập. Phần đã duyệt được giữ nguyên, không nhân bản:

1. Exact-ID staff detail tiếp tục dùng `API-ADM-01`, `CUSTOMER_READ`, server-side session principal, service re-check và masked `CustomerProfile`.
2. Caller thiếu permission bị 403 trước lookup; Customer và forged role/permission header không bypass được Identity authority snapshot.
3. PII chỉ decrypt trong Customer service rồi mask; response/log/error/audit deny không chứa plaintext, ciphertext hoặc key.
4. Không thêm collection route, repository search, `Pageable`, blind index, normalization, pagination, sort, new DTO hoặc permission.
5. Không thêm full-PII route/projection, purpose parameter, decryption path cho staff, audit action hoặc fallback giả.
6. Không thay `API-ADM-01` để trả thêm field; không sửa OpenAPI v1, migration, RBAC seed hoặc module khác.

Đây là exclusion bắt buộc, không phải scaffold. Khi contract được duyệt, triển khai phải bắt đầu contract-first trong `07` + OpenAPI, sau đó clean cutover qua Identity permission, Admin scope và Audit provider thật.

## D. Authorization, privacy, audit và versioning status

| Control | Hiện trạng | IMP-10 status |
|---|---|---|
| Authentication | Identity opaque session → Common filter → trusted principal | **IMPLEMENTED** cho API hiện hữu |
| Masked-detail permission | `CUSTOMER_READ` tại chain + service | **IMPLEMENTED** |
| Search permission | Không có code/role mapping được duyệt | **BLOCKED** |
| Full-PII permission | Không có; `CUSTOMER_READ` không được diễn giải thành full PII | **BLOCKED / deny by absence** |
| Staff data scope | Không có organization/assignment/scope model | **BLOCKED** (`CUS-OI-05`) |
| Field masking | Staff name/phone/email masked; no-store response | **IMPLEMENTED**, nhưng name oracle còn chờ CUS-OI-02 |
| Full-PII decryption | Không có staff-facing projection/endpoint | **EXCLUDED** |
| Audit deny | `SECURITY_ACCESS_DENIED`, actor/roles/target/outcome/correlation/request ID, không PII | **IMPLEMENTED** dạng C3 log/metric; durable Audit còn BLOCKED |
| Search/full-PII success audit | Không có approved action/failure semantics/purpose metadata | **BLOCKED** |
| API compatibility | Không đổi route/schema hiện hữu | **PRESERVED** |

## E. Acceptance and test coverage

| Required scenario | Status | Evidence / blocker |
|---|---|---|
| Unauthorized request rejected | **PASS** cho masked detail | HTTP integration: anonymous 401 |
| Ordinary customer cannot access staff API | **PASS** cho masked detail | HTTP integration: CUSTOMER 403 trước lookup + C3 evidence |
| Staff without explicit permission rejected | **PASS** cho masked detail | AUDITOR không có `CUSTOMER_READ` → 403 |
| Search permission does not grant full PII | **BLOCKED / capability absent** | Không có search permission hoặc full-PII permission/endpoint; `CUSTOMER_READ` hiện chỉ trả masked detail |
| Approved roles see only approved fields | **PASS** cho masked detail | Exact 8-field `CustomerProfile`; name/phone/email masked |
| Cross-scope access rejected | **BLOCKED** | CUS-OI-05 chưa có scope contract/runtime; không thể tạo oracle giả |
| Search bounded and paginated | **BLOCKED / not implemented** | OI-API-08 thiếu query/pagination/max-size contract |
| No sensitive data in logs/errors | **PASS** cho masked detail | HTTP integration quét response/captured stdout; PII/token absent |
| Audit follows approved contract | **PARTIAL** | Deny C3 log/metric PASS; durable Audit và full-PII success event BLOCKED |
| Existing Customer APIs backward compatible | **PASS** | Không đổi production source, route, schema, DB hoặc OpenAPI |

Không thêm test giả cho search/full PII vì chưa có behavior contract để tạo expected result. Existing tests được chạy lại để chứng minh boundary được phép vẫn fail-closed và masked.

## F. Test execution

Chạy từ `digitalbanking-backend`:

```bash
./gradlew test \
  --tests 'com.simulatedbank.banking.customer.application.StaffCustomerDetailServiceTest' \
  --tests 'com.simulatedbank.banking.customer.adapter.in.CustomerAdminDetailControllerPostgreSqlIntegrationTest' \
  --rerun-tasks --max-workers=1 --console=plain
```

**PASS — 2 classes / 17 tests / 0 failure / 0 error / 0 skipped; BUILD SUCCESSFUL in 37s.**

Coverage thực tế: permission defense-in-depth, anonymous/customer/staff-without-permission deny, forged metadata, masked response, no response/stdout PII, validation/not-found, INACTIVE/null contacts, no-store/correlation, C3 access-denied evidence. Kết quả này không được diễn giải là approval hoặc test coverage cho search/full PII chưa tồn tại.

## G. Files changed by BE-CUS-IMP-10

- `digitalbanking-docs/docs-backend/modules/customer/BE-CUS-IMP-10.md`
- `digitalbanking-docs/docs-backend/modules/customer/README.md`
- `digitalbanking-docs/docs-backend/modules/customer/tasks/README.md`
- `digitalbanking-docs/docs-common/design/05. Modules contract.md`
- `DECISIONS.md`

Không thay đổi production source, test source, migration, OpenAPI hoặc RBAC seed.

## H. Outstanding dependencies and release gates

1. **BA/Admin/API — OI-API-08:** approved search use cases, fields, exact/partial semantics, normalization, minimum specificity, anti-enumeration, pagination/sort/max result, response/error/version contract.
2. **Security/BA/API — OI-API-09:** separate permission, eligible roles, purpose policy, customer/field scope, response schema, step-up/session controls nếu có, deny/non-existence semantics.
3. **Admin/Security — CUS-OI-05:** organizational/data-scope model và authoritative runtime provider.
4. **BA/Security/API — CUS-OI-02:** approved staff full-name masking oracle cho masked result.
5. **Audit:** durable `AuditModuleApi`/`audit_log`; action catalog, criticality/failure behavior và allowlisted purpose metadata cho full-PII access; audit payload vẫn cấm raw PII.
6. **Identity/DB:** approved permission codes và role mapping; không reuse `CUSTOMER_READ` cho full PII.
7. **API owner:** quyết định v1 additive hay v2 breaking sau khi schema được phê duyệt; cập nhật `07` và OpenAPI trước/cùng code.

## I. Final status

**P2 BLOCKED.** `OI-API-08/09` chưa được duyệt; staff data scope, full-name oracle và durable Audit còn thiếu. Không có staff search, customer enumeration hoặc staff full-PII path được thêm/enabled. Existing masked exact-ID detail giữ nguyên và focused regression 17/17 PASS. Chỉ chuyển trạng thái sau khi toàn bộ gate ở §B/§H có artifact approval và acceptance criteria executable.
