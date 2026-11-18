# AUTH-CHALLENGE — Challenge xác thực dùng chung

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | Bổ sung FE-01-01 / 17/11/2026 |
| Trạng thái tài liệu | Draft phần UI mới — chờ review |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | AUTH-CHALLENGE |
| Trạng thái UI | Có source preview v2, component/mock tests; browser bị chặn |
| Route preview | /auth/challenge?authPreview=v2 |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-01-auth-and-session.md]] |
| Task thực hiện | FE-01-01/02 |
| Owner | Frontend |
| Component tham chiếu khi triển khai | [[digitalbanking-docs/docs-frontend/design/screens/AUTH-RECOVERY.md]], [[digitalbanking-docs/docs-frontend/design/screens/CUST-TRANSFER.md]] |

## 1. Nguồn UI và phạm vi

Working tree nền frontend@6bceb5d: `digitalbanking-frontend/src/features/auth/v2/AuthScreens.tsx` (V2ChallengeForm), `AuthFlow.tsx`, controller `src/shared/auth/session.ts`, MSW `src/mocks/v2-auth.ts`. UI LOGIN_MFA/OTP theo 07a và YAML v2 FE/mock đã duyệt; chưa có FIDO/passcode/step-up giao dịch. Phê duyệt 09/11 áp dụng khung tài liệu cũ, không phải phần UI mới này.

## 2. Bố cục và thứ tự vùng

Brand → authCard → h1 Xác minh đăng nhập → maskedDestination → lỗi → thời điểm hết hạn/countdown → số lần thử từ phản hồi gần nhất → status → form OTP/verify → resend → đăng nhập lại → Simulation. Thanh điều khiển fixture nằm ngoài flow.

## 3. Component và cấu hình

| Thành phần | Cấu hình / tương tác |
|---|---|
| Input OTP | RHF/Zod 6–8 chữ số, maxLength8, inputMode numeric, autocomplete one-time-code; xóa sau request |
| Xác minh OTP | POST `/api/v2/auth/otp/verify`, challengeId trong memory + otp; thành công200 nhận phiên |
| Gửi lại mã OTP | POST `/api/v2/auth/challenges/{challengeId}/resend` không body; 202 thay metadata, không reset attempts ở FE |
| Đăng nhập lại | Xóa challenge local, về login; không gọi endpoint hủy chưa có contract |
| Mã hỗ trợ | Correlation ID từ response, có thể mở; không render raw detail |

## 4. Style và kích thước

Tái dùng `.auth/.authCard`, `.auth-sub/.spaced` và AntD từ shared layout/foundation; card460/max-width100%, padding32. Không thêm breakpoint/stylesheet riêng.

## 5. Trạng thái và tương tác

Busy ref khóa verify/resend gửi đôi. Wrong OTP401 giữ challenge, ẩn số lần thử cũ đến khi có metadata mới; không tự trừ ở UI. 404/409/410/423 challenge terminal hoặc hết hạn theo expiresAt khóa verify/resend, còn restart. 429 chờ đúng Retry-After, không tự retry hay đặt max-resend/cooldown tùy ý. Chỉ render form hoạt động khi methods đúng `[OTP]`; phương thức khác hiển thị chưa hỗ trợ. Direct URL/reload không có challenge memory về login. Không lưu OTP/challenge vào storage/URL.

## 6. Responsive

Theo AuthLayout: <=767px wrapper căn trên/padding32×20, card24×22; form dọc/nút block. Chưa có browser evidence 360/768/1440, chưa khẳng định không overflow.

## 7. Accessibility và nội dung

Label/htmlFor, aria-invalid/describedby cho lỗi OTP, role alert; status aria-live polite không đọc countdown mỗi giây (countdown aria-hidden). `<time dateTime>` giữ ISO, hiển thị giờ vi-VN Asia/Ho_Chi_Minh. Focus h1 khi chuyển flow. Keyboard/screen reader thực tế chưa kiểm chứng.

## 8. Cập nhật khi triển khai

Mức hiện tại là source/component/MSW; runtime session v2 và FE-01-02/GAP-04, permission GAP-07 vẫn mở. Hướng dẫn fixture/kết quả tại [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 17/11/2026 | Điền design theo UI OTP mới | Source/component/MSW; browser localhost timeout, native app approval timeout; chưa có screenshot |

Cập nhật18/11/2026: browser17/11 đã hiển thị login và màn phiên chung sau reload; lỗi first-load đã thêm gate chờ ServiceWorker.controller trước mount. Bản sửa chưa retest browser do IAB18/11 không attach được. Các giới hạn browser nêu trên tiếp tục mở; kết quả cuối ở [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

### Host mới theo ảnh người dùng18/11/2026

Preview v2 dùng AuthSplitLayout hai nửa thay card cũ: artwork trái/BrandDB và nội dung phải max500px; <=767px ẩn artwork. Component/nội dung/route/lifecycle riêng của màn này giữ nguyên; không đổi contract hoặc quyền. Toolbar fixture thu gọn trong details ở góc dưới trái. Browser run03 có screenshot/mobile login200/reload/logout; chưa kiểm lại toàn bộ challenge/đa tab/API thật. [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/layout-result.md]].
