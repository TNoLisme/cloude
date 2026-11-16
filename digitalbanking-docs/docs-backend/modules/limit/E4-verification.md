# E4 — Báo cáo kiểm chứng LIM-07/LIM-08 và bàn giao LIM-09/LIM-10

> **Ngày kiểm chứng:** 15/11/2026
> **Cập nhật báo cáo:** 16/11/2026
> **Phạm vi:** Giao diện nội bộ quản lý vòng đời cấu hình hạn mức — LIM-07/08
> **Trạng thái:** LIM-07/08 hoàn tất; 159 kiểm thử liên quan đạt; build đóng gói thành công; converge độc lập PASS, 0 phát hiện; LIM-09/10 hoãn, chưa nghiệm thu

## 1. Nguồn và quyết định đã được người dùng chốt

Thứ tự ưu tiên: `docs-common/01–11` → `docs-common/design/` → tài liệu backend. Căn cứ: SRS FR-032/033/035/051, UC-011, AC-017/019/028; Database §4.5.1–3; Architecture §6–8; API §8.5; Consistency §9.2 và DELIVERY-PLAN §7/9.

Bốn quyết định tại bước clarify:

1. Hoàn tất giao diện nội bộ LIM-07/08; hoãn tích hợp LIM-09/10 vì checkout chưa có các thành phần production `admin`, `identity`, `account`, `transfer` và API E3. Không dựng thành phần production giả để kết luận tích hợp đã hoàn tất.
2. Max từng lần/ngày phải được cấu hình rõ ràng, không tự đặt mặc định nghiệp vụ. Thiếu hoặc sai policy thì từ chối tạo/duyệt đề xuất.
3. Từ chối `effectiveFrom` trong tương lai; cấu hình có hiệu lực ngay khi duyệt. Không thêm scheduler hoặc áp dụng hồi tố.
4. Chặn kích hoạt scope account chưa được theo dõi nếu customer đã dùng hạn mức trong ngày quyết định; giữ đề xuất chờ duyệt để có thể duyệt vào ngày mới chưa có giao dịch. Không reset hoặc bù lại usage từ lịch sử.

Hồ sơ SDD local, được ignore theo cấu hình repo hiện có: [spec](specs/003-controlled-limit-changes/spec.md), [plan](specs/003-controlled-limit-changes/plan.md), [research](specs/003-controlled-limit-changes/research.md), [hợp đồng nội bộ](specs/003-controlled-limit-changes/contracts/limit-changes.md), [tasks](specs/003-controlled-limit-changes/tasks.md). Checklist chất lượng đặc tả đạt **16/16**. Constitution còn là template chưa điền nên bỏ qua kiểm tra nguyên tắc của template; không có extension hook.

## 2. Phần đã triển khai

- `LimitChanges.prepare` kiểm tra ID scope/maker/approval dương, VND, tiền biểu diễn được bằng `NUMERIC(19,2)`, hạn mức từng lần ≤ hạn mức ngày và policy cấu hình. Tạo version tiếp theo với trạng thái `PENDING_APPROVAL`, giữ nguyên cấu hình đang áp dụng và toàn bộ usage. Liên kết `approval_id` khác null là duy nhất; đề xuất trùng bị từ chối mà không làm hỏng transaction của caller.
- `approve` kiểm tra lại policy/thời gian, yêu cầu checker khác maker và chỉ xử lý đề xuất pending. Trong cùng transaction, bản active cũ chuyển `SUPERSEDED`, bản pending chuyển `ACTIVE` với thời điểm hiệu lực là thời điểm quyết định. `reject` giữ nguyên active; các trường checker/thời điểm duyệt trên config bị từ chối giữ null theo tài liệu 04. Thông tin người từ chối thuộc approval/audit do admin quản lý.
- Mọi mutation yêu cầu transaction vật lý đang hoạt động, có quyền ghi của caller, propagation `MANDATORY` và isolation `READ COMMITTED`. Chặn gọi ngoài transaction, trong transaction chỉ đọc hoặc từ `afterCommit`. Lỗi từ chối nghiệp vụ cho phép caller ghi nhận việc từ chối; lỗi kỹ thuật/audit rollback toàn bộ thay đổi.
- Khóa advisory theo customer được dùng chung với E2, lấy trước các khóa usage của E2; thao tác cấu hình còn khóa scope account tương ứng. Thay đổi cấu hình không tạo, reset hoặc tăng usage. D4 kiểm tra cả số tiền và số giao dịch của customer theo `app.timezone`; lịch sử account đã hết hiệu lực không giúp vượt qua kiểm tra này.
- Migration V5 giữ nguyên checksum V3/V4, thêm unique index cho `approval_id` khác null và chỉ cấp `UPDATE` các cột phục vụ vòng đời cấu hình. Không cấp sửa scope, số tiền, version, maker, liên kết approval hoặc quyền xóa lịch sử. Fixture E1 được sửa để mỗi version dùng approval ID riêng; kiểm thử nâng cấp migration tính thêm V5.
- Policy dùng `app.limit.policy.max-per-transaction-vnd` và `app.limit.policy.max-daily-vnd`, tương ứng biến môi trường `APP_LIMIT_POLICY_MAX_PER_TRANSACTION_VND` và `APP_LIMIT_POLICY_MAX_DAILY_VND`. Mặc định để trống: prepare/approve trả `SERVICE_UNAVAILABLE`. Vượt max trả `LIMIT_OUT_OF_POLICY`; đề xuất sai dữ liệu trả `VALIDATION_ERROR`. Reject vẫn hoạt động khi thiếu policy.

## 3. Phạm vi kiểm thử đã thực hiện

| Nội dung kiểm chứng | Bằng chứng thực thi |
|---|---|
| Pending không đổi active/usage; cấp version, giữ lịch sử và chặn liên kết approval trùng | `LimitChangesIT` |
| Validation, policy thiếu/sai, đúng/vượt ngưỡng; approve kiểm tra lại policy | `LimitChangePolicyTest`, `LimitChangesIT` |
| Approve/reject; tự duyệt, approval không tìm thấy, quyết định lặp; đề xuất/quyết định đầu tiên chạy đồng thời | `LimitChangesIT` |
| Hạ hạn mức không reset usage; enforcement E2 từ chối chi thêm khi đã vượt hạn mức mới | `LimitChangesIT` |
| Scope account mới sau khi customer đã dùng amount/count; lịch sử account hết hiệu lực; duyệt vào ngày mới chưa dùng | `LimitChangesIT` |
| Activation chờ E2 hoàn tất dưới khóa PostgreSQL; T2 bắt đầu trước activation không được bỏ qua account scope mới | `LimitChangesIT` |
| Rollback do lỗi caller; commit cùng AuditRecorder thật; lỗi validation audit và lỗi quyền ghi SQL thật | `LimitChangesIT` |
| Transaction vật lý, MANDATORY, chỉ đọc, isolation, afterCommit và quyền cột của DB app role | `LimitChangesIT` |
| Hồi quy liên quan đến dữ liệu, chọn cấu hình, enforcement, ngày nghiệp vụ và ranh giới module | `LimitPersistenceIT`, `LimitRulesIT`, `LimitEnforcementIT`, `LimitBusinessDateIT`, `LimitRuleServiceTest`, `ModuleBoundaryArchTest` |

Kiểm thử đồng thời dùng tối đa hai worker. Ca lỗi SQL của audit kiểm tra cụ thể `SQLState 42501`. Không chạy full regression của các module không liên quan.

## 4. Thành phần thật, mock và dữ liệu giả lập trong kiểm thử

| Thành phần | Cách kiểm chứng | Giới hạn của bằng chứng |
|---|---|---|
| PostgreSQL 16, Flyway, constraint, quyền DB và khóa | Chạy thật qua Testcontainers, dùng app role và các transaction độc lập | Không dùng DB mock để chứng minh constraint, locking hoặc rollback |
| `LimitChanges`, `LimitEnforcement`, repository và `AuditRecorder` | Gọi implementation thật trong Spring context; các câu SQL chạy trên PostgreSQL thật | Đây là tích hợp giữa các thành phần đã có, chưa phải toàn luồng REST/admin/transfer |
| Caller admin, maker/checker và approval | Test harness dùng `TransactionTemplate` để gọi provider; truyền ID test cố định | Không có admin/identity/approval workflow thật; ID test không chứng minh đối tượng tồn tại, ownership hoặc quyền truy cập |
| Dữ liệu usage trước khi đổi cấu hình | Một số ca tạo usage bằng fixture trực tiếp; các ca phối hợp E2 gọi enforcement thật | Fixture chỉ dựng trạng thái đầu vào, không chứng minh giao dịch tiền/ledger đã chạy |
| Chuyển ngày trong ca D4 | `@MockitoSpyBean` trên repository; chỉ stub `currentTime()` sang ngày tiếp theo | Thao tác cấu hình/usage/transaction vẫn chạy thật; thời gian trong ca này được điều khiển để kiểm tra biên ngày |
| Ngày nghiệp vụ E2 trong `LimitBusinessDateIT` | Một số ca stub nguồn `transactionStart()`; có ca riêng kiểm tra SQL lấy thời điểm transaction thật | Không coi các ca thời gian giả lập là bằng chứng cho đồng hồ vận hành thực tế |
| Policy | `MockEnvironment` trong unit test; integration test cung cấp property test và thay property source để kiểm tra tái validation | Các giá trị max chỉ là dữ liệu test, không phải default nghiệp vụ hoặc bằng chứng hệ thống có hot reload policy |
| `LimitRuleServiceTest` của E1 | Unit test dùng repository mock | Chỉ chứng minh logic chọn/kiểm tra rule; bằng chứng persistence nằm ở các integration test PostgreSQL |

Không thêm mock/stub thay thế các dependency còn thiếu vào production. Build và test pass không đồng nghĩa những dependency đó đã được tích hợp.

## 5. Lệnh và kết quả

Chạy từ `digitalbanking-backend/`, Java 21, Docker/Testcontainers PostgreSQL 16:

- Baseline chỉ `limit.*`: **BUILD SUCCESSFUL**, 108 kiểm thử đạt, không failure/error/skip.
- Test đỏ ban đầu `LimitChangesIT.validProposalIsPending`: fail đúng vì chưa có bean `LimitChanges`.
- Regression `transactionStartedBeforeActivationCannotSkipNewAccountScope`: trước sửa kỳ vọng `NOT_CONFIGURED` nhưng nhận `PASS`. Sau sửa, kiểm tra snapshot dưới khóa dùng chung từ chối toàn bộ check nếu có scope mới mà T2 cũ không được phép bỏ qua.
- Lượt cuối: `./gradlew test --tests 'com.simulatedbank.banking.limit.*' --tests 'com.simulatedbank.banking.architecture.ModuleBoundaryArchTest' --offline --console=plain --max-workers=2` → **BUILD SUCCESSFUL (46 giây), 159 kiểm thử / 0 failure / 0 error / 0 skip**.
- Đóng gói: `./gradlew build -x test --offline --console=plain --max-workers=2` → **BUILD SUCCESSFUL (1 giây)**. Không chạy lại full regression không liên quan.
- `git diff --check` và `git diff --cached --check`: đạt trước review cuối.

XML kết quả: `digitalbanking-backend/build/test-results/test/TEST-*.xml`; báo cáo HTML: `digitalbanking-backend/build/reports/tests/test/index.html`. Các lần chạy sau có thể ghi đè kết quả này. Ngày 16/11/2026 đã đọc lại XML và xác nhận tổng 159 kiểm thử, không failure/error/skip.

Các đường dẫn `/tmp/limit-e4-baseline.log`, `/tmp/limit-e4-red.log`, `/tmp/limit-e4-stale-red.log`, `/tmp/limit-e4-scoped.log`, `/tmp/limit-e4-package.log` là log tạm của phiên triển khai, không nằm trong commit; tại thời điểm cập nhật báo cáo, log scoped/package không còn trên filesystem. Không dùng các đường dẫn tạm làm bằng chứng lưu trữ lâu dài.

## 6. Dependency còn thiếu và phần chưa triển khai/nghiệm thu

| Dependency hoặc phạm vi còn thiếu | Phần chưa làm/chưa kiểm chứng | Cách xử lý hiện tại và điều kiện hoàn tất |
|---|---|---|
| Production `admin` và workflow `approval_request` | LIM-09/10: endpoint maker/checker, lưu/quyết định approval thật và tính nguyên tử approval + config + audit qua endpoint | Hoãn theo clarify. Test harness chỉ gọi provider; owner admin cần cung cấp workflow và tích hợp thật |
| Production `identity` | Session, quyền maker/checker, CSRF, verified step-up và mapping HTTP tự duyệt D5 | Provider chỉ chặn checker trùng maker từ ID được truyền; chưa chứng minh xác thực/phân quyền. Tích hợp identity thật trước nghiệm thu endpoint |
| Dữ liệu/module customer, account, app_user và approval | Kiểm tra đối tượng tồn tại, ownership và FK liên module | Caller tương lai phải xác thực reference/ownership; thêm và validate FK khi migration của owner đã sẵn sàng. Không tạo bảng placeholder |
| Production `transfer` và các thành phần tiền/ledger liên quan | Debit/credit/ledger thật, trạng thái terminal, replay/recovery; tác động thay đổi hạn mức qua giao dịch tiền thật | Chỉ kiểm chứng provider E2 đang có; chưa nghiệm thu LIM-05 hoặc acceptance tích hợp của LIM-10 |
| API tra cứu E3 | `GET /limits`, các field remaining và response sau thay đổi cấu hình | Chỉ kiểm tra query/rule nội bộ; chưa kiểm chứng response/remaining của API thật |
| Policy max nghiệp vụ của môi trường triển khai | Giá trị max cụ thể và seed/default nghiệp vụ | Không tự chốt; phải cấp hai cấu hình policy trước prepare/approve. Giá trị trong test không dùng làm baseline triển khai |

Khi tích hợp, admin phải kiểm tra customer/account/ownership, quyền, CSRF và step-up đã verify; sở hữu/khóa approval thật; gọi provider; cập nhật approval và ghi audit trong cùng transaction. Provider không sở hữu `approval_request`, không tự xác thực quyền và không tự ghi audit cho caller.

LIM-09/10 **chưa triển khai và chưa nghiệm thu**. Mã `ROLE_CONFLICT` nội bộ không phải bằng chứng đã chốt mapping HTTP D5. Các phần hoãn không được đóng bằng kết quả build, mock hoặc test harness.

## 7. Giới hạn vận hành cần giữ khi tích hợp

E2 giữ thời điểm đánh giá cố định từ lúc bắt đầu transaction. Nếu T2 bắt đầu trước activation rồi chờ khóa, sau đó phát hiện bất kỳ scope `ACTIVE` đang hiệu lực nào bắt đầu sau thời điểm cố định đó, toàn bộ check phải trả `NOT_CONFIGURED`, kể cả customer scope cũ vẫn đang áp dụng. Cơ chế này ngăn bỏ qua account scope mới và không cấp phép xử lý tiền. Transaction mới sau khi approval commit sẽ dùng cấu hình mới.

Không tuyên bố các request đang chờ luôn được phục vụ liên tục; không có cơ chế activation theo lịch. Caller tích hợp phải xử lý `NOT_CONFIGURED` theo policy đã chốt, không tự hiểu thành không giới hạn.

## 8. Kết quả converge độc lập

Subagent `limit_e4_reviewer` chạy `speckit-converge` sau implementation. Vòng 1: **CONVERGED — 0 phát hiện** thuộc các nhóm thiếu, một phần, mâu thuẫn hoặc ngoài yêu cầu.

Reviewer đối chiếu **7 FR, 4 SC, 7 acceptance scenario trong phạm vi, 6 edge case, 12 quyết định thiết kế và 15 task** với source/test/bằng chứng. Các yêu cầu transaction, quyết định cạnh tranh, tái kiểm tra policy, giữ nguyên usage, D4 và bảo vệ T2 bắt đầu trước activation đạt phạm vi đã clarify. Constitution được bỏ qua vì còn là template; không có extension hook.

Reviewer không chạy lại test rộng, không sửa file; `tasks.md` giữ nguyên từng byte trong lượt converge. Sau báo cáo PASS, agent chính đánh dấu T015 hoàn tất: **15/15 task đã xong**. Không cần vòng sửa tiếp vì converge độc lập không còn phát hiện.

Kết luận nghiệm thu chỉ áp dụng cho **provider LIM-07/08** với 159 kiểm thử đạt và build thành công. LIM-09/10, các tích hợp còn thiếu và review/chấp thuận của con người vẫn được theo dõi riêng.
