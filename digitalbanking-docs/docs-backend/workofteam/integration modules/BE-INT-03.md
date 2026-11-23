# BE-INT-03 — Tích hợp session, Customer và Account read

> **Trạng thái:** **Verified** cho lát đọc single-bank MVP ngày 19/11/2026. Xem [báo cáo kiểm chứng](BE-INT-03-verification.md).  
> **Ngày:** 18/11/2026  
> **Người thực hiện/chốt:** người điều hành tích hợp, theo [DEC-INT-003/008/009](../../../../DECISIONS.md).  
> **Tiền đề:** [BE-INT-02](BE-INT-02.md) đã xác minh G1 trên DB canonical mới V1–V19; kết quả đó không thay bằng chứng BE-INT-03.

## 1. Mục tiêu và ranh giới

Ghép **Identity session thật → Customer identity/PII provider thật → Account read/ownership provider thật** trong một Spring Boot deployable, cùng PostgreSQL canonical. Hoàn thiện binding `Session.customerId` và `AuthResponse.user.customerId`, xác nhận 5 route đọc hiện có dưới principal thật, rồi chứng minh 401/403/404/503, IDOR, masking và deny audit bằng HTTP/PostgreSQL. Không tạo API read thứ hai trong Admin, không đọc bảng/implementation của module khác, không dùng fake làm production bean.

Nguồn theo thứ tự ưu tiên: `docs-common/03. SRS.md` FR-008–012/039/049 và AC-005/023/024/027; `04. Database design & analysis.md` §§4.2–4.3; `07. API contracts.md` §§2.2/2.6/3.4–3.6/8.1.7/8.2; `design/05. Modules contract.md` §§3/5.1/6/7/13; backend architecture §1.5. [DEC-INT-008](../../../../DECISIONS.md) chốt hai nghĩa còn thiếu cho MVP: binding session và staff data scope. Contract chưa có cho staff search/full PII, tenant/branch/segment hoặc policy chặn đọc khi Customer `INACTIVE`; không tự mở rộng trong task này.

## 2. Hợp đồng và trách nhiệm từng module

| Seam | Provider → consumer | Hành vi bắt buộc |
|---|---|---|
| Session | `identity.adapter.out.IdentitySessionAuthenticator` implement duy nhất `common.config.SessionAuthenticator`; Common filter → controller | Token opaque được xác thực với session ACTIVE/chưa hết hạn, user ACTIVE và authority snapshot hiện hành. `Session.userId` tin cậy; `Session.customerId` chỉ là snapshot server-side. Token sai/hết hạn/revoked/user không hợp lệ → 401; DB/authority/Customer provider lỗi → 503, không cấp principal. Không log token/CSRF. |
| User ↔ Customer | `customer.api.CustomerQuery.findByUserId(long)` → Identity/Account/Customer self; `findByCustomerId(long)` → Account staff list | Read-only, trả `Optional<CustomerIdentity(customerId,userId,status)>`, không PII; `INACTIVE` vẫn trả snapshot. Empty khác lỗi hệ thống; không tự tạo row. Identity chỉ phụ thuộc `customer.api`, Customer không import Identity internals. |
| Hồ sơ | Customer application/controller sở hữu `GET /customers/me`, `GET /admin/customers/{customerId}` | Self lấy `userId` từ principal, tra Customer theo userId, kiểm ownership trước decrypt. Staff cần `CUSTOMER_READ` và role staff, kiểm trước lookup. Self fullName được phép, phone/email mask; staff cả fullName/phone/email mask. |
| Tài khoản | `account.api.AccountQuery` + `AccountAccessService`/controller sở hữu `GET /accounts`, `GET /accounts/{accountId}`, `GET /admin/customers/{customerId}/accounts` | Self resolve `userId → customerId` bằng CustomerQuery; `AccountQuery` kiểm ownership theo account row. Staff cần `ACCOUNT_READ` và role staff. Response chỉ có account number masked/last4; money là string scale 2; `asOf` có mặt. Account không đọc/decrypt Customer PII. |
| Deny audit | Customer/Account C3 adapter → Audit provider/fallback theo `design/01` §5.7 | 403 sau authentication ghi `SECURITY_ACCESS_DENIED` với actor/target/correlation, không PII/secret. Lỗi C3 không biến deny thành success; 401/404 và read thành công không phát security-deny event. |

**Session binding được chốt:** Với user `CUSTOMER` có row, `Session.customerId`, response login sau MFA và `GET /auth/session` cùng trả ID decimal của Customer. Row `INACTIVE` vẫn trả ID. CUSTOMER thiếu row: phiên có `customerId=null`, các API self trả `404 CUSTOMER_NOT_FOUND`; staff luôn `customerId=null`. Không đồng nhất `userId` với `customerId`, không lấy ID/role/scope từ request. Identity phải xử lý provider failure riêng với `Optional.empty()` và nhất quán ở login, session refresh, request đã đăng nhập. Ownership ở Customer/Account vẫn kiểm authoritative Customer/Account row mỗi request; snapshot session không là quyền truy cập độc lập.

**Staff scope MVP:** single-bank, không có tenant/branch/segment trong schema hoặc role assignment. Staff được đọc exact ID trên toàn ngân hàng theo permission hiện có, với projection mask. `OPERATIONS`/`ADMIN`: `CUSTOMER_READ` + `ACCOUNT_READ`; `AUDITOR`: `ACCOUNT_READ` nhưng không có `CUSTOMER_READ`; CUSTOMER: chỉ quyền self. Một principal vừa có `ROLE_CUSTOMER` vừa có permission staff bị từ chối 403 trước lookup. Không dùng header/query/body để tăng scope. Quyết định này áp dụng cho 3 route đọc nêu trên; search/full PII và tenant/branch/segment cần contract riêng.

**Chênh lệch response đã chọn:** `GET /accounts/{accountId}` hiện trả `owner{customerId,status}` và chưa có `owner.fullName` vì `CustomerQuery` không cung cấp masked name. `owner.status` là trạng thái Customer và được đọc qua CustomerQuery theo DEC-INT-009. Giữ projection tối thiểu này trong BE-INT-03, đối chiếu `07`/OpenAPI và ghi deviation tại report. Không thêm fullName bằng cách đọc Customer internals. Nếu cần khớp field OpenAPI bắt buộc cho release, mở một provider-owned masked view contract và test riêng trước BE-INT-11/G3.

## 3. Luồng triển khai

1. **Đối chiếu wiring:** xác nhận một `SessionAuthenticator` thật, Common filter/chain lấy session token cookie hoặc Bearer theo `07` §2.2, các bean Identity/Customer/Account bật cùng profile và cùng datasource. Kiểm `SessionAuthenticator.Session`/`AuthResponse` không dùng giá trị client tự khai cho `customerId`.
2. **Hoàn thiện binding Identity → Customer:** gọi `CustomerQuery.findByUserId` tại nơi tạo session/response phù hợp; ánh xạ `Optional` và status theo §2, DB error fail-closed. Giữ response login và `/auth/session` nhất quán; CSRF rotation/session revocation vẫn theo contract Identity hiện có. Không thêm cột Customer ID vào session hoặc migration khi mapping có thể đọc qua provider.
3. **Đối chiếu read paths:** Customer self/staff và Account self/detail/staff list dùng provider API hiện có. `GET /admin/customers/{customerId}/accounts`: `findByCustomerId` empty → 404, customer tồn tại không account → 200 `items=[]`; không gộp hai trường hợp. Status filter/ID path được validate trước DB. Permission và role staff phải được kiểm lại ở service, đặc biệt Account detail staff branch.
4. **Khóa quyền và dữ liệu:** kiểm 401 trước handler; 403 trước lookup khi thiếu permission/role không hợp lệ; owner mismatch 403 + C3; không tồn tại 404 đúng resource code. Inactive vẫn có thể đọc với status thật, không tự gán eligibility cho Transfer. Response `Cache-Control: no-store`, `X-Correlation-ID`, Problem không lộ SQL/PII, account number/phone/email/name mask đúng vai trò.
5. **Tích hợp Audit C3:** dùng provider `audit.api.AuditModuleApi.recordStandalone` nếu wiring hiện có, fallback security log + metric khi C3 lỗi theo contract. Test deny ở chain và service; không sinh audit cho read thành công hoặc 404 thông thường. Nếu provider C3 chưa khả dụng trong lát triển khai, dùng fake chỉ trong test theo cùng DTO/lỗi và giữ phần durable audit BLOCKED trong report.
6. **Dọn chênh lệch tài liệu:** cập nhật `05. Modules contract.md`, OpenAPI/route DTO nếu thay đổi public shape; ghi quyết định thay nghĩa ở `DECISIONS.md`. Không sửa migration V1–V19 đã áp dụng. Không đánh dấu G0/G2/G3 PASS từ riêng task này.

## 4. Ma trận acceptance HTTP/PostgreSQL

Mọi case dùng DB canonical mới PostgreSQL 16, user/session/Customer/Account thật với ID tổng hợp khác nhau; OTP gateway có thể là test double **chỉ để tạo session sau MFA**. Kỳ vọng theo từng vector được đối chiếu với kết quả thực tế trong [báo cáo kiểm chứng](BE-INT-03-verification.md).

| ID | Vector | HTTP/DB kỳ vọng |
|---|---|---|
| INT03-S01 | CUSTOMER active, mapping Customer ACTIVE, login/MFA rồi `/auth/session` và `/customers/me` | 200; cùng `customerId` từ provider ở auth response/session/self; profile self có fullName, phone/email masked; không tạo Customer row. |
| INT03-S02 | CUSTOMER có row `INACTIVE`; CUSTOMER không có row; staff session | Inactive vẫn có ID/status; thiếu row cho auth `customerId=null`, self/account self 404 `CUSTOMER_NOT_FOUND`; staff `customerId=null`. DB failure ở Customer lookup → 503, không trả null/404/401 giả. |
| INT03-S03 | Không token, token sai/hết hạn/logout/revoked, user disabled, role bị thu hồi | 401 `AUTH_SESSION_EXPIRED` hoặc 403 do quyền hiện hành theo từng case; không có dữ liệu business. Role change có hiệu lực request kế tiếp; CSRF/session refresh theo Identity contract. |
| INT03-C01 | Self Customer A gọi `/customers/me`, `/accounts`, `/accounts/{A}` | 200; chỉ account A; ID từ principal mapping; account number chỉ last4, money string scale 2, asOf/no-store/correlation. |
| INT03-C02 | A gọi `/accounts/{B}`; path ID B tồn tại; ID không tồn tại/ID sai định dạng | 403 `AUTH_FORBIDDEN` + C3 cho B; 404 `ACCOUNT_NOT_FOUND` cho vắng; 400 `VALIDATION_ERROR` cho ID sai. Không có PII B trong body/log. |
| INT03-C03 | A gửi header/query `customerId=B`, `userId=B`, role/permission giả; hoặc snapshot CUSTOMER lẫn permission staff | Không đổi principal/scope; không thấy Customer/Account B. Mixed role bị 403 trước lookup. |
| INT03-T01 | OPERATIONS/ADMIN có `CUSTOMER_READ` gọi `/admin/customers/{id}`; AUDITOR/CUSTOMER/thiếu permission gọi cùng route; ID vắng/sai định dạng | Staff hợp lệ 200 với fullName/phone/email masked; staff có quyền gặp ID vắng → 404 `CUSTOMER_NOT_FOUND`, ID sai → 400 `VALIDATION_ERROR`; thiếu quyền/role → 403 + C3 trước lookup, kể cả ID tồn tại/vắng. |
| INT03-T02 | Staff có `ACCOUNT_READ` gọi `/accounts/{id}` và `/admin/customers/{id}/accounts`; CUSTOMER/AUDITOR thiếu permission tương ứng | Staff 200 masked; CUSTOMER chỉ được detail thuộc mình và không vào list staff; thiếu permission 403. AUDITOR được account read, không được customer detail. |
| INT03-T03 | Customer ID vắng; Customer tồn tại chưa có Account; Account ID vắng; status filter sai | Staff list: 404 `CUSTOMER_NOT_FOUND`, 200 `items=[]`, 404 `ACCOUNT_NOT_FOUND` cho detail, 400 filter sai. Provider DB failure → 503/5xx, không giả thành empty/404. |
| INT03-A01 | Deny tại chain và service; C3 provider lỗi | 403 giữ nguyên; security event hoặc fallback log/metric không chứa PII/token; 401/404/200 không ghi deny. |

Kiểm no-write bằng row count/checksum phù hợp cho `customer`, `account` và business tables trước/sau GET; session idle refresh/CSRF rotation là side effect Identity đã có, không xem là business write. Chạy provider/consumer với cùng vectors, không dùng mock để tuyên bố bằng chứng DB/authorization thật.

## 5. Điều kiện đóng và báo cáo

- [x] Source hoàn thiện các seam §2–3, không import private entity/repository/module implementation; migration canonical vẫn validate.
- [x] Case §4 có JUnit/HTTP/SQL artifact và kết quả thực tế, không có leak trong response/structured log; 401/403/404/503/IDOR/masking/C3 đều được chứng minh với provider thật.
- [x] Đối chiếu OpenAPI cho 5 route và `AuthResponse`; ghi rõ deviation `owner.fullName` và bất cứ field/error còn lệch.
- [x] Report riêng ghi **status, scope, requirement, environment, source revision, exact command, expected/actual, artifacts, limitations** theo Test Plan §10; lưu cả run FAIL/BLOCKED. Dùng `--tests` cho class/method liên quan theo DEC-INT-007; chỉ mở rộng khi có căn cứ cụ thể, không tự chạy full suite.
- [x] Chỉ chuyển BE-INT-03 thành **Verified** khi các case thực chạy PASS. Gate G0/G2/G3 và money path vẫn được xét độc lập; BE-INT-04..10 xử lý Limit/Transfer/T2/Audit/Notification/recovery.

## 6. Rủi ro và dependency còn lại

- `owner.fullName` ở AccountDetail khác response ví dụ `07` và có thể khác OpenAPI; provider masked name chưa có. Không trả raw PII để lấp field.
- Quyết định staff scope chỉ cho single-bank MVP. Khi có org/tenant/branch hoặc giới hạn nhân sự, cần scope source authoritative, policy query và test cross-scope trước triển khai môi trường đó.
- `INACTIVE` vẫn đọc được theo contract hiện hành; eligibility cho mutation/transfer là CUS-OI-06 và BE-INT-04/05, không suy từ read tests.
- Test session sau MFA có thể cần OTP transport fake test-only khi Notification delivery chưa nối; Identity/Customer/Account và PostgreSQL vẫn phải là provider thật. Các dependency khác thiếu operation phải ghi phương án vào `05. Modules contract.md` trước khi dùng.
