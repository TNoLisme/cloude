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
| FE-FND-01 | Đưa source, assets, cấu hình và tests vào digitalbanking-frontend, pin toolchain/lockfile | FE | GAP-01, GAP-06 | Đang làm | Thực hiện theo mục 2.1 và [[digitalbanking-docs/docs-frontend/guides/01-local-development.md]] |
| FE-FND-02 | Shared API/DTO/Money/session/query keys | FE + BE | FE-FND-01, GAP-05, GAP-08 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md]] |
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
| GAP-01 | Timeline nói register/PIN/operator baseline, API mới thiếu register/recovery/PIN/seed/block/unblock. Cần chốt phạm vi trước khi triển khai các luồng này | BA + BE + FE | Chốt các chức năng trong phạm vi, bổ sung 07/YAML hoặc duyệt phạm vi loại/hoãn, ghi AC ảnh hưởng |
| GAP-02 | API resolve người nhận cũ chưa có trong contract đích | BA + BE | Chốt có endpoint masked lookup hoặc UX nhập destination rồi xác nhận ở server, cập nhật FE-03 |
| GAP-03 | Chưa có search customer/user và user listing cho staff/admin, OI-API-08/18 | BA + BE | Chốt tìm kiếm hoặc nguồn ID được phép từ fixture/approval, cập nhật 07/YAML |
| GAP-04 | YAML đã có begin/finish tại loginFido, còn provider, enrollment và khả năng thực thi WebAuthn cần xác nhận, OI-API-13/17 | BE + Security | Chốt enrollment/provider, ceremony/options runtime và fallback, có test tích hợp begin/finish theo YAML |
| GAP-05 | 07/YAML dùng DB_SESSION, security dùng SESSION_TOKEN và Path khác. FE-FND-02 cần chuyển client sang cookie session/getSession | BE + Security | Thống nhất 07/YAML/security/BE docs và runtime session/CSRF, kiểm chứng reload/logout |
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
