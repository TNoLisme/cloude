# Design UI theo màn hình

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Mã | FE-DESIGN |
| Owner / reviewer | Frontend / người phụ trách FE, QA |
| Baseline source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Trạng thái phát hành | Đã chốt trong bộ docs FE v1.2 ngày 09/11/2026 |

## 1. Cách sử dụng

Mỗi màn hình có một file trong screens, được giữ cùng Screen ID khi đổi route hoặc component. Component dùng chung có hai file trong shared. Tài liệu ghi đúng source/commit dùng để mô tả UI và trạng thái của phần đã có code.

Source UI để mô tả nằm trên nhánh frontend tại commit trong metadata, được sử dụng cho công việc FE-FND-01/03. Các đường dẫn main sau refactor và API đích theo standards/features. Mô tả source là kết quả đọc component/CSS, kết quả render và browser QA được ghi riêng trong evidence.

| Trạng thái UI | Ý nghĩa |
|---|---|
| Có source UI | Nội dung thiết kế được ghi theo component/CSS có trong source tham chiếu |
| Có source UI, có phần chờ cập nhật | Các vùng đã có code được mô tả, phần UI bổ sung chưa có code giữ heading trống trong cùng file |
| Chưa có UI màn đích | File chỉ có metadata/liên kết và khung mục trống để điền khi thiết kế hoặc triển khai |

## 2. Component và layout dùng chung

- [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]] ghi theme, typography, spacing, form/control/state, table/filter/receipt.
- [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]] ghi auth card, shell, sidebar, topbar, bottom navigation và drawer.
- [[digitalbanking-docs/docs-frontend/standards/02-ui-and-design-system.md]] giữ các quy tắc dùng chung FE-UI.
- [[digitalbanking-docs/docs-frontend/templates/screen-design-template.md]] dùng khi bổ sung một screen.

Kích thước/màu/props được ghi theo nơi khai báo trong code. Giá trị do thư viện hoặc browser quyết định được chỉ rõ nguồn mặc định và kiểm tra lại khi render, có kết quả mới thì cập nhật cùng file.

## 3. Danh mục màn hình

| Screen ID | File mô tả | Trạng thái UI | Đặc tả |
|---|---|---|---|
| AUTH-LOGIN | [[digitalbanking-docs/docs-frontend/design/screens/AUTH-LOGIN.md]] | Có source UI, có phần chờ cập nhật | FE-01 |
| AUTH-REGISTER | [[digitalbanking-docs/docs-frontend/design/screens/AUTH-REGISTER.md]] | Có source UI | FE-01 |
| AUTH-RECOVERY | [[digitalbanking-docs/docs-frontend/design/screens/AUTH-RECOVERY.md]] | Có source UI | FE-01 |
| AUTH-WORKSPACE | [[digitalbanking-docs/docs-frontend/design/screens/AUTH-WORKSPACE.md]] | Có source UI | FE-01 |
| APP-BOOTSTRAP | [[digitalbanking-docs/docs-frontend/design/screens/APP-BOOTSTRAP.md]] | Có source UI | FE-01 |
| ERROR-FORBIDDEN | [[digitalbanking-docs/docs-frontend/design/screens/ERROR-FORBIDDEN.md]] | Có source UI | FE-01 |
| ERROR-NOT-FOUND | [[digitalbanking-docs/docs-frontend/design/screens/ERROR-NOT-FOUND.md]] | Có source UI | FE-01 |
| CUST-HOME | [[digitalbanking-docs/docs-frontend/design/screens/CUST-HOME.md]] | Có source UI | FE-02, FE-04 |
| CUST-ACCOUNT | [[digitalbanking-docs/docs-frontend/design/screens/CUST-ACCOUNT.md]] | Có source UI | FE-02 |
| CUST-PROFILE | [[digitalbanking-docs/docs-frontend/design/screens/CUST-PROFILE.md]] | Có source UI | FE-02 |
| CUST-PIN-SETUP | [[digitalbanking-docs/docs-frontend/design/screens/CUST-PIN-SETUP.md]] | Có source UI | FE-02 |
| CUST-PIN-CHANGE | [[digitalbanking-docs/docs-frontend/design/screens/CUST-PIN-CHANGE.md]] | Có source UI | FE-02 |
| CUST-PIN-RESET | [[digitalbanking-docs/docs-frontend/design/screens/CUST-PIN-RESET.md]] | Có source UI | FE-02 |
| CUST-TRANSFER | [[digitalbanking-docs/docs-frontend/design/screens/CUST-TRANSFER.md]] | Có source UI, có phần chờ cập nhật | FE-03 |
| CUST-HISTORY | [[digitalbanking-docs/docs-frontend/design/screens/CUST-HISTORY.md]] | Có source UI | FE-04 |
| CUST-TX | [[digitalbanking-docs/docs-frontend/design/screens/CUST-TX.md]] | Có source UI | FE-04, FE-03 |
| OPS-CUSTOMER | [[digitalbanking-docs/docs-frontend/design/screens/OPS-CUSTOMER.md]] | Có source UI, có phần chờ cập nhật | FE-06 |
| OPS-CREATE-CUSTOMER | [[digitalbanking-docs/docs-frontend/design/screens/OPS-CREATE-CUSTOMER.md]] | Có source UI | FE-06 |
| STAFF-AUDIT | [[digitalbanking-docs/docs-frontend/design/screens/STAFF-AUDIT.md]] | Có source UI, có phần chờ cập nhật | FE-07 |
| OPS-FRAUD | [[digitalbanking-docs/docs-frontend/design/screens/OPS-FRAUD.md]] | Có source UI, có phần chờ cập nhật | FE-06 |
| AUTH-CHALLENGE | [[digitalbanking-docs/docs-frontend/design/screens/AUTH-CHALLENGE.md]] | Chưa có UI màn đích | FE-01 |
| CUST-RESULT | [[digitalbanking-docs/docs-frontend/design/screens/CUST-RESULT.md]] | Chưa có UI màn đích | FE-03 |
| CUST-LIMITS | [[digitalbanking-docs/docs-frontend/design/screens/CUST-LIMITS.md]] | Chưa có UI màn đích | FE-05 |
| STAFF-TX | [[digitalbanking-docs/docs-frontend/design/screens/STAFF-TX.md]] | Chưa có UI màn đích | FE-04 |
| STAFF-LIMIT | [[digitalbanking-docs/docs-frontend/design/screens/STAFF-LIMIT.md]] | Chưa có UI màn đích | FE-05 |
| STAFF-APPROVAL-LIST | [[digitalbanking-docs/docs-frontend/design/screens/STAFF-APPROVAL-LIST.md]] | Chưa có UI màn đích | FE-05, FE-08 |
| STAFF-APPROVAL-DETAIL | [[digitalbanking-docs/docs-frontend/design/screens/STAFF-APPROVAL-DETAIL.md]] | Chưa có UI màn đích | FE-05, FE-08 |
| ADMIN-ROLES | [[digitalbanking-docs/docs-frontend/design/screens/ADMIN-ROLES.md]] | Chưa có UI màn đích | FE-08 |

CUST-TRANSFER có receipt như một state trong wizard. CUST-RESULT là file chờ màn độc lập theo reference của kiến trúc đích. AUTH-CHALLENGE là file chờ controller/màn challenge mới, các OTP form đã có được mô tả trong màn tương ứng. STAFF-APPROVAL-LIST và STAFF-APPROVAL-DETAIL cùng thuộc screen group STAFF-APPROVAL trong route map.

Các màn register/recovery/PIN/create customer có source, việc đưa vào phạm vi tích hợp sau refactor được quyết định ở GAP-01. OPS-CUSTOMER hiện mô tả lookup /staff/customers, route param của đích và API được cập nhật qua FE-06-01. Chức năng riêng của từng màn được truy vết từ feature và tracker.

## UI-DOC-01 — Cập nhật theo màn hình

1. Chọn Screen ID trong bảng và mở file đó trước khi sửa component.
2. Nếu file đang trống, điền các mục bố cục, component, style, trạng thái, responsive và accessibility theo phần được thiết kế/triển khai. Ghi mức Thiết kế đề xuất nếu UI chưa có code.
3. Khi có code, ghi component function/path, permalink commit và route thực tế. Chuyển trạng thái UI theo phần đã thực hiện, giữ trống các vùng chưa có nội dung.
4. Giữ ID component cục bộ như LOGIN-PHONE, TRANSFER-AMOUNT để truy vết thay đổi trong màn. Component dùng lại tham chiếu shared thay vì chép cấu hình.
5. Đổi shared theme/layout cần cập nhật shared docs và các màn có override. Đổi hành vi nghiệp vụ/API cần cập nhật feature/standards/tracker tương ứng.
6. Thay đổi component/màu/kích thước/field/interaction/responsive cần cập nhật mục liên quan và nhật ký của file màn hình trong cùng PR.
7. Ghi evidence screenshot/test/browser theo commit và viewport. Chỉ ghi đã kiểm chứng khi có kết quả.
8. Tài liệu được review cùng PR/UI, metadata phê duyệt ghi rõ phiên bản được chốt. Tracker giữ trạng thái công việc, design docs giữ nội dung màn hình.
9. Screen mới thêm vào registry, feature và route map. Screen bị thay thế ghi link thay thế và cập nhật mọi tham chiếu.

## 4. Các mục của một file màn hình

| Mục | Nội dung |
|---|---|
| 1 | Nguồn UI/commit, route và phạm vi đã có code |
| 2 | Bố cục, vị trí tương đối và thứ tự các vùng |
| 3 | ID component, loại, props, field/label/data, action |
| 4 | Style/kích thước và selector/shared rule sở hữu |
| 5 | Loading/empty/error/disabled/success và tương tác theo state |
| 6 | Breakpoint, desktop/tablet/mobile và override |
| 7 | Accessibility/copy có trong code và nội dung cần kiểm chứng |
| 8 | Task/GAP triển khai, quy tắc cập nhật khi UI thay đổi |
| 9 | Nhật ký PR/commit/evidence |

Các vùng UI chưa có code trong màn đã triển khai một phần được đặt ở mục 10, giữ heading trống cho đến khi có thiết kế/code.

## 5. Liên kết quản lý

[[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] giữ FE-DOC-02 cho việc bổ sung bộ design và FE-FND-03 cho thực hiện UI. [[digitalbanking-docs/docs-frontend/standards/01-architecture.md]] giữ route policy và state ownership, [[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md]] giữ tiêu chí kiểm chứng.

## 6. Nhật ký

| Ngày | Nội dung |
|---|---|
| 09/11/2026 | Tạo registry, 2 shared design, 20 file màn có source và 8 file chờ UI |
