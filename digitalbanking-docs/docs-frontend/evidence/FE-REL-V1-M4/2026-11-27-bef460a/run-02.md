# Phiếu chạy FE-REL-V1-M4 / run-02

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 27/11/2026 Asia/Bangkok / Codex |
| Task cha / con | FE-REL-V1-M4; xử lý handoff GAP-07 trước các lát FE-06-01/FE-05-02/FE-06-02/FE-07-01 |
| FE SHA / working tree | Nền `bef460a66bb8087afa31986406638a7952cf0a49`; working tree đã có cập nhật tracker và handoff từ run-01, chưa commit |
| BE SHA / môi trường | Source BE trên nhánh tích hợp; controller source hiện có; chưa gọi runtime API M4 trong lần chạy này |
| Contract | OpenAPI BE v1.1.0 (`digital-banking-v1.yaml`) |
| Mức chứng minh | Đọc tài liệu/source; chưa API/browser |
| Nguồn đã đọc | FE README, tracker 03, roadmap/plan 09, lifecycle 03/template, common 07 §§3.4–3.6/8.2/8.6/8.7/8.8, OpenAPI v1, BE-INT-03, BE-ADM-API-03/CON-01, BE-AUD-02, BE-FRD-01, Security/SRS handoff, FE-06/07 feature, backend SecurityFilterChainConfig và controllers |
| Phạm vi được phép | Chat 27/11/2026: người phụ trách FE nói BE và Security vừa cho phép mình tự chốt handoff; yêu cầu hỗ trợ giải quyết. Người phụ trách FE trước đó chấp nhận đề xuất M4 trong chat. Ghi theo báo cáo ủy quyền này, không ghi BE/Security trực tiếp ký quyết định. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| GAP-07 handoff cho M4 | Người phụ trách FE báo BE và Security cho phép FE tự chốt handoff; các policy FE được chốt gồm read-only, role chỉ điều hướng, server gate từng API, FE xử lý 403/xóa dữ liệu tương ứng khỏi cache, không thêm capability vào UserSummary | Chat hiện tại 27/11/2026: “bên phía be vừa yêu cầu tôi có thể tự chốt handoff cho be và security cũng vậy thì bạn có thể giúp tôi giải quyết vấn đề đó không”; cùng quyết định chấp nhận baseline đề xuất trước đó | Có, để triển khai FE theo ma trận nguồn và chạy QA; đây không phải owner approval cho việc sửa policy common/Security |
| Auditor customer profile | Common 07/BE-INT-03 cho Auditor ACCOUNT_READ, không CUSTOMER_READ; SRS/Security có mâu thuẫn | Giữ theo common/BE; không mở customer profile cho Auditor; không sửa tài liệu owner | Có phần không phụ thuộc; ghi vấn đề owner còn mở |
| Approval GET runtime | `ApprovalAdminController` source có mặt; `BE-ADM-API-03` và `BE-ADM-CON-01` bất nhất về trạng thái tích hợp | Chỉ metadata; visibility do server; không render payload/result; không có runtime evidence thì loại lát khỏi M4 | Có thể triển khai phía FE; chưa được nhập merge |
| Audit GET runtime | `AuditController` source có mặt; BE-AUD-02 verification ghi chờ ghép REST/service | Read list/detail, không export; cần runtime QA | Có thể triển khai phía FE; chưa được nhập merge |
| Fraud/customer GET runtime | Controller/security matchers source có mặt; source không thay cho HTTP role/scope evidence | Theo common 07; exact-ID/read-only; phone search và POST review ngoài M4 | Có thể triển khai phía FE; chưa được nhập merge |
| UI | Màn preview hiện tại được chấp nhận cho lát M4 theo chốt trước đó | Dùng UI mock staff hiện hữu; UI cuối có thể chỉnh sau | Có |

## 3. Thay đổi và kịch bản

Đã cập nhật riêng handoff Backend và Security/BA, cùng GAP-07 tracker. Baseline handoff FE đã chốt theo thông tin ủy quyền người phụ trách FE báo lại. Tài liệu không ghi BE/Security trực tiếp phê duyệt, không tự sửa source policy của họ. Các mâu thuẫn nguồn và runtime được giữ như điều kiện QA/merge.

Không sửa code trong run này. Source inspection xác nhận `SecurityFilterChainConfig` có authorization matchers cho audit, admin customer/account, approval, fraud list và fraud review. Source controllers hiện có cho audit, fraud, approval, admin customer accounts và customer admin detail. Đây chỉ là bằng chứng đọc source, không chứng minh endpoint hoạt động, DB data, permission seed, scope/masking hay response v1.1.0 ở runtime.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Trạng thái | Evidence / command / timestamp |
|---|---|---|---|---|
| Chốt handoff GAP-07 FE cho M4 | Vai trò mỗi bên và ranh giới được ghi trung thực | Handoff BE/Security ghi quyết định FE theo báo cáo ủy quyền; không đại diện chữ ký owner | Pass — quyết định FE | `management/12-m4-be-handoff.md`, `management/13-m4-security-handoff.md`; 27/11/2026 |
| Đối chiếu policy với common/BE | Không suy quyền ngoài nguồn | Dùng common 07/BE-INT-03; Auditor không CUSTOMER_READ; giữ tài liệu SRS/Security mâu thuẫn mở | Pass — source review | `management/13-m4-security-handoff.md`; 27/11/2026 |
| Xác nhận API M4 runtime | HTTP role/scope/response đạt | Lệnh `docker compose -f compose.qa.yaml ps --format json` đầu tiên dừng ở parse cấu hình vì lần gọi thiếu `--env-file`; đây là thiếu sót trong lệnh kiểm tra, không chứng minh QA stack không thể chạy. M2/M3 đã dùng env file tạm với credential QA tổng hợp. API M4 chưa được gọi/kiểm tra trong run này. | Chưa chạy — cần lặp lại với env tạm như M3 | 27/11/2026; không ghi credential vào repo/evidence |
| Kiểm tra tài liệu | `git diff --check` sạch | Pass sau cập nhật handoff/tracker/plan | Pass | `git diff --check`; 27/11/2026 Asia/Bangkok |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Handoff BE | [[digitalbanking-docs/docs-frontend/management/12-m4-be-handoff.md]] — baseline FE chốt; runtime proof còn là gate |
| Handoff Security/BA | [[digitalbanking-docs/docs-frontend/management/13-m4-security-handoff.md]] — ghi rõ quyết định FE theo ủy quyền được báo lại; không giả nhận phê duyệt owner |
| Tracker/GAP | [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] — policy baseline đã chốt; GAP tổng chỉ đóng sau runtime/source owner synchronization/FET |
| Review UI | UI mock M4 được chấp nhận trong chat trước đó; design cuối vẫn chưa chốt |
| Merge | Chưa merge; handoff chốt không thay API/browser QA hoặc merge gate |

## 6. Kết quả lần chạy

Vấn đề “ai có quyền chốt handoff” được giải quyết cho phạm vi FE M4 theo thông tin người phụ trách FE báo: FE có thể tự chốt handoff với BE và Security. Quyết định handoff FE hiện đủ rõ để bắt đầu triển khai theo common 07/OpenAPI v1.1.0. Không cần hỏi lại chính sách FE trong phạm vi đã nêu.

M4 chưa được merge. Còn phải triển khai từng lát, xác minh API thật theo role/scope/response và browser critical flow; approval/audit chỉ vào merge nếu runtime chứng minh sẵn sàng. GAP-07 chưa đóng toàn cục vì mâu thuẫn tài liệu owner và evidence runtime vẫn còn. Chưa commit hoặc push trong lần chạy này.
