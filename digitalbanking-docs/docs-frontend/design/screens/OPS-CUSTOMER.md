# OPS-CUSTOMER — Tra cứu và thao tác tài khoản tại quầy

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Screen ID | OPS-CUSTOMER |
| Trạng thái UI | Có source UI, có phần chờ cập nhật |
| Route trong code | /staff/customers |
| Đặc tả | [[digitalbanking-docs/docs-frontend/features/FE-06-operations-and-fraud.md]] |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn UI và phạm vi

[Source frontend/src/features/staff/OperatorPages.tsx dòng 45–452](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/features/staff/OperatorPages.tsx#L45-L452)

[Source frontend/src/styles.css dòng 386–406](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L386-L406)

Source OperatorPage đã có lookup phone/email và dialog seed/block/unblock. Route target /staff/customers/:customerId và phạm vi thao tác được chốt tại GAP-01/03/07, cập nhật phần nội dung khi code thay đổi.

## 2. Bố cục và thứ tự vùng

Heading với CTA Tạo khách tại quầy → success/unknown alert → Panel Thông tin tra cứu/Form ngang → khối result/loading/error/empty → Modal thao tác account.

## 3. Component và cấu hình

| ID | Component | Cấu hình / dữ liệu | Hành động |
|---|---|---|---|
| OPS-CREATE | Heading action Button primary | Tạo khách tại quầy | /staff/customers/new |
| OPS-SEARCH-MODE | Select | Tìm theo, Số điện thoại/Email | Đổi mode clear lookup/form |
| OPS-IDENTIFIER | Input | Label/type theo mode, placeholder Nhập thông tin chính xác, phoneRules/emailRules | OnChange clear lookup |
| OPS-SEARCH | Button primary | Tra cứu hoặc Chờ Ns, loading query fetching, disabled wait | Set lookup nếu cooldown hết |
| OPS-RESULT | Panel | Title fullName, sub phone · email, h3 Tài khoản khách hàng | Render accounts |
| OPS-ACCOUNT | .operator-account | Masked number, Status, số dư mô phỏng/Money | Một card mỗi account |
| OPS-SEED | Button primary | Nạp số dư mô phỏng, disabled non-ACTIVE hoặc có pending | Mở Modal seed |
| OPS-BLOCK | Button danger nếu ACTIVE | Khóa hoặc Mở khóa theo status, disabled CLOSED/pending | Mở Modal reason |
| OPS-MODAL | Modal | Title theo seed/block/unblock, maskClosable=false, footer=null, destroyOnHidden | onCancel đóng theo guard |
| OPS-SEED-FORM | Form vertical | Amount nguyên1–100.000.000/max9, reference required/max100, Note | Kiểm tra và xác nhận tạo intent |
| OPS-SEED-REVIEW | Descriptions | Tài khoản masked, Money amount, reference | Hủy hoặc Xác nhận nạp |
| OPS-SEED-UNKNOWN | Warning + ActionButton | Cùng yêu cầu/nội dung, Đối soát cùng yêu cầu | Retry cùng key |
| OPS-REASON | TextArea | Lý do bắt buộc, required/whitespace/max500 | Submit block/unblock |
| OPS-REASON-CTA | Button + ActionButton | Hủy / Xác nhận khóa hoặc mở khóa | Server xác nhận rồi refetch |

## 4. Style và kích thước

Search flex wrap gap12/align end, item flex1/min180/margin-bottom0. Account card border #E1E7EF/radius10/nền #FBFCFE/padding23/margin-top22, balance27px. Modal width dùng Ant default, global max-width calc(100vw - 32px). Review Descriptions và form-actions theo [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]].

## 5. Trạng thái và tương tác

Ban đầu empty hướng dẫn nhập lookup, CUSTOMER_NOT_FOUND có empty message riêng. Query pending Loading, lỗi khác ErrorPanel/retry khi hết wait. Success alert closable. Pending seed unknown giữ warning và modal, closable/keyboard=false khi busy hoặc unknown, nút Hủy ẩn khi seed unknown. Seed thành công hiển thị balanceAfter và refetch; block/unblock thành công hiện success và refetch.

## 6. Responsive

Search fields full-width ở tối đa767, account padding18 và amount vẫn27px do selector cụ thể. Staff sidebar rút76 ở640–1023, drawer dưới640. Modal giới hạn theo viewport, form-actions wrap.

## 7. Accessibility và nội dung

Field labels và reason validation theo Ant Form. Modal title cụ thể theo hành động, đóng bị giới hạn có chủ ý khi request chưa rõ. Kiểm chứng focus trap/return focus, Escape và screen reader message khi unknown.

## 8. Cập nhật khi triển khai

FE-06-01 chuyển lookup theo ID/API mới, GAP-01 chốt seed/block/create, GAP-03 chốt search. Các UI hiện có vẫn mô tả đầy đủ trong file này, task có đổi action cần cập nhật dialog tương ứng.

Khi UI đổi, cập nhật component/selector và nội dung tại file này cùng PR, ghi source commit, trạng thái code và evidence. Quy trình chi tiết tại [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Nhật ký

| Ngày | Thay đổi | Kiểm chứng |
|---|---|---|
| 09/11/2026 | Ghi mô tả UI theo source | Đọc code/CSS, chưa chạy lại browser |

## 10. Phần UI chưa có code

### Customer lookup theo customerId và API mới

## Bổ sung preview ngày 13/11/2026 — được duyệt UI/mock ngày 14/11/2026

Source tham chiếu đã được khôi phục tại digitalbanking-frontend. Màn này có thể mở qua thanh Preview UI; APP-BOOTSTRAP dùng trạng thái Khôi phục phiên. Khung preview tại src/mocks/PreviewShell.tsx, adapter tại src/mocks/legacy-preview.ts. Đây là fixture chỉ xem được người phụ trách FE cho phép ngày 13/11/2026; thao tác thiếu contract bị khóa, không thay đặc tả nghiệp vụ đích. Các trạng thái sâu của form register/recovery/PIN chưa được mô phỏng. Task FE-FND-02-MOCK được người phụ trách FE duyệt UI/mock trong chat ngày 14/11/2026; nhiệm vụ đồng bộ contract và tích hợp vẫn mở; bằng chứng và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].
