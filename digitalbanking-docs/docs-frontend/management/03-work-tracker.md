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
| FE-DOC-GAP-REVIEW | Đối chiếu GAP-01..12 theo common/backend `dev2`, lập bản trình chốt cuối | FE | Yêu cầu người phụ trách FE ngày 22/11/2026; owner nguồn vẫn cần trả lời | Chờ review — tài liệu đọc source | [[digitalbanking-docs/docs-frontend/management/08-common-dev2-gap-final-review.md]], [[digitalbanking-docs/docs-frontend/evidence/FE-DOC-GAP-REVIEW/2026-11-22-835cf01/run-01.md]]; chưa có phê duyệt GAP hoặc API/browser mới |
| FE-REL-V1-PLAN | Kế hoạch ghép source và tích hợp FE với backend v1 theo từng lát | FE | Người phụ trách FE chốt v1 ngày 22/11/2026; BE dev2@740ab5f | Chờ review — kế hoạch, chưa merge | [[digitalbanking-docs/docs-frontend/management/09-v1-fe-be-merge-plan.md]], [[digitalbanking-docs/docs-frontend/evidence/FE-REL-V1-PLAN/2026-11-22-41209c4/run-01.md]] |
| FE-REL-V1-SOURCE | Ghép source frontend/dev2 trên nhánh tích hợp cách ly | FE | User chốt bốn conflict, v1 FE–BE | Đang kiểm chứng — API types v1 cần đồng bộ | [[digitalbanking-docs/docs-frontend/evidence/FE-REL-V1-SOURCE/2026-11-22-5a1f310/run-01.md]]; merge source không nghiệm thu API thật |
| FE-FND-02-V1 | Đồng bộ generated types và fixture v1.1.0 theo dev2 | FE | User chọn v1; GAP-05/07/08 cho session/quyền/mutation | Đang kiểm chứng — schema/mock độc lập; API thật chờ GAP-05 | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-V1/2026-11-23-282de76/run-01.md]] |
| FE-FND-02-MOCK | Task con FE-FND-02: MSW/fixture bám OpenAPI, bật mock để review UI và hướng dẫn preview | FE | FE-FND-01, GAP-06/ADR-001, Q-01/02/03 trong lộ trình bổ sung | UI/mock được duyệt | Đã chốt Q-01/02/03; mock và fixture adapter chỉ xem cho 20 màn source cũ đã chạy, thao tác thiếu contract bị khóa. 19 tests/typecheck/API check/format/build đạt; browser smoke đã có; người dùng duyệt UI/mock ngày 14/11/2026, task tích hợp còn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-14-c1760f1/run-02.md]] |
| FE-FND-01 | Đưa source, assets, cấu hình và tests vào digitalbanking-frontend, pin toolchain/lockfile | FE | GAP-01, GAP-06 | Đang làm | Lint/CI config và clean install đã có kết quả local đạt, task con được FE duyệt ngày 14/11; xem [[digitalbanking-docs/docs-frontend/evidence/FE-FND-01/2026-11-14-cfde9ad/run-02.md]]. Phạm vi tích hợp legacy và xác nhận kiến trúc còn mở |
| FE-FND-01-TOOLING | Task con FE-FND-01: lint, scripts kiểm tra, CI và cài sạch theo lockfile | FE | Stack/ADR-001 đã chốt phía FE | Đã duyệt — tooling local | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-01/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02 | Shared API/DTO/Money/session/query keys | FE + BE | FE-FND-01, GAP-05, GAP-08 | Đang làm | Money/ID và contract/mock v2 đã được FE duyệt ngày 14/11/2026; session/quyền/step-up/integration còn mở. [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md]] |
| FE-FND-02-DATA | Task con FE-FND-02: Money/ID utilities theo schema hiện hành, unit boundary/precision | FE | API-DATA-01 và schema ID/Money đã rõ; độc lập GAP session/step-up | Đã duyệt — unit/schema | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DATA/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02-AUTH-CONTRACT | Task con FE-FND-02: đồng bộ session contract theo Security trước implementation phụ thuộc | FE + BE + Security | GAP-05, versioning 07 mục 1.4/2.1 | FE/mock được duyệt | Đã chọn v2 riêng; contract/types/mock v2 và 72 tests/full gate đạt. Giữ nguyên v1; client/UI migration và runtime còn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-AUTH-CONTRACT/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02-DEV-REVIEW | Task con FE-FND-02: đọc dev và đối chiếu readiness/common với FE | FE | Snapshot origin/dev, GAP-05/07 | Đã duyệt — đọc source | Đọc source dev@24606b5 ngày 14/11/2026: common đã có, v1 không đổi; chưa có identity/business API/v2. Không đóng GAP hoặc nghiệm thu runtime. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DEV-REVIEW/2026-11-14-cfde9ad/run-02.md]] |
| FE-FND-02-CONTRACT-AUDIT | Rà quyết định replay/TTL/ownership, chuẩn bị phiếu FE–BE và lộ trình có gate | FE | Nguồn main/dev/dev2/rework; GAP-05/07/08/09 | Chờ review — tài liệu | [[digitalbanking-docs/docs-frontend/management/05-fe-be-contract-handoff.md]], [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CONTRACT-AUDIT/2026-11-17-6bceb5d/run-01.md]]. Đã có DEC-TRF-004/005/012; bản giao FE, staff scope và readiness còn mở |
| FE-FND-02-CLIENT-SESSION | Shared JSON client và memory session với mock v2; phần nền tảng của FE-FND-02 | FE | ADR-002/07a/v2 FE/mock đã duyệt; API-CLIENT-01/FE-AUTH-01 | Chờ review — unit/mock | [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CLIENT-SESSION/2026-11-17-6bceb5d/run-01.md]], [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CLIENT-SESSION/2026-11-17-6bceb5d/result.md]]. 119 tests/6 gate đạt; 47 tests mới. Chưa wiring UI, verify challenge hoặc API/browser; GAP/integration mở |
| FE-FND-03 | App shell, route policy, UI tokens/form/accessibility và đồng bộ design theo màn | FE | FE-FND-01, ADR-001 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/standards/01-architecture.md]], [[digitalbanking-docs/docs-frontend/standards/02-ui-and-design-system.md]] |
| FE-01-01 | Password login, MFA, session/bootstrap/logout | FE + BE | FE-FND-02, GAP-05, GAP-07 | Đang làm | UI/mock v2 đã nối; run03 có browser login/reload/logout sau thay layout; full OTP/đa tab/API thật còn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/layout-result.md]] |
| FE-01-01-MOCK | Login/OTP/bootstrap/logout và màn phiên chung | FE | Quyết định FE 17/11, v2 FE/mock | Đang làm — còn kiểm chứng luồng/review UI | Run03:6 gates/151 tests đạt, layout và browser subset mới đã có. Fresh worker install, full OTP/đa tab và acceptance còn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/run-03.md]] |
| FE-01-01-LOGIN-UI | Bố cục login theo ảnh người dùng, BrandDB/Digital Banking, không CAPTCHA/hotline | FE | Yêu cầu sửa UI trong chat18/11; không đổi contract | Chờ review bố cục UI | 6 gates/151 tests đạt; browser1440/768/360 và login/reload/logout mock có kết quả. Không thay nghiệm thu parent. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/layout-result.md]] |
| FE-01-01-SCENARIOS | Kịch bản auth v2 FE/mock đã trình bày | FE | Contract v2 FE/mock và chốt người phụ trách FE | Đã chốt kịch bản | User chốt các kịch bản hiện tại ngày18/11; không suy ra duyệt bố cục/toàn bộ mock hay tích hợp. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/run-04.md]] |
| FE-01-02 | Trusted-device passcode/FIDO và fallback | FE + BE | FE-01-01, GAP-04 | Bị chặn | Chờ enrollment/provider và test runtime WebAuthn |
| FE-02-01 | Profile, account list/detail, ownership | FE | FE-01-01, FE-FND-03 | Đang làm | Preview customer riêng đã nối, API thật/FE-FND-03 và FET đầy đủ còn mở. [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/result.md]] |
| FE-02-01-MOCK | Preview riêng customer self: hồ sơ/list/detail theo v2 FE/mock | FE | Chốt FE18/11; mock v2 đã duyệt | Chờ review UI/mock | Browser subset, tests và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-02.md]]; không coi là duyệt task cha/API thật. |
| FE-03-01 | Form/review/create transfer và 428 step-up | FE + BE | FE-02-01, FE-FND-02, GAP-02, GAP-08 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-03-transfers.md]] |
| FE-03-01-MOCK | Preview customer form/review theo TransferCreateRequest, không gửi lệnh | FE | YAML v2 FE/mock và fixture account; GAP-02/08/09 khóa create/step-up | Chờ review UI/mock — browser default/review/empty đã xem | [[digitalbanking-docs/docs-frontend/evidence/FE-03-01-MOCK/2026-11-22-07ccdaf/result.md]]; không có handler POST /transfers |
| FE-03-02 | Same-key retry, pending/timeout/reload và receipt | FE + QA | FE-03-01, GAP-09 | Chưa bắt đầu | Cần browser + backend invariant evidence |
| FE-04-01 | History filter/page/detail theo reference | FE | FE-02-01, FE-FND-02 | Chưa bắt đầu | [[digitalbanking-docs/docs-frontend/features/FE-04-transaction-history.md]] |
| FE-04-01-MOCK | CUSTOMER history list/filter/page/detail v2 mock | FE | Contract v2 FE/mock, customer session preview; sort whitelist BE mở | Demo mock sẵn xem — UI chưa chốt | [[digitalbanking-docs/docs-frontend/evidence/FE-04-01-MOCK/2026-11-18-b9ba604/run-01.md]] |
| FE-05-01 | Hạn mức customer và form limit maker | FE + BE | FE-FND-02, FE-01-01, GAP-07 | Đang làm — mock xem hạn mức chờ review, maker/integration còn mở | [[digitalbanking-docs/docs-frontend/features/FE-05-limits-and-approvals.md]] |
| FE-05-01-MOCK | CUSTOMER xem hạn mức qua getLimits v2 mock | FE | Contract v2 FE/mock; maker/checker GAP-07/08 ngoài phạm vi | Chờ review UI/mock — browser default đã xem | [[digitalbanking-docs/docs-frontend/evidence/FE-05-01-MOCK/2026-11-19-65bd72f/run-02.md]]; API thật chưa nghiệm thu |
| FE-05-02 | Approval list/detail và approve/reject dùng chung | FE + BE | FE-05-01, GAP-08 | Đang làm — mock read-only chờ review, mutation/integration còn mở | FE-08 tái dùng cơ chế này |
| FE-05-02-MOCK | Approval list/detail read-only v2 mock | FE | User chốt preview staff độc lập/metadata-only 19/11; GAP-07 staff scope và GAP-08 mutation vẫn mở | Chờ review UI/mock — browser list/detail đã xem | [[digitalbanking-docs/docs-frontend/evidence/FE-05-02-MOCK/2026-11-19-65bd72f/run-01.md]], [[digitalbanking-docs/docs-frontend/evidence/FE-05-02-MOCK/2026-11-19-65bd72f/result.md]]; API thật chưa nghiệm thu |
| FE-06-01 | Staff đọc customer/account và liên kết history | FE + BE | FE-FND-02, GAP-03 | Đang làm — preview đọc ID; tìm số điện thoại/integration còn mở | Người phụ trách FE chốt tìm bằng số điện thoại, mock đọc ID trước ngày 20/11/2026. [[digitalbanking-docs/docs-frontend/evidence/FE-06-01-MOCK/2026-11-20-797d9bc/run-01.md]] |
| FE-06-01-MOCK | Preview staff đọc hồ sơ/tài khoản theo customerId fixture; ô tìm số điện thoại chờ contract | FE | GET getAdminCustomer/getAdminCustomerAccounts v2 Draft FE/mock; GAP-03/07 | Chờ review UI/mock — browser default/404 đã xem | [[digitalbanking-docs/docs-frontend/evidence/FE-06-01-MOCK/2026-11-20-797d9bc/run-01.md]], [[digitalbanking-docs/docs-frontend/evidence/FE-06-01-MOCK/2026-11-20-797d9bc/result.md]], [[digitalbanking-docs/docs-frontend/management/06-gap03-phone-search-handoff.md]] |
| FE-06-02 | Fraud list, review và trạng thái cuối | FE + BE | FE-06-01, GAP-11 | Đang làm — mock read-only chờ review, mutation/integration còn mở | [[digitalbanking-docs/docs-frontend/features/FE-06-operations-and-fraud.md]] |
| FE-06-02-MOCK | Preview staff fraud flag list/detail từ GET; quyết định review khóa | FE | User chốt demo chỉ đọc ngày 20/11; GAP-07/11 vẫn mở | Chờ review UI/mock — browser list/detail đã xem | [[digitalbanking-docs/docs-frontend/evidence/FE-06-02-MOCK/2026-11-20-ec8d548/run-01.md]], [[digitalbanking-docs/docs-frontend/evidence/FE-06-02-MOCK/2026-11-20-ec8d548/result.md]] |
| FE-07-01 | Audit search/detail/export và quyền | FE + QA | FE-FND-02, GAP-12 | Đang làm — preview chỉ đọc; export, quyền và integration còn mở | [[digitalbanking-docs/docs-frontend/features/FE-07-audit.md]] |
| FE-07-01-MOCK | Preview staff audit list/filter/page/detail v2 chỉ đọc | FE | Contract v2 Draft FE/mock; GAP-07/12 cho quyền và export | Chờ review UI/mock — browser list/detail và regression đạt; chưa duyệt UI | [[digitalbanking-docs/docs-frontend/evidence/FE-07-01-MOCK/2026-11-22-07ccdaf/result.md]]; export bị khóa, API thật chưa nghiệm thu |
| FE-08-01 | Role/privilege maker và approval ROLE_CHANGE | FE + BE | FE-05-02, GAP-03, GAP-07/08 | Đang làm — preview form/GET chờ review, mutation/integration còn mở | [[digitalbanking-docs/docs-frontend/features/FE-08-administration.md]] |
| FE-08-01-MOCK | Preview độc lập form/review 4 operation và danh sách ROLE_CHANGE chỉ đọc | FE | YAML v2 FE/mock; GAP-03/07/08 khóa chọn target thật và POST | Chờ review UI/mock — browser form/review/link đã xem; selector kịch bản đạt UI/MSW test, chưa xem lại browser | [[digitalbanking-docs/docs-frontend/evidence/FE-08-01-MOCK/2026-11-21-cd07f27/result.md]], [[digitalbanking-docs/docs-frontend/evidence/FE-08-01-MOCK/2026-11-21-cd07f27/run-02.md]]; chỉ GET approval từ fixture, không có POST handler |
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
| GAP-01 | FE yêu cầu giữ các luồng legacy ngày 14/11; common `dev2` có passcode/lockout nhưng không có register/password recovery/staff create customer/manual lock-unlock API; **top-up ngoài phạm vi MVP**, seed chỉ là dữ liệu demo. Ngày 22/11 FE yêu cầu ưu tiên common và `dev2` trước khi chốt cuối. | Người phụ trách FE + BA; BE/Security cho contract của mục được duyệt | Chốt **từng luồng** là bổ sung MVP, seed/demo hay ngoài phạm vi; phân biệt PIN với passcode, auth lockout với đổi account status. Nếu bổ sung, sửa BRD/SRS trước 07/YAML và BE/FE. Giữ thao tác thiếu contract khóa. [[digitalbanking-docs/docs-frontend/management/08-common-dev2-gap-final-review.md]] |
| GAP-02 | Common có tạo transfer với destination account number, BE resolve khi tạo; `OI-API-02` chưa chốt format/lookup trước submit. | BA + BE + Security | Xác nhận có masked lookup trước gửi hay xác nhận tại `POST /transfers`; nếu thêm API, đồng bộ 07/YAML và quyền/masking. |
| GAP-03 | FE chọn phone search; common `OI-API-08/18` và YAML chưa có search customer/user. BE-INT-03 đã xác minh exact-ID read single-bank. | BA + BE + Security | Chốt phone search và nguồn targetUserId theo quyền/scope/PII, method/version/schema/lỗi; không chặn exact-ID read. [[digitalbanking-docs/docs-frontend/management/06-gap03-phone-search-handoff.md]] |
| GAP-04 | Common có passcode/FIDO; `dev2` DEC-ID-010/BE-ID-IMP-10 xác minh FIDO verification nhưng enrollment/provider và passcode positive chưa đủ. | BE Identity + Security | Bàn giao trusted credential/enrollment, RP/origin, fallback/method OI-API-13/17 và browser proof; không suy từ verify thành full flow. |
| GAP-05 | Ngày 22/11/2026 người phụ trách FE chốt **v1 backend dev2** làm đích tích hợp/merge. BE bàn giao runtime/OpenAPI v1.1.0 `DB_SESSION`/`LOGIN_NEW_DEVICE`; v2 `SESSION_TOKEN`/`LOGIN_MFA` là lịch sử FE/mock Draft. Security common còn khác v1 ở cookie/challenge. | BE merge owner + Security + DB; FE kiểm browser | Xác nhận cookie name/path/cờ, challenge, CSRF và cách xử lý mâu thuẫn Security common cho v1; kiểm login/reload/logout bằng runtime v1, cập nhật nguồn liên quan. Không coi chọn version là đóng GAP. [[digitalbanking-docs/docs-frontend/management/09-v1-fe-be-merge-plan.md]] |
| GAP-06 | Common `dev2` bắt buộc Vite 7/pnpm 10/RHF+Zod; Tailwind/shadcn là Should. FE đã chọn Ant Design. | FE + kiến trúc | Duyệt ADR-001/UI kit, Node/lockfile/CI; đồng bộ common nếu cần, không đổi stack ngầm. |
| GAP-07 | `07`/DEC-INT-008/BE-INT-03 đã xác định exact-ID staff single-bank; Auditor có ACCOUNT_READ nhưng thiếu CUSTOMER_READ. SRS/Security còn mâu thuẫn; UserSummary không trả capability hiện hành. | BE + Security + BA, rồi FE | Đồng bộ matrix common và capability/version refresh cho menu/direct URL/action; API/browser test. Giữ GAP-07-A..D dưới đây. |
| GAP-08 | Common/dev2 đã quyết định replay/TTL và Admin key/binding; OI-API-19 ghi đã xử lý. Chưa có FE handoff đúng HTTP version và vector/browser thật. | BE + Security + FE | Giao 428, 201/422 replay, 409, correlation, 202 support và Admin step-up/retry theo version; FE chốt UX sau đó. Không hỏi lại TTL từ terminal. |
| GAP-09 | BE-INT-10 recovery server và GET reference/history đã có; reload trước reference vẫn thiếu cách khôi phục intent ở browser. | BE + BA + FE + Security nếu lưu key | Chốt protected lookup hoặc UX lịch sử/tra soát; chỉ chốt storage/retention nếu được phép. |
| GAP-10 | FR-041–043/BE-INT-08 có notification delivery; FR-053/AC-029 yêu cầu retention/legal hold hệ thống. Không có contract inbox hoặc màn retention; OI-API-07 mở. | BA + Audit + BE | Xác nhận có FE UI hay job nội bộ; nếu UI, bổ sung API/quyền/AC; nếu nội bộ, ghi evidence hệ thống. |
| GAP-11 | Common FR-044–046/048 Must, FR-047 Should; BE fraud runtime đã có một phần, P0/P1 chưa đối chiếu FR/AC. | BA + người điều phối, BE bàn giao evidence | Chốt bảng release theo từng FR/AC và ngoại lệ cho Must nếu hoãn; review mutation theo quyền/evidence. |
| GAP-12 | Common 07 §8.6.3 và BE `dev2` có snapshot/export giới hạn, nhưng `OI-API-11` vẫn hỏi sync/async; maxRows đề xuất, thiếu file mẫu/gate FE. | BE + Audit + FE | Đồng bộ sync/async, giới hạn trong 07/YAML; giao file mẫu và test 413/403/audit/hash/download. |

Rà soát lịch sử trên `origin/main@65bd72f`/`origin/dev2@740ab5f` ngày 21/11/2026: [[digitalbanking-docs/docs-frontend/management/07-main-dev2-gap-review.md]]. Bản **trình chốt hiện hành** ưu tiên common và backend `dev2@740ab5f`, fetch lại 22/11/2026: [[digitalbanking-docs/docs-frontend/management/08-common-dev2-gap-final-review.md]]. Hai bản là đối chiếu nguồn, không thay phê duyệt owner hoặc tự đóng GAP.

### Việc còn lại của GAP-07 sau lát đọc exact-ID

Người phụ trách FE yêu cầu ngày 21/11/2026 lưu rõ phần chưa làm được để tiếp tục sau. Policy hiện có chỉ là điều kiện hiển thị cho customer dashboard và hai quyền đọc staff exact-ID; preview staff vẫn độc lập với phiên thật. Không dùng nó làm căn cứ mở các màn/hành động khác.

| Mã theo dõi | Phần chưa làm | Căn cứ thiếu/mâu thuẫn | Owner cần chốt / điều kiện mở lại |
|---|---|---|---|
| GAP-07-A | Một ma trận role → permission → endpoint/action thống nhất | SRS §4.2 và Security §5.1 cho Auditor đọc dữ liệu customer; 07 §3.4–3.6, seed và BE-INT-03 từ chối `GET /admin/customers/{customerId}` vì thiếu `CUSTOMER_READ`. Các quyền khác cũng cần rà theo endpoint, gồm export và maker/checker. | BE + BA + Security đồng bộ SRS, 07, design/security, seed và OpenAPI khi cần; ghi quyết định/review cụ thể. |
| GAP-07-B | Cách FE biết permission hiện hành | `UserSummary` v1/v2 chỉ trả `roles`/`privileges`, không có permission/capability; role assignment có thể đổi sau login. | BE + Security chốt capability/permission trong session hoặc ma trận tĩnh có version và cơ chế refresh/invalidate; FE chốt policy hiển thị sau phản hồi. Không tự thêm field vào YAML. |
| GAP-07-C | Nối route/menu/action staff với phiên | Staff customer, approval và fraud hiện là preview độc lập; auth preview dùng v2 Draft, còn handoff dev2 là v1. UI cuối chưa duyệt. | Sau GAP-07-A/B, GAP-05 runtime version và duyệt UI phần tương ứng: FE dùng policy tập trung, chặn direct URL, ẩn/khóa action theo quyền, xử lý `403` từ server và xóa cache khi đổi phiên/quyền. |
| GAP-07-D | Xác minh phân quyền thật | Unit/mock chỉ kiểm điều kiện FE, không chứng minh quyền BE hay ownership. | FE + BE + QA chạy FET-12/13 và FET-10 liên quan trên API/browser thật với role, privilege CHECKER, quyền bị thu hồi, cross-user/scope, direct URL và action; lưu FE/BE SHA cùng evidence. |

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

## 16. Đối chiếu main ngày 16/11/2026

Theo yêu cầu đọc main, đã fetch và đọc origin/main@d1efbc6b89418ccd5c3c8ac9af455a71caa4ae49. Main bổ sung thiết kế 05. Modules contract v0.1: contract nội bộ backend, chữ ký/DTO Đề xuất chờ owner review; không thay public HTTP 07/OpenAPI. Public v1 và code backend refactor không đổi từ dev@24606b5; 30 operations, DB_SESSION/LOGIN_NEW_DEVICE, chưa có business implementation hoặc v2. MOD-OI-04/08 về replay/TTL/correlation/ownership còn cần FE phối hợp, không tự áp đề xuất vào client. Giữ các phê duyệt ngày 14/11 và GAP-05/integration mở. Mức chứng minh: đọc source, chưa API/browser. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DEV-REVIEW/2026-11-16-6bceb5d/run-01.md]].

Phân tích ảnh hưởng FE theo yêu cầu tiếp theo ngày 16/11: các phần shared client/session, Money/ID wire, 428 step-up, intent/key/retry, reference lookup, quyền/masking và tests đã nằm trong FE-FND-02/FE-01/03/04/07/REL. Bổ sung căn cứ phối hợp MOD-OI-04 (replay HTTP/TTL/purge) vào GAP-08/09 và MOD-OI-08 (replay correlation/ownership) vào phần transport/GAP-07. Chưa tự quyết policy, sửa YAML hoặc triển khai flow còn thiếu contract. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-DEV-REVIEW/2026-11-16-6bceb5d/run-02.md]].

## 17. Audit quyết định contract ngày 17/11/2026

Đã fetch origin và rà docs main/dev/dev2/rework/identity-pr/feature-transfer. Có DEC-TRF-004/005/012 trên dev2/rework chọn replay 201/422 nguyên status/body, nonterminal 409, TTL tối thiểu 24h từ terminal, correlation body gốc/header mới và quyền source-or-destination. Main/dev chưa đồng bộ toàn bộ; main 07 đã có replay và ownership cơ bản, không coi các phần đó thiếu quyết định từ đầu. Provider/QA/API/FE review và integration vẫn mở; staff scope trong rework còn Blocked.

[[digitalbanking-docs/docs-frontend/management/05-fe-be-contract-handoff.md]] ghi nguồn theo SHA, các lựa chọn đã có, Q-01..05 cần xác nhận bản contract/version giao FE, staff scope, unknown/reload UX và readiness; đồng thời lên lộ trình bước 0..5 theo các task hiện có. Giữ mục tiêu FE/mock v2 đã duyệt; chưa áp semantics nhánh BE vào v2, chưa sửa canonical YAML/source hoặc đóng GAP-05/07/08/09. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CONTRACT-AUDIT/2026-11-17-6bceb5d/run-01.md]].

Theo chỉ đạo tiếp theo ngày 17/11: chỉ gửi BE câu hỏi Q-01..05 ở handoff mục 4.1 (contract/version, permission/scope, recovery server/policy và readiness); mục 4.2 FE-01..06 dành cho người phụ trách FE chốt sau phản hồi BE về UX, recovery client, retry/poll và cách thể hiện. Chưa chốt các lựa chọn FE, chưa nhận câu trả lời BE. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CONTRACT-AUDIT/2026-11-17-6bceb5d/run-02.md]].

Đối chiếu theo common ở lần tiếp theo 17/11: handoff mục 4.0 ghi đáp án trong nguồn cho replay/TTL/correlation/quyền customer và phân loại Q-01..05 theo phần đã có thông tin/phần còn cần BE xác nhận. Nguồn main và dev2/rework chưa đồng bộ; không gọi đây là phản hồi BE trực tiếp hoặc sign-off. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CONTRACT-AUDIT/2026-11-17-6bceb5d/run-03.md]].

Mở rộng thông tin dev2 cùng ngày: handoff mục 3.1 tổng hợp DEC-TRF-001..016 theo tám nhóm, gồm step-up, validation/failure, recovery, audit, limit/fraud, OTP/notification, schema/DTO nội bộ và HTTP boundary candidate. Đây là lựa chọn thiết kế ở dev2@bb6f23f, chưa tự nhận vào FE/mock v2 hoặc chứng minh integration. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CONTRACT-AUDIT/2026-11-17-6bceb5d/run-04.md]].

## 18. FE-FND-02-CLIENT-SESSION ngày 17/11/2026

Theo yêu cầu thực hiện task và giao tài liệu kết quả chi tiết: đã bổ sung shared JSON client và memory session với mock v2 theo ADR-002/07a đã duyệt. 6 gate local đạt, 12 files/119 tests (47 mới), inputs YAML/mock không drift; không sửa preview legacy. Controller/guards/signal đa tab và cleanup callback chờ review task con; UI wiring/verify flow và API/browser tiếp tục theo FE-01-01, task cha/GAP không tự đóng. Không áp semantics Transfer dev2, không tự chọn budget retry/poll hoặc mapping quyền. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CLIENT-SESSION/2026-11-17-6bceb5d/result.md]].

Ngày18/11/2026, theo yêu cầu trình duyệt UI mock FE-01-01: đã khởi động Vite mock port5174, gửi mở panel (queued) và hướng dẫn review. Chưa có xác nhận duyệt UI; giữ task/integration/browser verification mở. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/run-02.md]].


### Cập nhật ngày 18/11/2026 — CUSTOMER sau đăng nhập

Người phụ trách FE chốt trong chat: “tôi muốn sau khi đăng nhập thành công thì vào phần dashboard của customer, và trên màn hình chính của customer thì bỏ phần hiển thị trạng thái hoạt động của tài khoản ( đang hoạt động )”. Quyết định này thay thế phần giữ màn phiên chung sau login **đối với CUSTOMER**: trong preview auth v2, người dùng có userType và role CUSTOMER đến tổng quan `/customer?authPreview=v2`, dùng cùng controller phiên; không seed phiên customer riêng. Màn tổng quan không hiển thị status tài khoản; chi tiết vẫn đọc status theo contract. Vai trò khác giữ màn phiên chung; chưa chốt mapping GAP-07. Preview riêng vẫn tồn tại. Bản mock đang chờ review, không chứng minh runtime v2 hay quyền/ownership BE. Chi tiết: [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-03.md]].


### Run-04 ngày 18/11/2026 — trang chủ customer theo ảnh tham khảo

Theo yêu cầu người phụ trách FE trong chat 18/11, trang chủ mock dùng topbar thương hiệu/lời chào, sidebar trắng với icon và vùng active kiểu ảnh tham khảo. Menu chỉ có Trang chủ, Thông tin tài khoản và Hồ sơ của tôi, là các màn v2 đã nối trong FE-02-01. Nội dung chính hiển thị masked account, số dư khả dụng/asOf và thông tin tài khoản đọc từ GET /accounts; không dựng lãi suất, thẻ, vay, hóa đơn, khuyến mãi hoặc số liệu tổng hợp không có nguồn. Transfer/history có contract nhưng UI v2 thuộc task khác nên chưa mở menu ở đây. Dashboard tiếp tục không có nhãn trạng thái; màn chi tiết vẫn hiển thị status. Browser mock đã xem tại `/customer?authPreview=v2`; ảnh và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-04.md]]. Chờ review UI, không khẳng định API thật.


### FE-FND-03-ICONS — ngày 18/11/2026

Task con FE-FND-03 theo yêu cầu iconography trong chat. Đã dựng SVG ba nhóm, catalog và component tái sử dụng; typecheck/lint đạt. Người phụ trách FE chốt áp dụng customer mock hiện tại trong chat; đã thay icon menu/account/empty và có audit screenshot trước/sau. Trạng thái Chờ review UI/mock; Không đổi logic/route/API hoặc đóng FE-FND-03. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-03-ICONS/2026-11-18-b9ba604/run-01.md]].

## Thứ tự demo UI được chốt ngày 18/11/2026

Người phụ trách FE muốn hoàn thành các task có UI demo trước khi thiết kế lại và duyệt UI cuối. FE-01/02 và FE-FND-03-ICONS hiện giữ trạng thái demo đang chỉnh, chưa phải UI/mock được duyệt. Có thể mở FE-04-01-MOCK phạm vi CUSTOMER read-only theo contract v2 FE/mock; trước khi làm phải lập phiếu riêng và rà filter/response theo nguồn. FE-03-01/02 vẫn giữ phần phụ thuộc GAP-02/08/09 và xác nhận contract/version giao FE. Không tự tích hợp BE hoặc merge từ mốc demo. [[digitalbanking-docs/docs-frontend/evidence/FE-DOC-03/2026-11-18-b9ba604/run-03.md]].
