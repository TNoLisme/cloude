# FE 05 — Lịch sử và chi tiết giao dịch

Trạng thái: thiết kế; phụ thuộc FE 01/04, BE 05–06. Nguồn [OpenAPI](../../../contracts/openapi.yaml), [API baseline](../../baseline/api-and-team-contract.md).

## API và màn hình

`/customer/transfers` gọi GET `/api/v1/transfers` với status/from/to/limit/cursor. Mặc định limit 20, tối đa 100. Không thêm search memo, lọc amount, direction hoặc accountId chưa có trong endpoint. Cột/card: thời gian, direction, counterpartyDisplayName, counterpartyAccountMasked, tiền, trạng thái, memo và link detail.

`/customer/transfers/:transferId` gọi GET `/api/v1/transfers/{transferId}`. Hiển thị transferId, status, amount/currency, memo, createdAt, completedAt nếu có, expiresAt khi có và failureCode được dịch sang thông báo. Detail không bảo đảm có displayName/masked number như list: dùng account IDs theo schema hoặc ngữ cảnh list hợp lệ; không bịa tên khi deep link.

## Cursor và bộ lọc

Chọn “Tải thêm” cho lịch sử; UI không decode hoặc tự chế cursor. Lưu nextCursor do server trả, null thì hết. Không hiển thị tổng số trang/tổng giao dịch vì response không cung cấp total. Reset page/cursor khi đổi filter; giữ filter và cursor trong query key; deduplicate transferId khi ghép page, không tự sửa thứ tự nghiệp vụ.

Bộ lọc thời gian theo giờ Việt Nam, đổi UTC khi gọi API: from inclusive, to exclusive; yêu cầu from < to. Nhãn UI ghi rõ mốc kết thúc không bao gồm; không ngầm coi to là cuối ngày. Chưa áp dụng date-only khi chưa có chuyển đổi rõ ràng.

## Trạng thái và quyền

| Status | Nhãn | Thông tin |
|---|---|---|
| AWAITING_OTP | Chờ xác thực OTP | Tiền chưa bị trừ; chỉ source owner có lối vào bước confirm |
| COMPLETED | Hoàn tất | Hiện completedAt nếu có |
| EXPIRED | Hết hạn | Không tự gửi lại hoặc tạo giao dịch |
| FAILED | Thất bại | Hiện failureCode được hỗ trợ; unknown code có fallback |

Người gửi thấy mọi trạng thái; người nhận chỉ thấy COMPLETED. 404 không phân biệt không tồn tại với không có quyền. Detail chỉ tải dữ liệu đang được phép trong phiên, không tái sử dụng cache người khác. Có nút cập nhật trạng thái; không tự thêm polling vô hạn.

Initial loading/empty/filter-empty/error tách biệt. Lỗi tải thêm giữ page đang xem và nút thử lại. Chưa có dữ liệu không có nghĩa không tồn tại giao dịch ở server nếu request lỗi.

## Acceptance

Mock 4 trạng thái, unknown status, nextCursor/null, page trùng ID, đổi filter, timestamp tie và 401/404/503. Test chuyển UTC đúng, không decode cursor, không tổng trang giả. Desktop bảng/mobile card giữ cùng nội dung quan trọng. BE thật xác minh incoming không lộ pending/failed và status sau confirm/expiry đúng. Ghi screenshot, lệnh và API integration state sau implementation.
