# FE-02-01 — Kết quả bước độc lập

18/11/2026, nền `frontend@b9ba604` sau push (3fceda9 foundation, 60a77ee audit, b9ba604 auth); thay đổi FE-02-01 chưa commit. User yêu cầu push task trước rồi bắt đầu task tiếp theo. Đã đọc FE-02/FE-RUN/tracker, design CUST-HOME/ACCOUNT/PROFILE, 07 §8.2, security và OpenAPI v1/v2. Contract v2 2.0.0 là FE/mock; không có BE environment.

## Kết quả

| Đầu ra | Actual / giới hạn |
|---|---|
| Mock customer self | GET `/api/v2/customers/me`, `/accounts`, `/accounts/{accountId}` dùng fixture synthetic, require fixture session. 3002 fixture khác chủ→403; ID sai→400, ID vắng→404. Filter status cho giá trị enum. Mock không kiểm chứng quyền hay audit thật. |
| Component trình bày | CustomerProfilePanel, CustomerAccountCard, CustomerAccountDetailPanel tại `src/features/accounts/v2/CustomerPanels.tsx`. Tái dùng Panel/Descriptions và classes cũ; mask theo response, ID string, Money `formatMoney`/BigInt, fallback status lạ, null contact. Không suy ra PIN hoặc nối transfer/history. |
| Wiring/route | Chưa nối UI, client, query/cache hoặc navigation. Đang chờ câu trả lời user cho câu hỏi trong run-01; không suy ra default option là chốt. |
| Kiểm tra | 2 files/7 tests tập trung đạt. Full gates: lint/typecheck/api:check/format:check/test/build;19 files/158 tests, build đạt; chạy lại lint/typecheck/format sau thay đổi enum đạt. Artifacts `test-report/full-checks.log`, `final-static-checks.log`; thời gian/chi tiết ở run-01. |
| Tài liệu | CUST-HOME/ACCOUNT/PROFILE ghi component độc lập/chưa mounted; feature FE-02, local guide, tracker, run-01 cập nhật. |

## Còn mở

Cách mở preview customer phụ thuộc quyết định user vì chốt trước là màn phiên chung sau login chưa nối nghiệp vụ. Khi có câu trả lời, tiếp tục wiring theo lựa chọn đã chốt, khởi tạo/cancel queries theo session, kiểm chứng loading/empty/error/403/404, responsive/keyboard và đưa UI để review. Không dùng fixture để chứng minh ownership/audit, không suy đoán mapping staff/GAP-07, không đóng FE-01-01/FE-FND-03/GAP-05 hay nghiệm thu tích hợp v2. Đăng ký/recovery/PIN vẫn trong đợt tích hợp đầu, chờ BE/BA bổ sung contract (GAP-01). Không push/merge task mới.

## Kết quả run-02 sau phản hồi người dùng

User trả lời trong chat18/11: “Preview customer riêng; giữ màn phiên chung sau login (đề xuất)”. Đã nối viewer tại `/customer?customerPreview=v2`, hồ sơ và detail routes có cùng query. Auth sau login giữ màn phiên chung; fixture customer được seed riêng cho viewer dev/mock. Không tự suy ra role staff hoặc API thật đã sẵn sàng.

| Hạng mục | Kết quả |
|---|---|
| UI / data | `CustomerPreview.tsx`, `customer-api.ts`, CustomerPanels; reuse layout/Panel legacy, metadata masked, Money/ID string, parse/project known fields, unknown status fallback. Query key gồm fixture user/scenario/ID, AbortSignal, retry thủ công. |
| Các trạng thái | Browser đã thấy account3001, profile, detail,403/404, danh sách rỗng và lỗi dịch vụ. Toolbar fixture có thêm tải chậm. Không thêm PIN/transfer/history. |
| Responsive | Browser1440/768/360, không tràn ngang theo phép đo; mobile/tablet có nút chi tiết sau sửa breakpoint. Ảnh `screenshots/customer-desktop.jpg`, `customer-tablet.jpg`, `customer-mobile.jpg`. |
| URL/reload | Direct detail và reload được thấy hoạt động khi thử lại; lần đầu sau hot-reload trở về tổng quan, nguyên nhân chưa xác định, cần kiểm chứng clean browser. |
| Tests | 4 files/15 tests tập trung đạt; full6 gates và tổng test ghi ở kết quả cuối phía dưới. |

User chưa duyệt UI/mock mới. Backend ownership/audit/PII/security không được chứng minh từ mock; full keyboard/screen reader/zoom, multi-user/auth integration, FE-FND-03 và API thật vẫn mở. Task con FE-02-01-MOCK được trình review UI; task cha chưa Hoàn thành. Không commit/push/merge task này.

## Kết quả cuối run-02

**6/6 gates đạt, 21 files/166 tests đạt** (15 tests FE-02-01), build6.47s; full log `test-report/run-02-full-checks.log`, test32.14s, hoàn tất16:11:46 ngày18/11 Asia/Bangkok (`2026-11-18T09:11:46.853Z`). Test tập trung4 files/15 tests đã đạt trước lượt cuối; git diff --check và manifest ghi riêng sau thay đổi docs. Screenshot browser là fixture tổng hợp, không secret. Không có CI/API backend thật.

Xem tại `http://127.0.0.1:5174/customer?customerPreview=v2`. “Điều khiển bản xem thử” ở góc dưới cho chọn tình huống; các phần còn mở và quan sát reload ban đầu được ghi trong run-02. Mốc UI/mock chờ người dùng review, không tự chuyển task cha Hoàn thành hoặc tích hợp/merge.


## Run-03 — điều hướng customer sau login

Theo chốt chat 18/11, auth mock CUSTOMER vào dashboard, card tổng quan ẩn trạng thái; staff vẫn màn phiên chung. Preview riêng được giữ. Browser smoke login thành công và ảnh dashboard-login-run-03.jpg; 6 gate đạt, 21 files/167 tests. Chưa review UI, chưa kiểm browser đầy đủ hay tích hợp BE; HEAD b9ba604, working tree dirty. Chi tiết [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-03.md]].


## Run-04 — bố cục trang chủ theo ảnh tham khảo

Trang chủ mock customer dùng topbar lời chào và sidebar trắng 3 mục theo các màn đang nối. Số dư/định danh từ v2 mock, không thêm nghiệp vụ ngoài nguồn. Browser ảnh dashboard-layout-run-04.jpg; 6 gate đạt, 21 files/167 tests. Chờ review UI; FE-02-01/API thật mở. [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-04.md]].
