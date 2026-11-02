# FE 04 — Chuyển tiền, PIN/OTP và đối soát kết quả

Trạng thái: thiết kế. Phụ thuộc FE 01–03, BE 05. Nguồn [OpenAPI](../../../contracts/openapi.yaml), [6 quyết định đã chốt](../2026-10-01-security-transaction-refinements.md), [API baseline](../../baseline/api-and-team-contract.md).

## Flow màn hình

Route `/customer/transfers/new`: người nhận/số tiền → xác nhận/PIN → OTP nếu cần → kết quả. OTP là một bước đầy đủ trên mobile. Giao dịch đang xử lý không thể chỉnh payload; muốn sửa là thao tác mới sau khi kết quả cũ được đối soát. Không có API cancel hoặc resend transfer OTP, không vẽ nút có chức năng đó.

| Thao tác | API / dữ liệu |
|---|---|
| Chọn source | GET /api/v1/accounts; Customer-owned ACTIVE account |
| Xác minh người nhận | POST /api/v1/recipients/resolve, `{accountNumber}`; RecipientConfirmation |
| Tạo transfer | POST /api/v1/transfers + Idempotency-Key; CreateTransferRequest |
| Xác nhận OTP | POST /api/v1/transfers/{transferId}/confirm-otp, `{otp}`; không cần key mới |
| Đối soát có ID | GET /api/v1/transfers/{transferId} |

Người nhận nhập 8–34 chữ số theo OpenAPI; không thu hẹp còn 12 chỉ vì BE generator dùng 12. Hiển thị recipientDisplayName và masked number từ resolve. Đổi input người nhận xóa kết quả cũ, tránh response resolve cũ ghi đè response mới. Chỉ submit khi kết quả resolve khớp input hiện tại.

Amount string nguyên VND 2.000–10.000.000; currency VND; memo tối đa 140; PIN 6 số. Review hiển thị source/destination masked, tên, tiền và memo. Không dùng Number/float làm số tiền API. BE quyết định đủ số dư và account eligibility.

## State và response

| Trạng thái UI / response | Hành vi |
|---|---|
| editing/resolving/reviewing | Kiểm tra form; chưa có money request |
| submitting | Khóa double submit; giữ intent/key trong memory |
| 201 COMPLETED | Hiện hoàn tất, tải lại accounts/history |
| 200 AWAITING_OTP | Giữ transferId; hiện OTP/countdown TTL, chưa trừ/giữ tiền |
| 200 replay Transfer | Render theo status thực, không tạo transaction mới |
| OTP 400 OTP_INVALID | Cho nhập lại; không tự tính số attempts server |
| OTP lần 5: 409 STATE_CONFLICT | Đọc detail để hiển thị FAILED; các lần sau vẫn 409 |
| 409 TRANSFER_EXPIRED | Hiển thị hết hạn; thao tác mới do khách chủ động |
| 409 INSUFFICIENT_FUNDS / ACCOUNT_NOT_ELIGIBLE | Hiện lỗi, tải lại account/detail phù hợp |
| 409 IDEMPOTENCY_KEY_REUSED | Dừng retry, báo request conflict, không coi là replay |
| 400 PIN_INVALID / 403 PIN_LOCKED | Lỗi PIN/khóa tạm; không đi vào bước OTP |
| timeout/network/503 | Outcome unknown; đối soát trước khi mời chuyển lại |

Đồng hồ local về 0 không tự kết luận transaction đã EXPIRED: đọc server. Không suy ra outcome từ nút đóng hoặc AbortController. Trạng thái terminal không bị timer client ghi đè.

## Idempotency và secret lifecycle

Tạo UUID key một lần khi người dùng xác nhận intent. Cùng retry giữ source, destination, amount, currency, memo và key. Giữ request nhạy cảm chỉ trong request closure khi cần, xóa PIN sau khi request kết thúc; nếu retry POST cần PIN, yêu cầu nhập lại, không persist. Backend loại PIN khỏi payload hash nhưng xác minh PIN mỗi lần gọi, kể cả replay.

Có transferId thì GET detail để đối soát. Chưa có ID và request tạo timeout: cho retry cùng key/business payload, nhập lại PIN, không sinh key mới. Retry confirm dùng cùng transferId; completed replay không được làm chuyển tiền lần hai. Không tự replay mutation bằng query library.

Dispatch lỗi: initial 503, compensation thường tạo FAILED/OTP_DISPATCH_FAILED; replay cùng key trả current state, không gửi mã lại. Nếu concurrent confirm đã hoàn tất thì render COMPLETED. Nếu process crash để lại pending thì hiển thị chờ/hết hạn từ server, không khẳng định SMS được giao thành công.

Reload làm mất intent memory: lịch sử có thể giúp đối soát nhưng không đảm bảo tìm đúng bằng cách đoán số tiền/thời gian. Hiện hướng dẫn kiểm tra trước khi tạo mới; thiết kế lưu intent xuyên reload nằm ngoài phạm vi đang chốt. Không lưu PIN/OTP để giải quyết vấn đề này.

## Cache và acceptance

Thành công invalidate account list/detail và history; luôn dùng response BE làm kết quả. Pending/failed không optimistic debit. Source và destination có visibility khác nhau; chỉ người gửi được confirm.

Mock/test: 5.000.000 so với 5.000.001; resolve race; double click; wrong PIN; OTP 1–4/lần 5/hết hạn; balance/status thay đổi giữa PIN và OTP; timeout trước/sau commit; 503 dispatch; same-key replay; unknown enum fallback. E2E BE thật kiểm tra chỉ một debit qua retry/confirm đồng thời, dùng mailbox local có guard. Không tuyên bố đã test concurrency chỉ từ mock UI.
