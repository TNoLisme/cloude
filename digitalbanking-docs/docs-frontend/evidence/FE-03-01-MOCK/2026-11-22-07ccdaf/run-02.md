# Phiếu chạy FE-03-01-MOCK / run-02

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 22/11/2026 Asia/Bangkok / Codex FE |
| Task cha / con | FE-03-01 / FE-03-01-MOCK, [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] |
| FE SHA / working tree | Nền `frontend@07ccdafdb3b3c363bfb5615ecd78e8b83ca2ab65`, sạch trước phiếu |
| BE SHA / môi trường | Không dùng API BE thật; `origin/dev2@740ab5f` chỉ tham chiếu |
| Contract | 07 §8.3, YAML v2 Draft FE/mock `TransferCreateRequest`/GET accounts; không coi là runtime v2 |
| Mức chứng minh | Dự kiến browser mock, source/component/MSW regression; FET-04–07/API thật chưa áp dụng |
| Nguồn đã đọc | FE README, tracker GAP-02/08/09, roadmap/lifecycle/template, FE-03, CUST-TRANSFER, API-DATA/IDEM, FE-AUTH-03, FE-TEST-03/04, SRS/07/security/YAML và run-01 |
| Phạm vi được phép | Người phụ trách FE yêu cầu “cứ thực hiện task fe 03-01-mock và FE-07-01-MOCK rồi tôi chốt ui fe 08 sau”, chat ngày 22/11/2026. Hoàn tất kiểm mock/demo, không mở mutation đang chờ GAP. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| GAP-02 | Không có recipient masked lookup | Fixture chỉ xem/khóa thao tác chưa rõ, chat 13/11; yêu cầu tiếp tục mock 22/11 | Có cho form/review, không ghi đã xác minh người nhận |
| GAP-08/09 | Step-up/retry/recovery chưa có version runtime FE thống nhất | Không có chốt mới | Không POST/OTP/key/recovery; nút gửi khóa |

## 3. Thay đổi và kịch bản

Đã có preview `/customer/transfers/new?transferPreview=v2` từ run-01, source/component đạt nhưng browser chưa xem. Lần này kiểm trên browser tình huống mặc định, review, rỗng/lỗi nếu khả dụng; sửa lỗi thực tế nếu thấy; cập nhật evidence và tracker. Không thêm handler nghiệp vụ.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Kết quả | Evidence / command / timestamp |
|---|---|---|---|---|
| Browser default/review | Form và cảnh báo người nhận chưa xác minh; nút gửi disabled | Chrome local `127.0.0.1:5174` hiển thị form; chọn account `**** **** 3001`, nhập destination tổng hợp `1234567890` và `150000.00`, xem review: nguồn masked, destination ghi “chưa xác minh”, Money hiển thị `150.000,00 ₫`, nút “Gửi lệnh · chờ chốt contract” disabled | Pass browser subset | Chrome Windows/Vite mock ngày 22/11/2026; không lưu screenshot file |
| Mock scenarios/network | GET accounts; no POST/BE fallback | Chọn “Danh sách rỗng”: account source trở về placeholder, hiện “Không có tài khoản nguồn ACTIVE trong fixture.” Component/MSW test chỉ thấy GET `/api/v2/accounts`; không có handler POST transfers và nút gửi khóa | Pass empty browser + MSW; error/slow chưa xem browser lần này | Chrome local; `vitest run src/mocks/TransferPreview.test.tsx` 2/2 pass |
| Gates | Focused tests và các checks phù hợp thay đổi | Focused 2/2 pass; `node scripts/check-api.mjs` v1/v2 match exit 0; `git diff --check` exit 0. Không đổi code của task nên không chạy lại lint/typecheck/build; run-01 đã ghi các checks đó đạt tại source hiện hành | Pass checks đã chạy; không gán full gate mới | Direct binary trong node_modules, 22/11/2026 |

## 5. Tài liệu và review

| Hạng mục | Kết quả / liên kết |
|---|---|
| Feature / design / guide | Không đổi hành vi/UI; bổ sung nhật ký browser vào CUST-TRANSFER và tài liệu kết quả, guide từ run-01 vẫn đúng |
| Tracker / GAP | FE-03-01-MOCK chuyển sang Chờ review UI/mock — đã xem browser subset; GAP-02/08/09 giữ mở |
| Review UI của người dùng | Chưa duyệt |
| Review tích hợp / PR | Chưa có |
| Readiness / xác nhận merge | Chưa có |

## 6. Kết quả lần chạy

Preview có browser default/review/empty và focused MSW tests đạt. Đây là demo chỉ đọc form/review, chờ người phụ trách FE xem/chỉnh thiết kế cuối; FE-03-01 cha và FET-04–07 vẫn mở vì chưa có recipient confirmation/step-up/recovery và API thật. Không tự tạo transfer, OTP hoặc idempotency key. Không push/merge từ mức mock. Lần này không có code sửa; commit local nếu tạo chỉ chứa evidence/design/tracker, FE SHA được báo trong chat.
