# Chạy local, debug và giao ước build

| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-LOCAL |
| Phiên bản / ngày | 1.1 / 08/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt tài liệu | Người phụ trách Frontend xác nhận trong chat ngày 09/11/2026 |
| Owner / reviewer | Frontend / Backend, Cloud, QA |

## 1. Điều kiện chuẩn bị

Thư mục làm việc FE là digitalbanking-frontend. FE-FND-01 cung cấp source, package/lockfile và cấu hình môi trường. FE-FND-02 cung cấp client/DTO và scripts kiểm tra contract. Tại baseline nguồn main@f6ae7ec, các đầu ra này đang được theo dõi trong [[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]].

Chốt runtime, package manager và UI library theo ADR-001 trước khi cài dependency. Backend và dữ liệu QA cần được chuẩn bị cùng phiên bản contract trước kiểm tra tích hợp. Các tên lệnh bên dưới là giao ước cần hiện thực trong package đích, trạng thái cung cấp theo task tương ứng.

## 2. Quy trình thiết lập và phát triển

1. FE-FND-01 đưa source/assets/config/tests vào digitalbanking-frontend theo mục công việc tiếp theo trong tracker.
2. Tạo package, pin runtime/packageManager, lockfile và public env example theo ADR-001.
3. FE-FND-02 sinh DTO từ ../digitalbanking-docs/openapi/digital-banking-v1.yaml, hiện thực transport/session và kiểm tra schema.
4. Cài dependency bằng lockfile đã pin, cấu hình dev proxy/API base theo môi trường được cấp.
5. Chạy dev, kiểm tra getSession/login và account trên backend đích theo FE-01/02.
6. Chạy các gate tương ứng, ghi kết quả cùng commit/môi trường vào task và evidence.
7. PR cung cấp package phải cập nhật chính xác lệnh setup/dev/test/build tại tài liệu này.

## 3. Giao ước scripts đích

Các tên lệnh dưới là đầu ra cần hiện thực trong package mới ở FE-FND-01/02, trạng thái thực tế theo tracker.

| Lệnh đích | Trách nhiệm |
|---|---|
| pnpm install --frozen-lockfile | Cài dependency tái lập bằng phiên bản packageManager đã pin |
| pnpm dev | Chạy FE dev server/proxy theo env local |
| pnpm lint / pnpm typecheck | Kiểm tra lint và TypeScript strict |
| pnpm test | Unit/component với Vitest |
| pnpm api:generate / pnpm api:check | Sinh types và kiểm tra drift OpenAPI |
| pnpm format:check | Định dạng |
| pnpm build / pnpm preview | Build dist và xem build local |
| pnpm test:e2e | Browser E2E theo môi trường QA |

Runtime/package manager/version chính xác theo 05 và ADR-001, pin trong file môi trường, packageManager và lockfile. Ghi output lệnh/phiên bản trong evidence.

## 4. Giao ước môi trường

| Cấu hình | Vai trò và bảo mật |
|---|---|
| VITE_API_BASE_URL | Public build-time API path, đề xuất mặc định /api/v1 |
| API_PROXY_TARGET | Dev server target, chỉ cấu hình công cụ local |
| VITE_APP_TIME_ZONE | Public timezone hiển thị, tên cuối cùng chốt khi tạo package |
| QA base URL / fixture identity | Cấu hình test riêng, secret cấp qua env QA và kênh riêng |

Dev env example chứa placeholder. VITE_* xuất hiện trong bundle, chỉ nhận public config. Cookie HttpOnly/Secure và CSRF cần topology local HTTPS/proxy được BE thống nhất tại GAP-05. Dùng cùng hostname/origin giữa login và API để kiểm tra cookie chính xác.

## 5. Debug theo triệu chứng

| Triệu chứng | Kiểm tra |
|---|---|
| Reload về login | getSession status/code, cookie flags/path/origin, FE-AUTH-01 và GAP-05 |
| POST 403 | CSRF/current session và capability theo GAP-07 |
| OTP sai gây logout | Interceptor đang nhầm AUTH_OTP_INVALID với session expired |
| Transfer 428 lặp | Intent/key/body có giữ nguyên, challenge purpose/binding đã đúng |
| History rỗng hoặc trang sai | Page 0-based, filter UTC, scope/accountId và page reset |
| Số tiền sai | Money string/BigInt hoặc decimal conversion theo API-DATA-01 |
| Deep link 404 | Static host SPA fallback và /api routing |
| UI source chưa ở thư mục đích | Kiểm tra đầu ra FE-FND-01 trong tracker và đường dẫn digitalbanking-frontend |

Log chỉ metadata an toàn theo FE-SEC-03, correlation dùng để phối hợp BE.

## 6. Build và static hosting

SPA build tạo dist theo công cụ đã chốt. Handoff cho Cloud gồm commit, toolchain/lockfile, public env, build command và artifact checksum. Static host phục vụ asset đúng content-type, fallback các app route về index.html. /api/v1 đi backend, API 404 giữ response API. TLS/cookie/CORS và cache policy được đối chiếu 08.

Đề xuất cache index.html revalidate và asset tên có hash cache dài hạn, Cloud review theo [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]]. Refresh deep link, session bootstrap, mutation CSRF và logout phải smoke test trên topology triển khai.

## 7. Liên kết

[[digitalbanking-docs/docs-frontend/management/03-work-tracker.md]] giữ trạng thái. [[digitalbanking-docs/docs-frontend/guides/02-demo-and-handover.md]] giữ checklist bàn giao. Backend đích cần được hiện thực và xác nhận readiness trước chạy integration.
