# CUST-PIN-SETUP — Thiết lập PIN

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | CUST-PIN-SETUP |
| Trạng thái UI | Có source UI |
| Route trong code | /customer/pin/setup |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-02-customer-and-accounts.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/accounts/AccountPages.tsx dòng 255–398](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/accounts/AccountPages.tsx#L255-L398)

Mô tả theo component và CSS của commit trong metadata. Quy tắc đích theo đặc tả tính năng liên kết và công việc trong tracker.

## 2. Bố cục và thứ tự vùng

Heading theo mode, subtitle PIN gồm 6 chữ số → Panel narrow Bảo vệ giao dịch của bạn → ErrorPanel → Form PIN giao dịch → xác nhận PIN → CTA.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| PIN-NEW | CodeField secret | name pin, label PIN giao dịch | Required đúng 6 số |
| PIN-CONFIRM | Form.Item/Input.Password | name confirmPin, numeric/maxLength6/autocomplete off, ẩn toggle, dependency pin | MatchRule và regex 6 số |
| PIN-CTA | ActionButton block | Xác nhận PIN | POST theo mode |


## 4. Style và kích thước

Panel narrow và CodeField theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]]. PIN/confirm là ô input liên tục letter-spacing12, font20, width tối đa340. Cả nhóm form dọc.

## 5. Trạng thái và tương tác

Form mode setup gửi request theo source, thành công pinConfigured/refreshCustomerData/navigate /customer replace. Action busy/cooldown khóa CTA. Form reset trong finally. Lỗi theo ErrorPanel.

## 6. Responsive

Dùng customer layout, panel narrow mobile padding24×20, CodeField max-width100%, form một cột.

## 7. Accessibility và nội dung

CodeField label/extra giải thích paste/leading zero, PIN là secret password input. Confirm field có dependency validation. Form scrollToFirstError, cần QA keyboard/error focus và content của status.

## 8. Cập nhật khi triển khai

Phạm vi/API của PIN được chốt qua GAP-01 và liên kết vào task triển khai phù hợp. Khi thay đổi riêng mode setup cập nhật file này, cùng file shared nếu primitive đổi.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |
