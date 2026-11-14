# Theo dõi công việc và vấn đề mở FE

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-TRACK |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Bổ sung 13/11/2026 | FE-DOC-03 và UI/mock FE-FND-02-MOCK được người phụ trách FE duyệt ngày 14/11/2026; không mở rộng nghiệm thu sang API thật |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / FE Lead, BA, Backend, QA |

## 1. Cách cập nhật

Đây là nguồn trạng thái triển khai hiện tại theo [[digitalbanking-docs/docs-frontend/README.md#4. FE-DOC-01 — Quy tắc đồng bộ]]. Các điều chỉnh sau refactor được quản lý như công việc tiếp theo tại mục 2.1. Ngày cập nhật ban đầu 08/11/2026, baseline main@f6ae7ec.

Mỗi dòng ghi owner theo vai trò. Khi nhận việc bổ sung tên người, ngày bắt đầu, ngày dự kiến/kết thúc, PR/commit và kết quả vào cột nhật ký. Mức chứng minh mới được ghi theo từng kết quả. Ngày dự kiến hiện Chờ phân công cho toàn bộ công việc implementation.

## 2. Công việc

| ID | Đầu ra / đặc tả | Owner | Phụ thuộc | Trạng thái hiện tại | Bằng chứng / nhật ký |
|---|---|---|---|---|---|
| FE-DOC-01 | Bộ docs và truy vết theo [[digitalbanking-docs/docs-frontend/README.md]] | FE | Người phụ trách FE chốt tài liệu | Hoàn thành | Bộ docs v1.1 được người phụ trách FE xác nhận ngày 09/11/2026, kiểm tra liên kết/ID đạt |
| FE-DOC-02 | Design chi tiết theo màn hình, shared component/layout và file chờ UI | FE | Người phụ trách FE chốt v1.2 | Hoàn thành | [[digitalbanking-docs/docs-frontend/design/README.md]], mô tả 20 màn có source và 8 file chờ UI |
| FE-DOC-03 | Lộ trình mock trước, phiếu kiểm tra mỗi lần chạy task, gate duyệt UI/tích hợp/merge | FE | Yêu cầu trong chat ngày 13/11/2026 | Hoàn thành | [[digitalbanking-docs/docs-frontend/management/04-mock-first-roadmap.md]], [[digitalbanking-docs/docs-frontend/evidence/FE-DOC-03/2026-11-14-c1760f1/run-02.md]] |
| FE-FND-02-MOCK | Task con FE-FND-02: MSW/fixture bám OpenAPI, bật mock để review UI và hướng dẫn preview | FE | FE-FND-01, GAP-06/ADR-001, Q-01/02/03 trong lộ trình bổ sung | UI/mock được duyệt | Đã chốt Q-01/02/03; mock và fixture adapter chỉ xem cho 20 màn source cũ đã chạy, thao tác thiếu contract bị khóa. 19 tests/typecheck/API check/format/build đạt; browser smoke đã có; người dùng duyệt UI/mock ngày 14/11/2026, task tích hợp còn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-14-c1760f1/run-02.md]] |
| FE-FND-01 | Đưa source, assets, cấu hình và tests vào digitalbanking-frontend, pin toolchain/lockfile | FE | GAP-01, GAP-06 | Đang làm | Lint/CI config và clean install đã có kết quả local đạt, task con được FE duyệt ngày 14/11; xem [[digitalbanking-docs/docs-frontend/evidence/FE-FND-01/2026-11-14-cfde9ad/run-02.md]]. Phạm vi tích hợp legacy và xác nhận kiến trúc còn mở |
| FE-FND-01-TOOLING | Task con FE-FND-01: lint, scripts kiểm tra, CI và cài sạch theo lockfile | FE | Stack/ADR-001 đã chốt phía FE | Đã duyệt — tooling local | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-01/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02 | Shared API/DTO/Money/session/query keys | FE + BE | FE-FND-01, GAP-05, GAP-08 | Đang làm | Money/ID và contract/mock v2 đã được FE duyệt ngày 14/11/2026; session/quyền/step-up/integration còn mở. [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md]] |
| FE-FND-02-DATA | Task con FE-FND-02: Money/ID utilities theo schema hiện hành, unit boundary/precision | FE | API-DATA-01 và schema ID/Money đã rõ; độc lập GAP session/step-up | Đã duyệt — unit/schema | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DATA/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02-AUTH-CONTRACT | Task con FE-FND-02: đồng bộ session contract theo Security trước implementation phụ thuộc | FE + BE + Security | GAP-05, versioning 07 mục 1.4/2.1 | FE/mock được duyệt | Đã chọn v2 riêng; contract/types/mock v2 và 72 tests/full gate đạt. Giữ nguyên v1; client/UI migration và runtime còn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-AUTH-CONTRACT/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02-DEV-REVIEW | Task con FE-FND-02: đọc dev và đối chiếu readiness/common với FE | FE | Snapshot origin/dev, GAP-05/07 | Đã duyệt — đọc source | Đọc source dev@24606b5 ngày 14/11/2026: common đã có, v1 không đổi; chưa có identity/business API/v2. Không đóng GAP hoặc nghiệm thu runtime. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DEV-REVIEW/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-03 | App shell, route policy, UI tokens/form/accessibility và đồng bộ design theo màn | FE | FE-FND-01, ADR-001 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/standards/01-architecture.md]], [[digitalbanking-docs/docs-frontend/standards/02-ui-and-design-system.md]] |
| FE-01-01 | Password login, MFA, session/bootstrap/logout | FE + BE | FE-FND-02, GAP-05, GAP-07 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]] |
| FE-01-02 | Trusted-device passcode/FIDO và fallback | FE + BE | FE-01-01, GAP-04 | Bị chặn | Chờ enrollment/provider và test runtime WebAuthn |
| FE-02-01 | Profile, account list/detail, ownership | FE | FE-01-01, FE-FND-03 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-02-customer-and-accounts.md]] |
| FE-03-01 | Form/review/create transfer và 428 step-up | FE + BE | FE-02-01, FE-FND-02, GAP-02, GAP-08 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-03-transfers.md]] |
| FE-03-02 | Same-key retry, pending/timeout/reload và receipt | FE + QA | FE-03-01, GAP-09 | Chưa bắt đầu | Cần browser + backend invariant evidence |
| FE-04-01 | History filter/page/detail theo reference | FE | FE-02-01, FE-FND-02 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-04-transaction-history.md]] |
| FE-05-01 | Hạn mức customer và form limit maker | FE + BE | FE-FND-02, FE-01-01, GAP-07 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-05-limits-and-approvals.md]] |
| FE-05-02 | Approval list/detail và approve/reject dùng chung | FE + BE | FE-05-01, GAP-08 | Chưa bắt đầu | FE-08 tái dùng cơ chế này |
| FE-06-01 | Staff đọc customer/account và liên kết history | FE + BE | FE-FND-02, GAP-03 | Chưa bắt đầu | Theo ID trước, search cần hợp đồng |
| FE-06-02 | Fraud list, review và trạng thái cuối | FE + BE | FE-06-01, GAP-11 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-06-operations-and-fraud.md]] |
| FE-07-01 | Audit search/detail/export và quyền | FE + QA | FE-FND-02, GAP-12 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-07-audit.md]] |
| FE-08-01 | Role/privilege maker và approval ROLE_CHANGE | FE + BE | FE-05-02, GAP-03, GAP-07 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-08-administration.md]] |
| FE-REL-01 | Test gate, E2E theo AC, defect và UAT | FE + QA | Feature trong scope, backend đích | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md]] |
| FE-REL-02 | Responsive/accessibility, bundle audit, runbook/build contract | FE + QA | FE-REL-01, GAP-06 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/guides/01-local-development.md]] |
| FE-REL-03 | Cloud smoke và demo/handover | FE + Cloud | M4, FE-REL-02 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/guides/02-demo-and-handover.md]] |

Mọi dòng implementation hiện chưa có bằng chứng mới trên backend đích. Khi chia nhỏ task giữ ID cha và gắn đầu ra/AC riêng cho task con. Kết quả thực hiện được cập nhật vào cột Bằng chứng / nhật ký cùng commit và môi trường.

### 2.1. Công việc tiếp theo sau refactor

Thực hiện theo thứ tự ưu tiên dưới đây và phụ thuộc trong bảng công việc. Cột Trạng thái hiện tại ở mục 2 là nguồn tiến độ. Bảng này làm rõ nội dung phải sửa và kết quả cần kiểm chứng của từng task.

| Ưu tiên | Task / quyết định | Công việc phải làm | Đầu ra và tiêu chí hoàn thành |
|---|---|---|---|
| 1 | FE-FND-01, GAP-01, GAP-06 | Đưa source/assets/tests/config vào digitalbanking-frontend, sửa đường dẫn và scripts, pin package/runtime | Cài đặt sạch bằng lockfile, dev/build/typecheck chạy trong thư mục mới, danh sách chức năng giữ/hoãn được chốt |
| 2 | FE-FND-02, GAP-05 | Chuyển Bearer/refresh sang cookie session/getSession, tập trung CSRF và xử lý hết phiên | Login, reload, POST có CSRF, logout/đa tab và lỗi OTP 401 được kiểm chứng theo FET-01/13 |
| 2 | FE-FND-02, FE-08-01, GAP-07 | Chuẩn hóa OPERATOR thành OPERATIONS và CHECKER privilege, thống nhất menu/route/action policy | Một permission matrix, kiểm tra direct URL, action và ownership, FET-12/13 |
| 2 | FE-FND-02 | Sinh lại DTO từ OpenAPI hiện hành, cập nhật field/enum/ID/Money và error catalog | api:check/typecheck đạt, test Money decimal/ID lớn và Problem Details đạt |
| 3 | FE-FND-03, GAP-06 | Chốt UI library/tokens/form primitives và đồng bộ component dùng chung | ADR-001 được quyết định, form/loading/error/keyboard/responsive theo FE-UI, FET-14 |
| 3 | FE-01-01/02, FE-02-01, GAP-04 | Hoàn thiện session/MFA, trusted-device flow và profile/account với API đích | FET-01–03 đạt trên backend đích, FIDO/enrollment có quyết định và mức chứng minh rõ |
| 4 | FE-03-01, GAP-02, GAP-08 | Thay OTP sau create bằng 428 step-up trước create, giữ cùng intent/key/body, chốt xác nhận người nhận | Verify rồi retry cùng key/body, transfer status PENDING/COMPLETED/FAILED, FET-04/05 |
| 4 | FE-03-02, GAP-09 | Theo dõi bằng reference, xử lý pending/timeout/reload/replay, làm rõ unknown outcome | Một ý định gửi tiền có key ổn định, tra soát được kết quả, FET-06/07 và bằng chứng BE |
| 4 | FE-04-01 | Chuyển list sang /transactions, page 0-based, detail/route theo reference và filter UTC | FET-08 đạt cho filter/page/back/reload, ownership và masked data |
| 5 | FE-05-01/02, FE-08-01, GAP-03, GAP-08 | Bổ sung limit, approval và role/privilege maker/checker với shared flow | FET-09/12 đạt, maker khác checker, trạng thái APPLIED theo server |
| 5 | FE-06-01/02, FE-07-01, GAP-03, GAP-11, GAP-12 | Đồng bộ staff lookup, fraud review, audit detail/export với endpoint mới | FET-10/11 đạt, quyền đọc/ghi/export và tác động review được chốt |
| 6 | FE-REL-01/02/03 | Cập nhật test/mock, chạy integration/E2E, đo bundle, kiểm tra accessibility và build/deploy contract | Report FET-01–15 theo phạm vi được duyệt, evidence cùng commit và handover theo M4–M6 |

### 2.2. Tham chiếu khi nhận việc

Yêu cầu ngày 13/11/2026: dùng [[digitalbanking-docs/docs-frontend/management/04-mock-first-roadmap.md]] để chia nhỏ mock/UI và tích hợp từng phần. Mỗi lần chạy dùng [[digitalbanking-docs/docs-frontend/guides/03-task-execution-lifecycle.md]] và tạo phiếu evidence. Gặp điều chưa rõ phải hỏi người dùng, chờ chốt trước phần phụ thuộc. Mốc UI/mock được duyệt không đóng task tích hợp API thật.

FE-FND-01 lấy source từ nhánh frontend tại commit 9e09a71fd2c79c0486c4ee958e02fa2ef884314e để chuyển vào cấu trúc đích. Đường dẫn triển khai là digitalbanking-frontend, contract sinh types là digitalbanking-docs/openapi/digital-banking-v1.yaml.

[Source tham chiếu cho FE-FND-01](https://github.com/TNoLisme/cloude/tree/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend).

Khi bắt đầu task, ghi source/PR đích và phần đã chuyển vào dòng công việc. Kết quả kiểm thử gắn commit FE/BE hiện hành, phiên bản contract và môi trường theo [[digitalbanking-docs/docs-frontend/evidence/README.md]].

## 3. Vấn đề cần quyết định

Tất cả GAP dưới đây đang Mở. Owner ghi quyết định, ngày, link nguồn đã sửa và công việc được mở lại ngay tại dòng tương ứng khi đóng.

| ID | Vấn đề / tác động | Owner quyết định | Điều kiện đóng |
|---|---|---|---|
| GAP-01 | Người phụ trách FE chốt ngày 14/11/2026: giữ register/recovery/PIN/create customer/seed/block/unblock trong đợt tích hợp đầu, chờ BE/BA bổ sung contract trước phần triển khai tương ứng. Phạm vi phía FE đã chốt; API/ngữ nghĩa/AC còn thiếu | BA + BE + FE | Chốt các chức năng trong phạm vi, bổ sung 07/YAML hoặc duyệt phạm vi loại/hoãn, ghi AC ảnh hưởng |
| GAP-02 | API resolve người nhận cũ chưa có trong contract đích | BA + BE | Chốt có endpoint masked lookup hoặc UX nhập destination rồi xác nhận ở server, cập nhật FE-03 |
| GAP-03 | Chưa có search customer/user và user listing cho staff/admin, OI-API-08/18 | BA + BE | Chốt tìm kiếm hoặc nguồn ID được phép từ fixture/approval, cập nhật 07/YAML |
| GAP-04 | YAML đã có begin/finish tại loginFido, còn provider, enrollment và khả năng thực thi WebAuthn cần xác nhận, OI-API-13/17 | BE + Security | Chốt enrollment/provider, ceremony/options runtime và fallback, có test tích hợp begin/finish theo YAML |
| GAP-05 | Ngày 14/11/2026 người dùng chọn baseline FE/mock theo Security: SESSION_TOKEN, Path=/api, LOGIN_MFA. Đã chốt giữ v1 và tạo v2 riêng; contract/types/mock v2 đã được người phụ trách FE duyệt ngày 14/11/2026. BE/Security/DB runtime và migration vẫn chờ xác nhận | BE + Security | Thống nhất 07/YAML/security/BE docs và runtime session/CSRF, kiểm chứng reload/logout |
| GAP-06 | Người phụ trách FE đã chọn Ant Design + Vite 7/pnpm/RHF+Zod ngày 13/11/2026; package/lockfile đã kiểm chứng cho preview. Xác nhận liên vai trò và gate đầy đủ của task cha còn mở | FE + kiến trúc | Duyệt ADR-001, chốt package versions/lockfile/runtime và lệnh CI. Đồng bộ 05 nếu giữ Ant Design |
| GAP-07 | UserSummary chỉ có roles/privileges trong YAML, seed quyền khác nhau ở SRS/API/security, ví dụ quyền export ADMIN | BE + Security + BA | Một permission matrix thống nhất, chốt session capability hoặc mapping FE theo matrix |
| GAP-08 | Step-up transfer gắn request/key và sensitive action gắn approval/decision, create approval chưa có approvalId. 07 còn OI-API-19 về retry trùng | BE + Security | Chốt binding/create approval, challenge header/payload và quy tắc retry, có test contract |
| GAP-09 | Reload mất transfer intent/key memory khi chưa có reference, contract chưa có lookup by idempotency key | BE + FE + BA | Chốt recovery theo history/reference hoặc endpoint lookup được bảo vệ. Chốt retention nếu lưu key cục bộ |
| GAP-10 | Notification inbox và retention/legal hold UI chưa có API, OI-API-07 | BA + BE + Audit | Xác nhận trách nhiệm FE và kênh thông báo, thêm endpoint hoặc quyết định thao tác nội bộ có kiểm soát |
| GAP-11 | Timeline chia P0/P1 Fraud Option B trong khi SRS có Must/Should cho fraud và các module mới | BA + người điều phối | Duyệt scope theo FR/AC và milestone, ghi các mục hoãn có owner và trigger |
| GAP-12 | Audit export format/giới hạn/time range/stream hoặc async và quyền còn cần đối chiếu, OI-API-11 | BE + Audit + FE | Đồng bộ 07/YAML, có sample file và kiểm chứng size/permission |

## 4. Quyết định và giới hạn

ADR đang chờ review tại [[digitalbanking-docs/docs-frontend/decisions/README.md]]. Giá trị đề xuất như lockout/session/OTP/rate limit giữ trạng thái của nguồn, UI lấy metadata server theo standards. Công việc bị chặn có thể tiến hành phần độc lập với mock và ghi đúng mức chứng minh.

## 5. Nhật ký thực hiện

| Ngày | Công việc | Kết quả / mức chứng minh |
|---|---|---|
| 08/11/2026 | FE-DOC-01 | Đồng bộ main từ 8cf9bb5 đến f6ae7ec, viết docs v1.0. Chờ review nội dung |
| 08/11/2026 | FE-DOC-01 | Cập nhật v1.1, tập trung các điều chỉnh sau refactor vào mục 2.1 và đồng bộ hướng dẫn/đặc tả theo yêu cầu FE |
| 09/11/2026 | FE-DOC-01 | Người phụ trách Frontend chốt bộ tài liệu v1.1 trong chat, ghi nhận phê duyệt và chuẩn bị commit lên main |
| 09/11/2026 | FE-DOC-02 | Bổ sung shared design, 20 mô tả màn theo source và 8 file chờ UI. Kiểm tra cấu trúc/liên kết đạt, người phụ trách FE chốt v1.2 ngày 09/11/2026 |
| 13/11/2026 | FE-DOC-03, FE-FND-02-MOCK | Soạn lộ trình mock trước và vòng đời kiểm tra task theo yêu cầu người dùng. Đã hỏi lựa chọn UI/toolchain và phạm vi đợt đầu; chưa có câu trả lời, mock runtime chưa được triển khai. Evidence tại phiếu FE-DOC-03 |

## 6. Kiểm tra bản tài liệu v1.1

Ngày 08/11/2026 đã kiểm tra 24 file FE, 176 wikilink gồm file và heading, 38 tham chiếu operationId, 19 task ID, 12 GAP ID, 15 FET ID và 25 quy tắc dùng chung. Kết quả đạt. Các bảng và code fence hợp lệ, 8 đặc tả có đủ 12 mục. Nội dung đã chuyển sang tracker và mọi tham chiếu tới tài liệu đã bỏ được gỡ. Người phụ trách FE đã chốt baseline v1.1 ngày 09/11/2026 theo FE-DOC-01.

## 7. Kiểm tra phần bổ sung design v1.2

Ngày 09/11/2026, kiểm tra 56 file FE gồm 28 screen design, 2 shared design và template màn hình. 353 wikilink và heading, 40 source permalink/path/range, 38 tham chiếu operationId và 20 task ID đạt kiểm tra. Tám file màn chưa có UI giữ body trống theo khung mục. Các liên kết feature/design hai chiều, bảng và code fence hợp lệ. Mô tả UI dựa trên đọc source/CSS, mức kiểm chứng render/browser được bổ sung khi thực hiện FE-FND-03 và task feature.

## 8. Phê duyệt v1.2

Ngày 09/11/2026, người phụ trách Frontend xác nhận chốt v1.2 và yêu cầu commit/push lên main. FE-DOC-02 hoàn thành phần tài liệu. Các task UI/implementation và file chờ UI giữ trạng thái thực hiện riêng theo nội dung tracker và design registry.

## 9. Quyết định triển khai ngày 13/11/2026

Người phụ trách FE chọn Ant Design + pnpm/Vite 7/RHF/Zod (ADR-001), preview toàn bộ màn có source cũ trước rồi đồng bộ contract mới và bổ sung màn thiếu. GAP-06 có lựa chọn FE; kiểm chứng package/lockfile và xác nhận liên vai trò còn mở. Q-03 được chốt: chỉ xem UI bằng fixture và khóa thao tác chưa có contract rõ ràng. Các GAP-01/02/05/07/08 và task tích hợp không được tự đóng bởi quyết định preview.

## 10. Phê duyệt và yêu cầu push ngày 14/11/2026

Người phụ trách FE chốt FE-DOC-03 và FE-FND-02-MOCK trong chat: tài liệu được duyệt, UI/mock được duyệt trong phạm vi fixture chỉ xem; yêu cầu push lên frontend. Các task cha/integration/GAP chưa đủ điều kiện tiếp tục mở. Chưa có phê duyệt merge main. Chi tiết source/checks/remote ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-14-c1760f1/run-02.md]].

Ngày 14/11/2026 đã commit source/tài liệu được duyệt tại [d8fdb7e07d382288a0e620b73a06f8b4baf771d1](https://github.com/TNoLisme/cloude/commit/d8fdb7e07d382288a0e620b73a06f8b4baf771d1) và push origin/frontend thành công. Checks hiện có đạt: 19 tests, typecheck, API check, format và build; giữ các giới hạn lint/E2E/bundle đã ghi ở evidence. Main chưa merge; bước quyết định merge chờ người phụ trách FE.

## 11. Lần chạy FE-FND-01 ngày 14/11/2026

Theo yêu cầu bắt đầu thực hiện lộ trình, tiếp tục FE-FND-01 với task con FE-FND-01-TOOLING. Phần độc lập gồm lint, CI, scripts và clean install được thực hiện trước. Không tự đóng GAP-01/06, không chuyển sang tích hợp session/BE khi đầu vào còn mâu thuẫn. Evidence và kết quả thực tế tại phiếu run-01 của FE-FND-01.

Quyết định GAP-01 ngày 14/11/2026 trong lần FE-FND-01: giữ các luồng legacy thiếu API trong đợt tích hợp đầu, chờ BE/BA bổ sung contract. Phần tooling độc lập tiếp tục; các task implementation phụ thuộc GAP-01 vẫn chờ nguồn được cập nhật. Không tự coi việc người dùng chốt phạm vi là contract đã có.

Kết quả FE-FND-01-TOOLING: cài sạch frozen lockfile trên bản sao mới đạt; pnpm check gồm lint/typecheck/API/format/19 tests/build đạt. Đã sửa tương thích CRLF/LF cho API check và format khi checkout Windows. Workflow đã tạo và kiểm tra YAML, chưa chạy CI GitHub; task con Chờ review, task cha vẫn Đang làm.

## 12. Phần dữ liệu độc lập FE-FND-02-DATA ngày 14/11/2026

Theo yêu cầu tiếp tục, triển khai phần Money/ID đã rõ theo 07/YAML và API-DATA-01. Không coi lời yêu cầu tiếp tục là phê duyệt FE-FND-01-TOOLING hoặc đóng dependency/task cha. Tiền được parse/serialize bằng BigInt, ID giữ nguyên string; chưa nối vào UI/transport legacy, chưa làm API thật. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DATA/2026-11-14-cfde9ad/run-01.md]].

FE-FND-02-DATA: 47 tests Money/ID mới và full gate 66 tests đạt; task con Chờ review, task cha tiếp tục mở client/session/query keys. Baseline GAP-05 cho FE/mock đã được hỏi, chưa được tự chốt theo tài liệu đang mâu thuẫn.

## 13. Quyết định Security cho FE/mock ngày 14/11/2026

Người phụ trách FE chọn SESSION_TOKEN, Path=/api, LOGIN_MFA và yêu cầu đồng bộ API/YAML trước phần phụ thuộc. Đã lập [[digitalbanking-docs/docs-frontend/decisions/ADR-002-session-security-contract-alignment.md]]. API Contracts yêu cầu breaking sang /v2; vì vậy đang hỏi giữ v1 dạng draft với ngoại lệ rõ ràng hay tạo v2. Chưa tự đổi canonical contract/DB/client, chưa đóng GAP-05 hoặc ghi nhận BE/Security đã phê duyệt.

Người phụ trách FE đã chốt version: giữ nguyên /api/v1, tạo /api/v2 theo Security. Đã thêm ngữ nghĩa 07a và YAML v2 2.0.0 dạng Draft FE/mock; generated types và mock v2 riêng. Không đổi API/YAML v1 hoặc DDL. GAP-05 chưa đóng runtime; các mục V2-01..05 trong 07a chờ BE/Security/DB xác nhận.

FE-FND-02-AUTH-CONTRACT đã hoàn tất bản nháp v2/types/mock theo lựa chọn người dùng; 72 tests và toàn bộ pnpm check đạt. Task con Chờ review. Không có diff v1; API/DB thật và client/UI v2 chưa nghiệm thu, GAP-05 vẫn mở.

## 14. Đối chiếu dev ngày 14/11/2026

Theo yêu cầu đọc nhánh dev, đã đối chiếu origin/dev@24606b529197d6dd3e6908a7cd271b69b20dd4d2 với frontend@cfde9ad và working tree FE hiện tại, không checkout/merge/push. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DEV-REVIEW/2026-11-14-cfde9ad/run-01.md]] ghi nguồn, checks và checklist tích hợp.

Common đã có error/context/security/crypto/observability; OpenAPI v1 giống FE sau chuẩn hóa LF, vẫn 30 operations và 46 error codes. Mã lỗi/HTTP/retryable trong Java khớp 07/YAML. Đây là căn cứ để triển khai shared transport/Problem Details/CSRF/headers; không chứng minh business APIs đã có. Báo cáo common ghi 203 tests đạt trong phạm vi BE đã chạy, chưa sign-off, còn 3 task Blocked và NO-GO release/cloud; lần FE này không chạy lại Gradle hoặc API/browser.

Dev ghi quy tắc ưu tiên docs-common/01–11 trên design và common dùng DB_SESSION/Path=/ theo 07. Runtime filter/matchers/challenge validator vẫn theo v1/LOGIN_NEW_DEVICE; chưa có production SessionAuthenticator, business controller hoặc /api/v2. Quyết định FE đã chốt giữ v1 và tạo v2 SESSION_TOKEN/Path=/api/LOGIN_MFA không bị thay đổi bởi lần đọc này. GAP-05/V2-01..05 vẫn chờ BE/Security/DB; GAP-07 chưa có matrix/runtime thống nhất, các GAP nghiệp vụ khác không tự đóng. FE tiếp tục phần mock/shared độc lập đã rõ; chưa tích hợp session v2 hoặc merge.

## 15. Phê duyệt và commit theo task ngày 14/11/2026

Người phụ trách FE chốt trong chat: “chốt các task đang cần review, push nhánh frontend với mỗi commit là mỗi task riêng chứ không commit riêng”. Ghi nhận duyệt bốn task con đang chờ review; mỗi task có một commit chứa source/tài liệu/evidence của chính task đó, không tạo commit phê duyệt tổng hợp.

| Task | Mốc được người phụ trách FE duyệt | Phiếu mới |
|---|---|---|
| FE-FND-01-TOOLING | Tooling/checks local; CI remote và xác nhận liên vai trò còn mở | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-01/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02-DATA | Money/ID utility, unit/schema; chưa nối UI/transport | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DATA/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02-AUTH-CONTRACT | Ngữ nghĩa/YAML/types/mock v2 phía FE; vẫn Draft chờ runtime BE/Security | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-AUTH-CONTRACT/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02-DEV-REVIEW | Báo cáo đọc source dev@24606b5; không nghiệm thu BE | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DEV-REVIEW/2026-11-14-cfde9ad/run-02.md]] |

Phê duyệt task con không đóng FE-FND-01/02, GAP hoặc integration. Được phép push frontend; main chưa được duyệt merge. SHA thực tế của từng task tra từ commit chứa phiếu run-02; push chỉ được báo thành công sau khi kiểm remote.
