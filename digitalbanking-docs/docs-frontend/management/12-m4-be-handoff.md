# Handoff M4 cho Backend

| Thuộc tính | Giá trị |
|---|---|
| Ngày lập | 27/11/2026 |
| Người yêu cầu | Người phụ trách FE; chat 27/11/2026 yêu cầu giải quyết GAP và merge M4 |
| Nhánh/contract đối chiếu | `codex/fe-be-v1-integration`, OpenAPI v1.1.0, nguồn BE `dev2@740ab5f` đã ghép |
| Trạng thái | Baseline handoff M4 đã được người phụ trách FE tự chốt sau khi BE cho phép chốt handoff (theo thông tin FE ngày 27/11/2026); QA runtime vẫn bắt buộc |

## Mục tiêu

M4 dự kiến chỉ nối các màn đọc staff: exact-ID customer/account, approval metadata, fraud list/detail read-only, audit list/detail read-only. Phone search, fraud decisions, audit export, các thay đổi limit/role và mọi mutation ngoài phạm vi.

Ngày 27/11/2026, người phụ trách FE báo BE cho phép FE tự chốt handoff; người phụ trách FE chấp nhận baseline đề xuất: M4 read-only, role session chỉ dùng để điều hướng vào vùng staff, BE kiểm tra quyền trên từng request, FE xử lý `403` và loại dữ liệu tương ứng khỏi cache. Đây là quyết định handoff FE theo ủy quyền được báo lại trong chat, không phải trích dẫn hoặc phê duyệt trực tiếp do BE ký. Việc xác minh implementation/runtime và response vẫn là gate trước merge từng lát.

## Ma trận handoff để FE triển khai và đối chiếu runtime

| Endpoint/lát | Role/permission theo tài liệu nguồn | Scope/response | BE cần xác nhận |
|---|---|---|---|
| `GET /admin/customers/{customerId}` | OPERATIONS/ADMIN + `CUSTOMER_READ`; AUDITOR thiếu `CUSTOMER_READ` | Exact ID single-bank; contact staff masked; thiếu quyền 403 trước lookup | Baseline common 07/BE-INT-03; phải QA role/runtime |
| `GET /admin/customers/{customerId}/accounts`, `GET /accounts/{accountId}` staff path | OPERATIONS/ADMIN/AUDITOR + `ACCOUNT_READ` | Exact ID; account masked; server kiểm scope/ownership | Baseline common 07/BE-INT-03; phải QA role/runtime |
| `GET /admin/approvals`, `GET /admin/approvals/{approvalId}` | OPERATIONS/ADMIN; server-side maker/checker visibility | Out-of-scope detail 404; FE chỉ metadata, payload/result ẩn | Baseline OpenAPI; xác minh controller/runtime do BE docs đang bất nhất, nếu chưa sẵn sàng thì loại lát này khỏi merge |
| `GET /fraud-flags` | OPERATIONS + `FRAUD_REVIEW`; ADMIN/AUDITOR + `FRAUD_READ` | Read list/filter/page; không POST review | Baseline common 07 §8.7; phải QA role/runtime |
| `GET /audit`, `GET /audit/{auditId}` | OPERATIONS/ADMIN/AUDITOR + `AUDIT_READ` | List/detail read-only, masked; không export | Baseline common 07 §8.6; xác minh controller/response/runtime trước merge |

## Nội dung BE cần phản hồi

Policy handoff đã chốt ở phía FE theo ủy quyền được báo lại. Trước khi merge, implementation cần ghi FE/BE SHA, test HTTP/DB cho role đủ/thiếu quyền và scope 403/404, response v1.1.0, controller chưa ghép và issue còn mở. Lát không có runtime/evidence sẽ không nhập vào M4 merge.

Ngày 27/11/2026, QA HTTP với image BE đã dùng ở M3 xác nhận các GET M4 theo role cơ bản và 404 cho approval/audit ID không tồn tại (phiếu `FE-REL-V1-M4/run-03`). `SecurityFilterChainConfig` và năm controller GET tương ứng đã có trong source. Không phát hiện thiếu endpoint hay matcher cần sửa BE code để bắt đầu nối bốn màn FE; response có dữ liệu, masking và scope sâu vẫn cần kiểm chứng trước merge. Không đổi code BE trong lần đồng bộ handoff này.

Không cần thay contract cho role routing ở FE nếu server đã thực thi authority trên từng request. Nếu runtime khác ma trận/shape hiện tại, BE chỉ rõ nguồn owner cần sửa và đề xuất version/migration; FE không tự đổi OpenAPI hoặc coi docs cũ là runtime proof.

## Nguồn đã rà

- `docs-common/07. API contracts.md` §3.4–3.6, §8.2, §8.6, §8.7, §8.8.
- `digital-banking-v1.yaml`: operations `getAdminCustomer`, `getAdminCustomerAccounts`, `listApprovals`, `getApproval`, `listFraudFlags`, `searchAudit`, `getAuditRecord`.
- `docs-backend/workofteam/integration modules/BE-INT-03.md` và verification.
- `docs-backend/modules/admin/BE-ADM-API-03.md`, `BE-ADM-CON-01.md`.
- `docs-backend/modules/fraud/BE-FRD-01-rules-and-review.md` / verification.
- `docs-backend/modules/audit/BE-AUD-02-query-export-verification.md`.

## Điều kiện mở M4 API

Handoff policy đã chốt theo thông tin BE cho phép người phụ trách FE tự chốt. M4 chỉ merge lát có endpoint runtime và QA đạt; phần chưa sẵn sàng bị loại khỏi merge M4 và giữ issue riêng. Không tuyên bố API sẵn sàng chỉ từ OpenAPI.
