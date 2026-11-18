# FE-01-01-LOGIN-UI — Bố cục theo ảnh người dùng

18/11/2026 Asia/Bangkok. Nền frontend@6bceb5dc9f83e56530ddb04042e0eedd17ffcdcc; thay đổi trong working tree chưa commit, gồm các task nền tảng/UI trước đó. Phạm vi lần này là trình bày host/login v2 và điều khiển fixture, không API thật. Chưa có duyệt UI sau sửa; không commit/push/merge.

## 1. Phản hồi đã thực hiện

Nguồn: user gửi ảnh codex-clipboard-3d72d827-6ff5-4435-a4b3-b730681b74c2.png và yêu cầu nguyên ý: “thiết kế UI có bố cục như thế này, thay thế logo mb thành logo của dự án hiện tại, không có phần nhập mã kiểm tra, không cần có hotline, hình ảnh bên nửa trái có thể tái sử dụng lại thay mb internet banking thành tên dự án hiện tại”. Đây là yêu cầu sửa UI; không phải duyệt kết quả hoặc thay nghiệp vụ.

| Yêu cầu | Kết quả |
|---|---|
| Bố cục như ảnh | Desktop hai cột50/50, artwork trái, form phải max500px; nền trắng, bỏ card nổi giữa trang |
| Logo/tên dự án | Tái dùng BrandDB/Digital Banking hiện có, h1 Digital Banking và tên bên trái; màu primary theo theme dự án |
| Tái sử dụng hình trái | Copy nguyên ảnh được cấp vào public/images/login-reference.png, CSS chỉ hiển thị nửa trái; giữ tỷ lệ artwork và hòa biên bằng mask CSS |
| Không mã kiểm tra/hotline | Form login chỉ username/password; không CAPTCHA/hotline. OTP của bước LOGIN_MFA vẫn giữ theo contract |
| Xem mobile | <=767px ẩn artwork, form một cột; tablet giảm padding và brand |
| Mock toolbar | Chuyển details thu gọn góc dưới trái; mở “Điều khiển bản xem thử” để dùng scenario/code fixture |

Không thêm selector ngôn ngữ hoặc link footer chưa có đích/contract. Giữ chú thích mô phỏng và ghi chú register/recovery đang chờ contract. Không dùng ảnh làm căn cứ endpoint/quyền/session.

## 2. Code và tài liệu

Source mới `src/features/auth/v2/AuthSplitLayout.tsx` / `.module.css`; sửa host `src/mocks/AuthPreview.tsx`, heading/label/aria-required trong AuthScreens.tsx và thao tác mở details trong test host hiện có. Không thêm test chỉ để kiểm CSS/text; giữ các tests flow/MSW. Không sửa controller/session, DTO, YAML, handlers, API payload/version hoặc package/lockfile trong lần thay layout.

Host áp dụng chung bootstrap/challenge/summary; chỉ login đổi lời chào/heading. Updated AUTH-LOGIN, APP-BOOTSTRAP, AUTH-CHALLENGE, AUTH-WORKSPACE, shared layout, feature FE-01, local guide và tracker; các phần lịch sử giữ nguyên và có bổ sung mô tả mới.

## 3. Kiểm tra thực tế

IAB mở được localhost5174 trong lần này. Screenshot trực tiếp browser (JPEG, không ảnh dựng), có form fixture hoặc dữ liệu tổng hợp; không chứa CSRF/token/mật khẩu hiển thị. Viewport overrides đã reset và tab được giữ để người dùng duyệt.

| Case | Actual / bằng chứng | Kết quả và giới hạn |
|---|---|---|
| Desktop1440×1000 | screenshots/login-desktop-1440.jpg | LogoDB, heading đúng tên, hai nửa; form dọc, không CAPTCHA/hotline |
| Tablet768×1024 | screenshots/login-tablet-768.jpg | Hai cột, brand/padding nhỏ hơn; dữ liệu fixture autofill trong browser được mask ở password |
| Mobile360×800 | screenshots/login-mobile-360.jpg | Một cột, artwork ẩn; lỗi required tiếng Việt sau submit trống; full-page có thể cao hơn viewport |
| Tràn ngang | screenshots/viewport-bounds.json: mobile client/scroll345/345 (scrollbar), tablet768/768, desktop1440/1440 | Không tràn ngang trong các phép đo này; chưa kiểm zoom200%/mọi thiết bị |
| Login200 | Nhập fixture-customer/fixture-only → heading Phiên hiện tại tại /workspace?authPreview=v2 | Pass mock browser |
| Reload phiên | Reload → bootstrap loading → summary trở lại | Pass fixture browser; screenshots/session-mobile-reload.jpg |
| Logout và reload | Logout → Đã đăng xuất. → login; reload vẫn login | Pass fixture browser, không phải server revocation/security proof |
| Startup | Lần mở này bootstrap về login được, không gặp INVALID_RESPONSE | Không phải kiểm fresh installation/reset service worker hoàn toàn |

Ảnh ban đầu từ wrapper getScreenshot bị co khung khi đổi viewport; đã ghi đè các ảnh bàn giao bằng screenshot trực tiếp fullPage của browser và kiểm lại artifact, không chỉnh bitmap để tạo evidence. Các ảnh khác ngoài thư mục này không phải evidence của run03.

Gate tự động theo scripts dự án, log `layout-report/full-checks.log`; bản đầu trước điều chỉnh ảnh/label có151/151 và6 gates đạt, giữ ở `pre-visual-adjustment-checks.log`. Kết quả bản cuối ghi ở phần cập nhật cuối báo cáo, không dùng log cũ thay snapshot mới. Lệnh thực: helper `.tmp/fe-fnd-01-authui-20261117/layout-check.cjs` gọi pnpm pin10.34.9 lần lượt lint/typecheck/api:check/format:check/test --reporter=verbose --maxWorkers=2/build. OS Windows, Node22.14.0, Vite7.3.8; chỉ MSW. Không có CI/API thật.

## 4. Phạm vi còn mở và duyệt

Mốc FE-01-01-LOGIN-UI chỉ là bố cục, khác mốc parent auth hoàn chỉnh. Parent FE-01-01/FE-01-01-MOCK còn mở full browser OTP/resend/locked/expired, đa tab, keyboard/screen-reader/zoom và API v2 readiness/security/permission matrix. Các kết quả browser mới là subset FET-01/14, chưa đạt toàn FET-01/02/13/14 hoặc FE-TEST-03.

Mở `http://127.0.0.1:5174/login?authPreview=v2`, fixture-customer / fixture-only hoặc fixture-mfa / fixture-only để xem OTP. Phản hồi cần nhận: duyệt bố cục mới hoặc các điểm cần chỉnh. Không diễn giải việc user yêu cầu đổi layout là đã duyệt UI/mock hoặc cho phép merge.

## 5. Kết quả cuối

6/6 gates local đạt:17 files/151 tests và build6.55s, hoàn tất02:37:26 ngày18/11 Asia/Bangkok (19:37:26 UTC17/11 trong log). Sau thêm aria-required cho username/password/OTP, lint/typecheck/format đều đạt lần nữa lúc02:38:45; build cuối đã bao gồm thay đổi này. Không tăng timeout/bỏ assertion. Test host chỉ cập nhật bước mở details trước helper OTP, các flow expectations giữ nguyên.

Bố cục FE-01-01-LOGIN-UI **Chờ review**, chưa duyệt. Manifest ghi source/assets/report/screenshot checksums và working tree, không gán kết quả cho HEAD chưa chứa thay đổi. Task cha/GAP/integration mở đúng phạm vi mục4. Không có câu hỏi nghiệp vụ mới cho thay đổi này.
