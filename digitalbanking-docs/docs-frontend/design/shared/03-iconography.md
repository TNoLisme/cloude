# FE iconography — bản thiết kế 18/11/2026

Trạng thái: Draft, chờ review UI/mock sau áp dụng trên customer mock. Căn cứ: yêu cầu người phụ trách FE trong chat 18/11/2026; FE-UI-01/04 và UI-SHARED-01. Không tạo nghiệp vụ, route hoặc API.

## Danh mục

| Nhóm | Nguồn | Quy cách | Dùng cho |
|---|---|---|---|
| Navigation | `src/assets/icons/navigation/*.svg` | Một họ outline nét 1.8, hình 24×24, đơn sắc qua `currentColor` | Sidebar, menu, chevron |
| Feature | `src/assets/icons/feature/*.svg` | Một họ flat duotone, xanh `#2868B2` + navy `#173B5E`, nền `#EAF2FC` | Tài khoản, hồ sơ |
| Illustration | `src/assets/icons/illustration/*.svg` | Vector nét/phẳng cùng bảng màu, khung 240×160 | Trạng thái danh sách tài khoản rỗng |

Không dùng emoji, gradient, shadow trong SVG. `catalog.ts` là nơi duy nhất import asset và map tên; không copy đường dẫn vào feature. `Icon` dùng mask và màu `currentColor`; trạng thái inactive/hover/active/disabled do control cha quyết định bằng CSS semantic colors. `FeatureIcon`/`Illustration` là ảnh trang trí (`alt=""`, `aria-hidden`) và luôn đi cùng text thật. Icon button độc lập cần tên accessible ở button. Size nav sm/md/lg = 16/20/24 px; feature = 32/48/64 px. Illustration co tối đa 240 px.

## Phạm vi triển khai

Đã tạo thư viện icon/asset. Phạm vi đã được chốt trong chat: customer mock hiện tại. Navigation AntD tương đương được thay bằng SVG outline cùng ý nghĩa; card account thêm SVG feature, trạng thái danh sách rỗng thêm illustration cùng text. Không thay màn legacy hay asset gốc.

## Kiểm tra

Typecheck và lint component; xem phiếu [[digitalbanking-docs/docs-frontend/evidence/FE-FND-03-ICONS/2026-11-18-b9ba604/run-01.md]]. Visual audit trước/sau và kiểm tra UI mock tại run-01.


## Chốt phạm vi áp dụng ngày 18/11/2026

Người phụ trách FE trả lời câu hỏi trong chat: “Customer mock hiện tại (đề xuất)”. Đã thay các icon menu của customer mock, thêm feature icon vào card tài khoản và illustration vào danh sách rỗng. Ảnh trước `FE-02-01/2026-11-18-b9ba604/dashboard-layout-run-04.jpg`, sau `FE-FND-03-ICONS/2026-11-18-b9ba604/customer-icons-after.jpg`. Các màn khác chưa thay.
