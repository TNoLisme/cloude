# Phiếu chạy FE-REL-V1-M4 / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 27/11/2026 Asia/Bangkok / Codex |
| Task cha / con | M4: FE-06-01-READ-API, FE-05-02-READ-API, FE-06-02-READ-API, FE-07-01-READ-API |
| FE SHA / working tree | `bef460a66bb8087afa31986406638a7952cf0a49`, clean tại đầu lần chạy |
| BE SHA / môi trường | Source `dev2@740ab5f` đã ghép vào nhánh tích hợp; chưa gọi API M4 |
| Contract | OpenAPI BE v1.1.0; M4 chưa sẵn sàng nối UI/API do GAP-07 và UI review |
| Mức chứng minh | Đọc tài liệu/source; phần triển khai bị chặn |
| Nguồn đã đọc | AGENTS.md; FE README/tracker, roadmap 04, plan 09, lifecycle 03/template; GAP-07 trong tracker và bản rà common/dev2 08; issue log 11; OpenAPI v1; feature FE-05/06/07; standards auth/API/testing; preview evidence FE-05-02/06-01/06-02/07-01 |
| Phạm vi được phép | Chat ngày 27/11/2026: “hãy thực hiện task m4 giúp tôi”. Plan 09 cho phép M4 sau khi UI được duyệt và GAP-07 matrix/capability đúng v1. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| GAP-07-A quyền theo role/endpoint | Common 07/BE có quyền exact-ID staff; SRS/Security và BE seed/runtime còn khác nhau, đặc biệt Auditor CUSTOMER_READ và các quyền Approval/Fraud/Audit | Chưa có quyết định cụ thể trong chat hiện tại | Không nối bất kỳ route staff vào session/API |
| GAP-07-B capability phiên/refresh | Auth v1 UserSummary không trả permission; chưa có policy/cơ chế refresh thống nhất để FE suy quyền chỉ từ role | Chưa có quyết định BE/Security cụ thể | Không dựng role-to-screen/action policy |
| GAP-07-C/D và UI | Plan 09 yêu cầu permission matrix, xác nhận staff session và UI từng màn đã được duyệt; tracker ghi các preview staff độc lập, chờ review UI | Không có chốt UI cho M4 trong chat | Không nối preview staff hoặc kiểm chứng direct URL/API |
| GAP-03/11/12 | Phone search không thuộc API v1; fraud decision và audit export nằm ngoài read-only M4 | Giữ theo các câu chốt trước: đọc ID; fraud chỉ đọc; export ngoài phạm vi | Có thể tiếp tục sau khi GAP-07/UI được chốt; không thêm các thao tác này |

## 3. Thay đổi và kịch bản

Không sửa code. Đã lập câu hỏi chốt GAP-07 tại chat ngày 27/11/2026; task M4 và các lát phụ thuộc giữ Bị chặn đến khi có phản hồi. Contract v1 có các GET `getAdminCustomer`, `getAdminCustomerAccounts`, `listApprovals`, `getApproval`, `listFraudFlags`, `searchAudit`, `getAuditRecord`; sự tồn tại của operation không tự xác nhận quyền staff, khả năng phiên hoặc duyệt UI.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Trạng thái | Evidence / command / timestamp |
|---|---|---|---|---|
| Readiness M4 | Role/permission/capability rõ và UI từng phần duyệt | GAP-07-A/B/C/D và UI M4 còn mở | Blocked | Tracker GAP-07, plan 09; 27/11/2026 |
| API/browser M4 | FET-10/11/12/13 theo endpoint và staff role | Chưa chạy, không tạo cấu hình giả định | Blocked | Chờ chốt GAP-07 và duyệt UI |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature/design | Chưa đổi; đợi chốt quyền và UI |
| Tracker/GAP | Sẽ cập nhật sau khi người phụ trách FE chốt phạm vi; các điểm source owner BE/BA/Security phải giữ trạng thái cần owner xác nhận |
| Review UI | Chưa duyệt M4 |
| Review tích hợp/PR | Chưa bắt đầu |
| Merge | Chưa sẵn sàng; không có chỉ đạo merge |

## 6. Kết quả lần chạy

M4 chưa thể triển khai an toàn theo v1 vì không có role/permission/capability matrix được xác nhận và các màn staff chưa được duyệt cho lát tích hợp. Chờ người phụ trách FE chốt câu hỏi GAP-07; không suy quyền từ fixture hoặc tên role. Sau chốt sẽ mở lại phần phù hợp, đối chiếu lại contract/runtime và hoàn thành phiếu run tiếp theo.

### Bổ sung sau quyết định FE ngày 27/11/2026

Người phụ trách FE chốt đề xuất trong chat: M4 read-only; FE dùng role phiên để mở vùng staff, BE gate trên từng request; FE xử lý 403 và không giữ dữ liệu không được phép trong cache; không bổ sung capability vào UserSummary cho lát này. Đây là quyết định FE, không ghi thay xác nhận BE/Security. Handoff cụ thể đã lập tại [[digitalbanking-docs/docs-frontend/management/12-m4-be-handoff.md]] và [[digitalbanking-docs/docs-frontend/management/13-m4-security-handoff.md]]. Phát hiện source docs BE bất nhất: BE-ADM-API-03 ghi GET approval implemented, còn BE-ADM-CON-01 ghi API-APR-01/02 NOT_STARTED/BLOCKED; BE-AUD-02 report ghi audit REST còn chờ ghép. Do đó chưa có căn cứ nối/merge các lát này cho tới khi BE owner chỉ branch/SHA/runtime và test evidence. SRS/Security Auditor CUSTOMER_READ cũng cần owner giải quyết. Không merge M4 ở lần chạy này.
