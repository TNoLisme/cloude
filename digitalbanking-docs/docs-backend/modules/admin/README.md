# admin

## LIMIT_CHANGE — bổ sung E4 ngày 16/11/2026

Admin đã có approval_request thật và hai endpoint `/api/v1/admin/limits`, `/api/v1/admin/limits/{id}/approve`; `{id}` là approvalId. Scope chỉ LIMIT_CHANGE, không generic approval platform/GET approvals/role/retention. Gọi provider qua public API, contract yêu cầu approval/config/audit C2 cùng transaction; native audit implementation đang Blocked trên dev2; permission/CSRF/step-up và maker/checker được kiểm tại các lớp tương ứng.

Audit/identity/customer/account hiện mới có candidate contract trong `<provider>.api`; implementation production và provider-owner review vẫn Blocked. Không có fallback fake production. Test dùng fake session/challenge/staff/scope ở test-only, approval/config PostgreSQL thật; audit là SQL contract fixture ở test-only. Xem [báo cáo E4](../limit/E4-verification.md) để biết phần thật/mock, dependency/owner/điều kiện đóng và kết quả kiểm thử. Bổ sung này không thay owner admin/identity/account hoặc tự xác nhận các owner đã phê duyệt contract.
