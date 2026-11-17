# BE-CUS Task breakdown & implementation checklist

> Baseline `dev2@56210cc`; documentation/planning only. `DONE` phải có source, test và evidence. `NOT_STARTED` là trạng thái code trong baseline. Các task dependency-blocked ghi ở gate.

| Checklist | Task | Priority | Deliverable | Dependencies | Acceptance criteria |
|---|---|---|---|---|---|
| [x] | BE-CUS-DOC-01 | P0 | Phân tích, design, task checklist, dependency/gap | docs-common 03/04/07, module contract | Doc viết đúng dev2, nêu conflict, không sửa source; DONE chỉ riêng documentation |
| [x] | BE-CUS-IMP-01 | P0 | Flyway migration `banking.customer` — `V5__create_customer_table.sql` | Identity `app_user` migration V2, DB owner conventions | DONE 16/11/2026: fresh PostgreSQL + 9 constraint scenarios + full 803-test build PASS; [evidence](../BE-CUS-IMP-01.md) |
| [x] | BE-CUS-IMP-02 | P0 | JPA entity, repository, DB mapping, unit/integration | IMP-01, Spring Data baseline | DONE 16/11/2026: no entity leakage; PostgreSQL inactive/not-found/sequential+concurrent duplicate tests PASS; [evidence](../BE-CUS-IMP-02.md) |
| [x] | BE-CUS-IMP-03 | P0 | AES-GCM encrypted PII, masking adapter dùng Common | IMP-02, Common crypto config | DONE 17/11/2026: no plaintext at rest/log/API, crypto fail-closed; unit 6/6, PostgreSQL 6/6, full build 824/824 PASS; [evidence](../BE-CUS-IMP-03.md) |
| [ ] | BE-CUS-IMP-04 | P0 | CustomerQuery public API + immutable minimal DTO | IMP-02/03, Identity/Account API agreement | userId→customerId, missing/status, no PII, no module cycle tests |
| [ ] | BE-CUS-IMP-05 | P0 | `GET /api/v1/customers/me` + service | IMP-04, Identity session binding | 200 exact CustomerProfile, 401/403/404, no-store, correlation, self ownership |
| [ ] | BE-CUS-IMP-06 | P1 | Staff `GET /api/v1/admin/customers/{customerId}` | IMP-04, Admin scope, Audit, CUS-OI-02/05 | CUSTOMER denied, staff permission+scope, 404, masked fullName/contacts, audit deny |
| [ ] | BE-CUS-IMP-07 | P1 | Identity/Account/Admin/Audit real integration | IMP-04–06 + owner integration | End-to-end principal/permissions/mapping, Account no repo access, audit evidence |
| [ ] | BE-CUS-IMP-08 | P1 | Security+API+DB regression and evidence | IMP-01–07 | PARTIAL 17/11/2026: Customer-focused 13 classes/94 tests PASS, 0 fail/error/skip; malformed/oversized auth, RFC 9457, response/log PII scan, plaintext-at-rest scan và FK rollback đã bổ sung. Full backend 900 tests: 899 PASS/1 FAIL do existing Identity t11 Argon2 OOM. `AC-024`/`CT-API-015` PASS trên Customer path. BLOCKED: exact `AC-005` (Account), `AC-027` scope, Maker-Checker branch `CT-API-016`, Notification/Audit portions `AC-023`/`CT-API-017`, exact reject/no-write `T-AUD-16`. [evidence](../BE-CUS-IMP-08.md) |
| [ ] | BE-CUS-IMP-09 | P2 BLOCKED | Provision/create/update/status workflow only after new approved contract | CUS-OI-01/03/04/06 | Explicit API/DB/authorization/audit/idempotency approval and tests |
| [ ] | BE-CUS-IMP-10 | P2 BLOCKED | Staff search/full-PII access only after approved scope | OI-API-08/09, BA/Security | Explicit permission/privacy/audit/version contract; not default MVP |

## Dependency report to lead

1. Identity + DB owner: ai tạo Customer row khi tạo `app_user` loại CUSTOMER; migration FK và version Flyway nào dùng?
2. API + BA + Security: chốt mask `fullName` khi staff tra cứu, và chính sách truy cập theo data scope.
3. Common + Identity: đảm bảo session authentication trả principal tin cậy và PII crypto key/config sẵn có.
4. Audit + Admin: contract ghi `SECURITY_ACCESS_DENIED`, quyền `CUSTOMER_READ` và error handling khi audit không khả dụng.
5. BA + DB: xác nhận có mở rộng national ID/CIF/KYC hay giữ schema chuẩn; hiện tại **không thêm**.
6. Account + Transfer: chính sách `INACTIVE` và CustomerQuery snapshot ảnh hưởng đến giao dịch; không giả định KYC.

## Thứ tự thực hiện

Gate A (schema/contract): DOC-01, OI gates → Gate B (persistence): IMP-01 → IMP-02 → IMP-03/04 → Gate C (REST): IMP-05 và IMP-06 → Gate D (integration): IMP-07 → IMP-08. IMP-09/10 chỉ được lập kế hoạch chi tiết sau khi có phê duyệt phạm vi.

**Status labels:** NOT_STARTED / IN_PROGRESS / PARTIAL / BLOCKED / DONE. Khi tick DONE, gắn commit SHA, lệnh test, kết quả và evidence. Khi phụ thuộc chặn, ghi BLOCKED riêng thay vì bỏ task.

## Implementation evidence

- **BE-CUS-IMP-01 — DONE (16/11/2026):** Flyway V5 tạo `banking.customer` đúng `04` §4.2.2; Identity V2 chạy trước; focused 9/9, migration regression và `./gradlew build --rerun-tasks` (803/803) PASS. Chi tiết: [BE-CUS-IMP-01.md](../BE-CUS-IMP-01.md), quyết định version: [DEC-CUS-001](../../../../../DECISIONS.md).
- **BE-CUS-IMP-02 — DONE (16/11/2026, working tree chưa commit):** `CustomerEntity`/`CustomerStatus` khớp Flyway V5; repository lookup ID/user/existence/status; focused JPA 6/6, V5 migration 9/9 và `./gradlew build` 809/809 PASS. Chi tiết: [BE-CUS-IMP-02.md](../BE-CUS-IMP-02.md).
- **BE-CUS-IMP-03 — DONE (17/11/2026, working tree chưa commit):** `CustomerPiiService` + Common crypto/masking adapter mã hóa name/phone/email trước JPA, fresh IV mỗi lần, owner check trước decrypt, phone/email masked, tamper/missing-key fail-closed; unit 6/6, PostgreSQL 6/6, focused IMP-02/Common 7/7, full `./gradlew build` 61 classes/824 tests PASS. Chi tiết: [BE-CUS-IMP-03.md](../BE-CUS-IMP-03.md), quyết định phạm vi: [DEC-CUS-002](../../../../../DECISIONS.md).
- **BE-CUS-IMP-08 — PARTIAL (17/11/2026):** bổ sung malformed/oversized Bearer fail-closed, required RFC 9457 fields/no-internal disclosure, trace IDs cho `CT-API-015/016/017`, stdout PII/secret scan, plaintext byte-sequence scan ở PostgreSQL và FK-failure atomic rollback. Focused Customer/migration/security/architecture **13 classes / 94 tests / 0 failure/error/skip PASS**. Full backend **900 tests / 899 PASS / 1 FAIL** do existing `IdentityAuthPostgreSqlIntegrationTest.t11` Argon2 OOM, không thuộc Customer. Exact `AC-005`, `AC-027`, full `CT-API-016/017` và `T-AUD-16` vẫn BLOCKED bởi Account, Admin scope/Maker-Checker, Notification và durable Audit; chi tiết [BE-CUS-IMP-08.md](../BE-CUS-IMP-08.md).
