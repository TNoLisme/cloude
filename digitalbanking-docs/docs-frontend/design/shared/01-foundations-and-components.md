# UI foundation và component dùng chung

| Thuộc tính | Giá trị |
|---|---|
| Phiên bản / ngày | 1.0 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Mã | UI-SHARED-01 |
| Mức mô tả | Đọc code và CSS, chưa kiểm chứng render lại |
| Source UI | 9e09a71fd2c79c0486c4ee958e02fa2ef884314e |
| Owner | Frontend |

## 1. Nguồn và phạm vi

Mô tả cấu hình hiện diện trong source tham chiếu dùng cho task FE-FND-03. Giá trị dưới đây được đọc từ theme/CSS/component, các thay đổi đích theo standards và tracker phải cập nhật lại tài liệu cùng PR.

- [Source frontend/src/main.tsx dòng 9–42](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/main.tsx#L9-L42).
- [Source frontend/src/styles.css dòng 1–615](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/styles.css#L1-L615).
- [Source frontend/src/components/shared.tsx dòng 22–407](https://github.com/TNoLisme/cloude/blob/9e09a71fd2c79c0486c4ee958e02fa2ef884314e/frontend/src/components/shared.tsx#L22-L407).
- Quy tắc UI đích tại [[digitalbanking-docs/docs-frontend/standards/02-ui-and-design-system.md]].

## 2. Theme được khai báo

| Token / cấu hình | Giá trị trong source |
|---|---|
| Font family | Inter, Segoe UI, Arial, sans-serif. Font thực tế phụ thuộc font có trên máy |
| Font mặc định / line-height CSS | 14 px / 1.5 |
| Primary | #2868B2 |
| Heading navy | #173B5E |
| Text / secondary | #223449 / #65768A |
| Layout / surface | #F5F7FA / white |
| Border | #E1E7EF |
| Success / warning / error | #237A52 / #9C5B0A / #B53A3A |
| Border radius token | 8 px |
| ControlHeight token | 44 px |
| Card borderRadiusLG | 12 px |
| Button fontWeight | 600 |
| Table header background / text | #F7F9FC / #65768A |
| Table cellPaddingBlock | 17 px |
| Locale Ant Design | vi_VN |
| Focus visible global | Outline 3 px #8CB4E4, offset 3 px |

Global .ant-btn đặt height auto, min-height 40 px và white-space normal. Vì vậy controlHeight 44 là theme token, chiều cao render cuối còn phụ thuộc variant/nội dung/CSS. Hover/focus/disabled của Input/Button/Table dùng theme Ant Design tại phiên bản source, các giá trị chưa override cần kiểm tra computed style khi thực thi UI.

## 3. Typography và spacing

| Selector | Desktop | Khi viewport tối đa 767 px |
|---|---|---|
| h1 | 28 px, line-height 1.3, letter-spacing -0.7 px | 23 px |
| h2 | 20 px, line-height 1.35 | 19 px |
| h3 | 16 px | Giữ rule mặc định |
| .small / .muted | 12 px / màu secondary | Giữ rule mặc định |
| .page-heading | Flex ngang, gap 20, margin-bottom 26 | Flex cột, gap 14 |
| .page-heading p | 13 px, margin-top 7 | Giữ rule mặc định |
| .actions | Flex wrap, gap 12 | Override riêng từng màn nếu có |
| .spaced | margin-top 24 !important, display block | Giữ rule mặc định |
| .form-actions | Border-top, padding-top 20, margin-top 24, justify-between, gap 12 | Flex wrap |
| .footnote | 11 px, căn giữa, margin-top 24 | Giữ rule mặc định |

## 4. Danh mục component

| Component / props | Cấu trúc, dữ liệu và style | Trạng thái / tương tác |
|---|---|---|
| Brand(compact) | Mark DB 42×42, radius 11, nền navy/chữ trắng. Tên Digital Banking 18 px và subtitle 11 px | compact đổi subtitle SIMULATOR, sidebar có override riêng |
| Simulation | Safety icon và nhãn Mô phỏng — không sử dụng tiền thật. Nền #F1F5FA, border #E3EBF4, padding 7×10, radius 6, chữ 11 | Nhãn tĩnh |
| Heading(title,sub,action) | .page-heading, h1 tabIndex -1, subtitle tùy chọn, action bên phải | BankingLayout focus h1 khi đổi pathname |
| Panel(title,sub,narrow) | Ant Card, h2/subtitle trong header. Border #E1E7EF, shadow 0 3px 14px #173B5E09 | narrow thêm .form-panel |
| Panel narrow | max-width 644, căn giữa, body padding 28×32 | Mobile body 24×20 |
| Panel header | padding 22×26, min-height 80, title wrap | Mobile padding 20, card body thường 22 |
| Money(value,className) | Tabular-nums, nowrap. Hiện formatter nhận chuỗi số nguyên và BigInt, giá trị khác hiện — | FE-FND-02 cập nhật decimal string theo API-DATA-01 |
| Status(value) | Ant Tag, value được hiển thị nguyên văn | ACTIVE/COMPLETED/SUCCESS xanh+check. AWAITING_OTP/EXPIRED vàng+clock. BLOCKED/CLOSED/FAILED/FAILURE đỏ+close. Khác dùng default |
| ErrorPanel(error,retry) | Ant Alert error, icon, role alert, margin-bottom 24. Catalog tiếng Việt, correlation trong description | Chỉ render khi có error. Retry tùy prop |
| Loading | role status, aria-label Đang tải dữ liệu, Skeleton active 4 dòng | Render theo trạng thái query |
| EmptyState(text) | Ant Empty simple, default Chưa có dữ liệu | Nội dung tùy màn |
| Note(children) | Lock icon và đoạn chữ 12 px, line-height 1.6, gap 7, margin dọc 18 | Trợ giúp tĩnh |
| CodeField(name,label,secret) | Form.Item, required và đúng 6 số. Numeric input, maxLength 6, placeholder ••••••, extra giải thích paste/giữ số 0 đầu | OTP dùng Input autocomplete one-time-code. Secret dùng Input.Password ẩn toggle/autocomplete off |
| Countdown(expiresAt) | Dòng flex justify-between, 12 px, margin-bottom 18. Giá trị phút/giây màu #9C5B0A | Tick mỗi giây, về 0 hiện Mã đã hết hạn |
| ActionButton(action,...) | Ant Button primary, loading=busy, disabled=busy hoặc cooldown | Cooldown thay nhãn bằng Thử lại sau Ns, action lock ngăn gửi trùng |

CodeField có letter-spacing 12 px, font 20 px, font-weight 550, max-width 340 px. Viewport tối đa 767 px cho max-width 100%. Đây là một input liên tục nhận paste, source chưa chia sáu ô OTP.

## 5. Bảng, filter và receipt dùng lại

| Selector | Giao ước trong code |
|---|---|
| .table-region | max-width 100%, min-width 0, overflow auto, overscroll-behavior-x contain |
| .ant-table-cell | Font 12 px, link 13 px/600 |
| .filters | Flex wrap, gap 16, align end, padding-bottom 22, margin-bottom 12, border-bottom |
| .filters .ant-form-item | min-width 172, flex 1 1 172, margin-bottom 0 |
| .filters mobile | Field flex 1 1 100%, min-width 0, select min-width 0 |
| .panel-bottom | Border-top, margin/padding-top 17, justify-end |
| .receipt | Nền #F7F9FC, radius 9, padding 24, margin-bottom 26 |
| .receipt-amount | Căn giữa, dashed border #D5DFEA, padding-bottom 22, margin-bottom 21 |
| .receipt-amount > .money | 30 px/750, navy, margin 5px 0 12px, letter-spacing -0.7 |
| .receipt descriptions | Content căn phải, wrap anywhere |
| Receipt mobile | Padding 20×16, amount 27 px |

## 6. Quy tắc cập nhật

Đổi theme/shared primitive phải cập nhật file này và các màn có override riêng, giữ mã component/screen trong design registry. Source permalink được chuyển sang commit chứa thay đổi mới khi UI được triển khai.

Mỗi lần cập nhật ghi PR, selector/component thay đổi, màn bị ảnh hưởng và bằng chứng viewport/interaction. QA theo FE-UI và FE-TEST, trạng thái actual accessibility/render ghi ở evidence.

## 7. Nhật ký

| Ngày | Thay đổi | Source / kiểm chứng |
|---|---|---|
| 09/11/2026 | Mô tả theme, CSS và component hiện có | Commit nguồn trong metadata, đọc source |
