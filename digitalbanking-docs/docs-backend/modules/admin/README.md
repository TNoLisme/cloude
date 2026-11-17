# admin

## LIMIT_CHANGE — bổ sung E4 ngày 16/11/2026

Admin đã có approval_request thật và hai endpoint `/api/v1/admin/limits`, `/api/v1/admin/limits/{id}/approve`; `{id}` là approvalId. Scope chỉ LIMIT_CHANGE, không generic approval platform/GET approvals/role/retention. Gọi provider qua public API, contract yêu cầu approval/config/audit C2 cùng transaction; native audit implementation đang Blocked trên dev2; permission/CSRF/step-up và maker/checker được kiểm tại các lớp tương ứng.

Audit/identity/customer/account hiện mới có candidate contract trong `<provider>.api`; implementation production và provider-owner review vẫn Blocked. Không có fallback fake production. Test dùng fake session/challenge/staff/scope ở test-only, approval/config PostgreSQL thật; audit là SQL contract fixture ở test-only. Xem [báo cáo E4](../limit/E4-verification.md) để biết phần thật/mock, dependency/owner/điều kiện đóng và kết quả kiểm thử. Bổ sung này không thay owner admin/identity/account hoặc tự xác nhận các owner đã phê duyệt contract.

## Bổ sung — hướng dẫn task docs của Thịnh
Owner chính: Đạt; Thịnh đồng phát triển phần fraud/audit. README chỉ điều hướng.

1. Đọc [tổng quan admin](../_overview/10-admin.md) và [quy trình task của Thịnh](../../workofteam/thinh-backend-workflow.md).
2. [BE-ADM-01 — đề xuất tích hợp fraud/audit](BE-ADM-01-fraud-audit-integration.md) · [verification report](BE-ADM-01-verification-report.md).
3. Contract là draft; liên hệ owner admin để review trước khi ghép. Config tham chiếu tại [deployment/configuration.md](../../deployment/configuration.md).
