# E4 — Kiểm chứng sau khi chuyển lên dev2 sạch

> **Ngày cập nhật:** 16/11/2026
> **Nhánh/nền:** codex/limit trên origin/dev2 56210cc
> **Kết quả hiện tại:** 213 kiểm thử liên quan đạt, build thành công. Code limit/admin đã được chuyển; audit/identity/customer/account thật và GET E3/transfer thật còn Blocked.

## 1. Thay đổi nền và giới hạn nghiệm thu

Theo yêu cầu người dùng, chỉ chuyển 6 commit feature từ phuong/limit; không mang cụm commit audit/fraud/notification đã bị loại vào dev2. Chi tiết mapping commit, conflict và điều kiện PR tại [DEV2-MIGRATION.md](DEV2-MIGRATION.md).

Dev2 chưa có implementation audit mới. Nhánh này chỉ giữ AuditModuleApi contract; không có AuditRecorder/ApprovalAuditService hoặc migration V2 cũ. Vì vậy những kết quả 159/294 tests và converge trên nền cũ là lịch sử, không chứng minh audit production hoạt động trên nhánh sạch.

## 2. Phần đã triển khai và kiểm chứng

- LIM-07/08: prepare pending, approve/reject, version history, policy, expected current version dưới khóa, D4, giữ usage và phối hợp E2.
- LIM-09: POST /api/v1/admin/limits tạo approval_request thật và config pending; trả 201 + Location chuẩn. LIM-10: POST /api/v1/admin/limits/{id}/approve quyết định APPLIED/REJECTED đúng một lần, trả 200; không trả result cho reject.
- Admin/limit dùng PostgreSQL thật. Actor/permission/CSRF và JSON validation chạy qua common security thật; native provider session/step-up/scope chưa có nên test dùng fake đã ghi rõ.
- Audit là dependency bắt buộc qua public API; production thiếu audit bean thì 503 trước mutation. Không có fallback writer giả production.
- Policy max phải cấu hình; từ chối future effectiveFrom, hiệu lực khi approve; D4 giữ pending nếu account scope mới trong ngày customer đã dùng; self-decision 409 ROLE_CONFLICT. Các quyết định clarify cũ được giữ.
- Challenge maker bind actor/action/hash payload; checker thêm approvalId/decision/proposal hash. Contract test chống đổi actor/payload/purpose/approval, expiry và reuse.

## 3. Thành phần thật và test double

| Thành phần | Bằng chứng hiện tại | Chưa chứng minh |
|---|---|---|
| PostgreSQL/Flyway V1 và V3–V6, admin approval, limit config/usage | Implementation và SQL thật; concurrency, constraints/grants, commit/rollback | Không có audit schema production; FK liên module còn staged |
| Session/step-up/staff/customer/account | Fake chỉ trong test, dùng cùng candidate API; common filter/CSRF thật | Login/OTP/FIDO/expiry/revocation/ownership thật |
| AuditModuleApi | SqlAuditFixture test-only; INSERT vào limit_test.audit_event cùng transaction caller, denial dùng transaction riêng của fixture | Audit writer thật, append-only/hash chain/C3 fallback vận hành/retention |
| Một số biên ngày | Spy nguồn thời gian, dữ liệu/transaction PostgreSQL thật | Không phải bằng chứng thời gian vận hành thực tế |
| Unit tests | MockEnvironment/repository/provider mock phù hợp | Không dùng mock để khẳng định lock hoặc atomicity production |

SqlAuditFixture và init-roles.sql nằm trong src/test, không được đóng gói vào JAR. Các ca SQL fixture lỗi chứng minh rollback của consumer/caller, không được gọi là kiểm chứng AuditRecorder thật. Không có bảng customer/identity placeholder production.

## 4. Kết quả chạy trên nền sạch

Từ digitalbanking-backend/:

```sh
./gradlew clean test --tests 'com.simulatedbank.banking.admin.*' --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.common.config.HttpSecurityTest' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --offline --console=plain --max-workers=2
./gradlew build -x test --offline --console=plain --max-workers=2
```

**213 tests / 0 failure / 0 error / 0 skip**, BUILD SUCCESSFUL trong 52 giây; package build thành công. Không chạy full regression các module không liên quan. Test bao gồm thiếu audit provider và giữ các ca approval/config/usage rollback qua SQL dependency fixture; không skip ca kiểm thử để che dependency thiếu.

Kết quả 213 thay cho 294 ở nền cũ: loại 82 tests của implementation audit không được chuyển và thêm 1 vector fail-closed khi audit vắng mặt. XML tại digitalbanking-backend/build/test-results/test/TEST-*.xml, HTML tại build/reports/tests/test/index.html. Log tạm /tmp/limit-dev2-scoped.log và /tmp/limit-dev2-package.log không là archive lâu dài.

## 5. Blocked và điều kiện đóng

Theo dõi 16/11/2026, không tự đặt hạn hoàn thành cho owner:

| Dependency | Phần chưa nghiệm thu | Điều kiện đóng |
|---|---|---|
| Audit implementation mới | C2/C3 native writer, hash chain, append-only, fallback và rollback audit thật | Owner audit review API, nối implementation mới trên dev2 và chạy lại suite với provider thật |
| Identity/session/step-up/StaffDirectory | Session/OTP/FIDO, challenge consume/expiry/revoke và displayName thật | Owner identity cung cấp bean thật, chạy contract vectors và HTTP integration |
| Customer/account | Reference existence, ownership và data scope thật | Owner cung cấp query implementation và kiểm chứng DB/IDOR |
| GET E3/transfer/account/ledger | Remaining response, debit/credit, idempotency/recovery với limit | Ghép consumer thật, chạy các acceptance tương ứng |
| FK/migration coordination | Provider tables và historical references; version migration sau khi bỏ V2 cũ | Các owner thống nhất migration/version và validate dữ liệu; không tự tạo placeholder hoặc xóa DB cũ |
| Candidate contract owner review | Chưa có biên bản các owner phê duyệt | Provider/consumer review signature/tx/error/test vectors, không tự sign-off thay thành viên |

Code endpoint/limit đã chuyển và test consumer đạt không đồng nghĩa toàn E4 đã Verified. Báo cáo 159/294 và self-converge trước đây vẫn có trong lịch sử phuong/limit; readiness hiện tại phải dùng nền và giới hạn trong báo cáo này.
