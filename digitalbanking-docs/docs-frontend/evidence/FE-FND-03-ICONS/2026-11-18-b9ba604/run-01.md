# Phiếu FE-FND-03-ICONS / run-01

## 1. Định danh và đầu vào

18/11/2026 21:06 +07; FE HEAD b9ba604, working tree dirty từ FE-02-01 chưa commit. BE không áp dụng. Mức source/unit. Đọc FE README, tracker FE-FND-03/FE-02-01, roadmap, lifecycle/template, FE-UI-01/04, UI-SHARED-01, SRS FR-008–010/AC-005/023, OpenAPI getMyProfile/listAccounts/getAccount, source customer hiện tại.

## 2. Kiểm tra sẵn sàng

Yêu cầu chat 18/11 là xây hệ icon ba nhóm, không thay business logic/API/route/auth hoặc thiết kế lại page. Màn customer mock hiện chờ review còn legacy preview đã được duyệt; câu “approved screens” chưa xác định rõ phạm vi. Người phụ trách FE trả lời trong chat 18/11: “Customer mock hiện tại (đề xuất)”. Đã đủ căn cứ thay icon trên phạm vi đó; không mở rộng sang legacy/login/staff.

## 3. Thay đổi và kịch bản

Tạo 4 SVG navigation cùng họ outline, 2 SVG feature flat duotone, 1 illustration vector; catalog và Icon/FeatureIcon/Illustration với size variants, aria ẩn cho icon trang trí. Sau chốt, thay 3 icon sidebar customer bằng Icon, thêm FeatureIcon account vào card và Illustration emptyAccounts vào trạng thái danh sách rỗng. Giữ text, asset cũ, handler/API/route.

## 4. Đối chiếu hoàn thành

`node node_modules/prettier/bin/prettier.cjs --write src/components/icons`, `node node_modules/typescript/bin/tsc -b --pretty false`, `node node_modules/eslint/bin/eslint.js src/components/icons`: exit 0 lúc 21:06 +07. Node kiểm 7 SVG đều có root/viewBox/end tag: pass. Browser IAB trước: [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/dashboard-layout-run-04.jpg]]; sau: `customer-icons-after.jpg`. Quan sát icon menu/account nét rõ ở màn desktop, title/amount/status và route giữ nguyên. Browser không kiểm mobile/keyboard trong run này. Visual audit screenshot trước/sau chờ chốt màn và gắn vào run kế tiếp.

## 5. Tài liệu và review

Design `design/shared/03-iconography.md`, UI shared standard, CUST-HOME, FE-02, tracker. UI review chưa có; không commit/push/merge.

## 6. Kết quả lần chạy

`node .tmp/fe-fnd-01-authui-20261117/icon-check-01.cjs`: lint, typecheck, api:check, format:check, test (21 files/167 tests), build đều exit 0; log `icon-full-checks.log`, kết thúc 18/11/2026 21:15 +07. `git diff --check` exit 0 (line ending warnings). Chờ review UI/mock; FE-FND-03 cha còn mở. HEAD b9ba604, working tree dirty; không commit/push/merge.
