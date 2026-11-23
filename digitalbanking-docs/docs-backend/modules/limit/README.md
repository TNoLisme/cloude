# Module Limit

Limit quản lý cấu hình hạn mức CUSTOMER và ACCOUNT, bộ đếm ngày nghiệp vụ và reservation cho các lệnh Transfer đang chờ. Múi giờ mặc định là `Asia/Ho_Chi_Minh`. Nếu cả hai scope áp dụng, T2 kiểm hạn mức từng lệnh và hạn mức ngày của cả hai. [Modules contract §10](../../../docs-common/design/05.%20Modules%20contract.md) · [BE-INT-04](../../workofteam/integration%20modules/BE-INT-04.md)

| Bảng | Quyền sở hữu và dữ liệu |
|---|---|
| `limit_config` (V9, V19) | Limit ghi version, khoảng hiệu lực, trạng thái và liên kết đến phê duyệt Admin. |
| `limit_usage` (V12) | Limit ghi amount/count theo CUSTOMER hoặc ACCOUNT và ngày nghiệp vụ. |
| `limit_transfer_reservation` (V20) | Mỗi Transfer có một reservation với liên kết customer, source account, amount, currency, ngày T1 và policy version. Đi từ PENDING sang SETTLED hoặc RELEASED. |
| `limit_transfer_usage_effect` (V20) | Effect append-only từng scope, ghi cùng counter và settlement. Lưu config ID/version/giá trị, usage ID, amount/count delta. |

`limit.api.TransferLimits` cung cấp LI-01 snapshot, LI-04 guard và step-up, LI-05 reserve, LI-02 check, LI-07 settle/release và LI-06 đọc evidence cho SYSTEM. Tất cả thao tác giữ guard JOIN caller transaction. Guard dùng PostgreSQL advisory xact lock theo customer và được chia sẻ giữa Transfer, Admin và API Limit cũ. Candidate dùng `TransferLimitAdapter`; BE-INT-05 đã đấu nối T1. BE-INT-06 nối T2 qua `findPendingReservation` dưới guard mới và LI-02/07. Đọc evidence và xử lý recovery tiếp tục ở BE-INT-10. Snapshot hiển thị đọc trên primary và có thể thay đổi khi giao dịch khác commit.

`GET /api/v1/limits` yêu cầu session CUSTOMER có `LIMIT_READ_SELF`. Khi truyền `accountId`, Account provider kiểm ownership. Truy vấn chỉ đọc và trả config có hiệu lực tại `asOf`, từng scope có used/remaining, remaining tối thiểu bằng 0. Khi thiếu config, items rỗng và effective fields null.

`LimitConfigMutationPort` tham gia transaction maker/checker của Admin. Bản cũ tiếp tục có hiệu lực đến cutover nếu chưa hết hạn sớm hơn. Bản mới có hiệu lực từ thời điểm muộn hơn giữa yêu cầu và thời điểm duyệt trên primary. ACCOUNT lần đầu chỉ tính các lệnh hoàn tất từ cutover. Thay version giữ nguyên counter. [API contracts §8.5](../../../docs-common/07.%20API%20contracts.md) · [Database design §4.5](../../../docs-common/04.%20Database%20design%20%26%20analysis.md)
