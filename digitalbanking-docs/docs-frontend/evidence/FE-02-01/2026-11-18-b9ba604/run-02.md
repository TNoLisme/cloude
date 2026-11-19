# Phiếu FE-02-01 / run-02 — preview customer riêng

## 1. Định danh và đầu vào

18/11/2026 Asia/Bangkok; frontend@b9ba604, working tree dirty gồm mock/component/docs của run-01. Đọc FE README/tracker, FE-02, standards UI/API/auth, design CUST-HOME/ACCOUNT/PROFILE, 07 §8.2, YAML v1/v2 và security như run-01; không API thật. FET-03/14 cần test mock/browser riêng và backend evidence.

## 2. Kiểm tra sẵn sàng

User trả lời câu hỏi call_37f0bf7a753d4a728cd608c6a99db7b2 trong chat18/11: “Preview customer riêng; giữ màn phiên chung sau login (đề xuất)”. Được phép mở preview customer riêng dùng fixture customer, không đổi điều hướng sau login auth. Quyết định không đóng GAP-05/07, không duyệt UI, không xác nhận quyền staff/API thật. FE-01-01/FE-FND-03 còn mở; phần độc lập customer self theo 07 §8.2 được tiếp tục.

## 3. Thay đổi và kịch bản

Theo chốt user và yêu cầu tiếp tục: đã nối preview tách biệt dev/mock tại `/customer?customerPreview=v2`; profile/detail dùng route theo design cùng query preview. `CustomerPreview.tsx` dựng fixture customer riêng, QueryClient riêng, query key fixture user/scenario/accountId, signal/cancellation, không auto-retry; `customer-api.ts` parse response 200, ID/Money/instant/masked account, loại field không biết, status lạ đi fallback không gán action. Ba endpoint dùng handler MSW ở run-01. Không sửa màn auth v2 sau login, không giả định PIN/history/transfer hay policy staff.

Toolbar thu gọn có fixture 403/404, danh sách rỗng/tải chậm/lỗi; status filter ngoài enum trả network error tại mock để fail-closed vì response sai schema chưa được quy định. Source runtime mock chỉ nạp dev/test; không gọi BE khi thiếu handler.

## 4. Đối chiếu hoàn thành

Kiểm tập trung4 files/15 tests đạt cho schema/mock/component/preview, gồm unknown status, loại unexpected field, Money lỗi, ID/403/404, strict mode và direct detail URL. Full six gates theo pnpm10.34.9 tại `test-report/run-02-full-checks.log`; kết quả cuối và timestamps ghi ở result.

Browser IAB: tổng quan, hồ sơ masked, chi tiết3001, direct URL/reload (lần lặp lại), 403 fixture3002, 404 fixture9999, empty và service error đã quan sát. Viewport1440×900,768×1024,360×800; scrollWidth bằng clientWidth và ảnh tại screenshots. Phát hiện breakpoint legacy ẩn panel thứ hai và nút detail trên mobile/tablet, đã chuyển nút vào card; kiểm lại mobile mở được detail, tablet nhìn thấy nút. Lần đầu reload khi Vite đang hot-reload từng trở về tổng quan; mở direct URL rồi reload lại giữ detail, nguyên nhân lần đầu chưa xác định; còn cần xác minh clean browser. Browser mock không chứng minh server ownership/cookie/security/audit, chưa kiểm đầy đủ keyboard/screen reader/zoom và cross-user cache thật.

## 5. Tài liệu và review

Feature/design/guide/tracker/result đã cập nhật; user chưa duyệt UI. Không tích hợp BE/push/merge task mới. Link bản xem http://127.0.0.1:5174/customer?customerPreview=v2, toolbar “Điều khiển bản xem thử”.

## 6. Kết quả lần chạy

FE-02-01-MOCK chờ user review bản UI/mock; FE-02-01 task cha vẫn Đang làm, FE-FND-03/GAP-05/07 và integration mở. Full6 gates đạt,21 files/166 tests, build6.47s; hoàn tất16:11:46 ngày18/11 Asia/Bangkok. Chi tiết [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/result.md]].
