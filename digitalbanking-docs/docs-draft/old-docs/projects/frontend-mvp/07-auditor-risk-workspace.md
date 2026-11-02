# FE 07 — Audit và cờ rủi ro

Trạng thái: thiết kế; FE 01, BE 06. Nguồn [OpenAPI](../../../contracts/openapi.yaml), [BE audit/risk](../backend-mvp/06-history-audit-risk.md).

## Audit

`/staff/audit`, AUDITOR/ADMIN: GET `/api/v1/audit-events` với eventType, actorId, from, to, limit, cursor. ActorId phải UUID; eventType tối đa 80; không dựng enum kín từ vài mẫu demo. Không có filter targetId hoặc correlationId ở endpoint hiện tại.

Bảng: occurredAt, eventType, actorId nếu có, targetType/targetId, outcome SUCCESS/FAILURE, correlationId, summary. Mở drawer từ row đang có, không gọi detail endpoint chưa tồn tại. Không có sửa/xóa audit, export hoặc raw metadata. Dữ liệu text render như text, không dùng HTML từ server.

## Risk

`/staff/risk`, OPERATOR/AUDITOR/ADMIN: GET `/api/v1/operator/risk-flags` với ruleId, transferId, from, to, limit, cursor. TransferId UUID; ruleId tối đa 80.

Bảng: flagId, transferId, ruleId, ruleVersion, reason, detectedAt. Hai rule hiện tại LARGE_TRANSFER và HIGH_FREQUENCY; có thể cung cấp hai lựa chọn kèm fallback hiển thị rule mới. Một transfer có nhiều flag nên row key là flagId, không phải transferId. Không có status REVIEWED, nút duyệt/bỏ cờ, severity hoặc điểm ML trong contract.

Cờ là cảnh báo theo rule, không khẳng định gian lận hay transfer thất bại. Đánh giá best-effort sau commit; chưa có flag không chứng minh giao dịch an toàn. Có nút cập nhật, không dùng thông điệp “tất cả giao dịch đã được kiểm tra”. Link sang staff transfer detail chỉ thêm khi quyền endpoint và màn hình đó được xác nhận; hiện hiển thị/copy transferId.

## Query và trạng thái

Cursor “Tải thêm” hoặc stack cursor cho Trước/Tiếp; không page number/total giả. Áp dụng quy tắc UTC/from-inclusive/to-exclusive của FE 05. Thay filter reset cursor; query keys theo user/role/filter. Loading/empty/filtered-empty/error/load-more-error riêng; giữ rows hiện tại khi tải thêm lỗi. 401 xử lý session, 403 hiện thiếu quyền, 400 chỉ ra filter sai.

Desktop-first 1024/1440; header/filters xuống dòng ở màn hình nhỏ, bảng cuộn ngang trong container. Drawer đọc được bằng bàn phím, correlation/ID dài xuống dòng, không đẩy nút ra ngoài màn hình.

## Acceptance

Mock all filters, actorId vắng mặt, nhiều flag cùng transfer, unknown rule, empty và error; test quyền role đơn/đa role, route trực tiếp và logout clear cache. Test summary/reason chứa ký tự HTML chỉ render text. BE thật kiểm tra audit của seed/block/transfer và flags của hai rule; không tự kỳ vọng R3 vì chưa được chốt triển khai.
