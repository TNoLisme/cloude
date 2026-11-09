# UI và design system FE

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-UI |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, QA |

## 1. Căn cứ

UI tiếng Việt, VND và timezone cấu hình theo NFR-028/029 trong SRS. FE-FND-03 chuẩn hóa tokens, form và responsive layouts theo thư viện được quyết định tại ADR-001. Token/component dưới đây là giao ước đề xuất cho mọi feature. Design chi tiết theo source tại [[digitalbanking-docs/docs-frontend/design/shared/01-foundations-and-components.md]] và [[digitalbanking-docs/docs-frontend/design/shared/02-layouts-and-navigation.md]].

## FE-UI-01 — Token và component

Một nơi khai báo semantic tokens cho primary, background, surface, border, text, success/warning/error, spacing, type scale, radius và focus ring. Token map sang UI library được chọn. Component dùng chung bao gồm AppShell, PageHeader, MoneyText, MaskedIdentity, StatusBadge, ErrorPanel, EmptyState, LoadingState, FormField, ConfirmDialog, DataTable và ChallengeDialog.

Feature dùng semantic token thay vì tự định nghĩa màu/spacing tương đương. StatusBadge có nhãn chữ và icon khi phù hợp. Giá trị theme/CSS đã có được ghi theo source trong shared design. FE-FND-03 chốt giá trị áp dụng khi triển khai và bổ sung wireframe có phiên bản khi cần. Danh mục từng màn tại [[digitalbanking-docs/docs-frontend/design/README.md]].

## FE-UI-02 — Form và thông báo

- Label hiển thị thường trực, required/optional rõ ràng, trợ giúp theo field.
- Dữ liệu nhập kiểm tra theo schema contract. Password giữ nguyên ký tự, mã OTP/passcode là string để giữ số 0 đầu.
- Field error gắn aria-describedby, focus lỗi đầu khi submit thất bại. Lỗi server field theo API-ERR-01.
- Submit có pending state và chặn gửi lặp. Lỗi mạng giữ phần form an toàn, secret theo FE-SEC-03.
- Success message mô tả kết quả server. Mã correlation có thể copy để hỗ trợ.
- Dialog có tiêu đề, mô tả hậu quả, focus trap, Escape và trả focus về nút mở khi đóng. Giao dịch không xác định kết quả được giữ trong trang trạng thái để tra soát.

## FE-UI-03 — Ma trận trạng thái

| Trạng thái | Cách hiển thị |
|---|---|
| Loading lần đầu | Skeleton/spinner trong khu vực dữ liệu, aria-busy |
| Refetch | Giữ dữ liệu đã có, đánh dấu đang cập nhật và asOf |
| Empty | Nêu dữ liệu chưa có, action hợp lệ khi được phép |
| Filter empty | Nêu không có kết quả và action xóa bộ lọc |
| Error | ErrorPanel với thông báo an toàn, correlation, retry theo API-RETRY-01 |
| Forbidden | Màn 403 và đường quay lại vùng được phép |
| Session expired | FE-AUTH-01, thông báo hết phiên và login |
| Partial error | Khu vực lỗi độc lập, các khu vực còn lại vẫn hiển thị hợp lệ |
| Unknown transfer | Nêu đang xác minh, giữ intent/reference và action tra cứu theo FE-03 |

## FE-UI-04 — Responsive và accessibility

Kiểm chứng tối thiểu viewport 360, 768 và 1440 px theo đề xuất FE, xác nhận browser matrix tại FE-REL-01. Trang co giãn theo nội dung. Bảng có vùng cuộn riêng và tên cột rõ, mobile có thể dùng cards giữ đủ trạng thái/số tiền/reference.

Kiểm tra keyboard-only, thứ tự focus, tên accessible cho icon button, heading hierarchy, label/error form, contrast, zoom 200%, focus visible và thông báo async qua live region phù hợp. Countdown đọc thời điểm hết hạn và thông báo các mốc cần thiết. Ghi lỗi và evidence tại FE-REL-02.

## FE-UI-05 — Tiền, thời gian và dữ liệu nhạy cảm

Dùng formatter trong shared/lib theo API-DATA-01. API UTC, UI timezone cấu hình mặc định theo môi trường dự án. Ngày lọc dùng ranh giới UTC rõ ràng. Hiển thị tiền cùng đơn vị, giữ giá trị decimal chính xác khi parse.

PII hiển thị từ field masked do server trả. Cảnh báo fraud mô tả dấu hiệu cần rà soát và trạng thái review. Label thuật ngữ chung tập trung trong shared, feature chỉ bổ sung copy riêng đã review.

## 2. Nguồn liên quan

- [[digitalbanking-docs/docs-frontend/standards/03-api-and-data.md#API-DATA-01 — Kiểu dữ liệu và định dạng]].
- [[digitalbanking-docs/docs-frontend/standards/04-auth-and-security.md#FE-SEC-03 — Dữ liệu nhạy cảm và browser storage]].
- [[digitalbanking-docs/docs-frontend/standards/05-testing-and-acceptance.md]].

## FE-UI-06 — Đồng bộ design với code

Mỗi Screen ID có một file design riêng trong design/screens. Phần đã có UI mô tả component/field/label/layout/CSS/state/responsive theo commit cụ thể. Phần chưa có UI giữ các mục trống và trạng thái chờ cập nhật. Mọi thay đổi UI cập nhật file màn hình cùng PR theo [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]]. Component dùng chung cập nhật shared design và các override của màn liên quan.
