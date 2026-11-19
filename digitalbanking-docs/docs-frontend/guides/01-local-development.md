# Chạy local, debug và giao ước build

> FE-02-01 ngày18/11: đã có preview customer v2 riêng theo chốt user tại `/customer?customerPreview=v2`; mock fixture được seed cho preview và không chứng minh API thật. Xem chi tiết tại [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-02.md]].

## FE-02-01 — Xem customer mock riêng

Chạy `pnpm dev:mock --port 5174` từ digitalbanking-frontend rồi mở `http://127.0.0.1:5174/customer?customerPreview=v2`. Đây là viewer fixture riêng, không phải trang đích sau login: dùng sidebar/nút mobile chuyển tài khoản/hồ sơ, “Xem chi tiết tài khoản” mở detail và quay lại. Mở “Điều khiển bản xem thử” chọn dữ liệu mẫu/rỗng/tải chậm/lỗi dịch vụ; hai nút fixture403 (account3002 của customer khác) và404 (account9999) giúp xem lỗi. Chuyển về Dữ liệu mẫu để xem bình thường; link auth v2 dẫn bản login/session riêng.

Fixture seed chỉ có customer tổng hợp, không dùng dữ liệu thật. Không cung cấp PIN/transfer/history, role staff hay backend session/security. Nếu mở trực tiếp detail, giữ query `customerPreview=v2`. Không tự suy diễn browser/mock là bằng chứng ownership server; xem result và phiếu chạy để biết các kiểm tra, giới hạn và lỗi còn theo dõi.


| Thuộc tính | Giá trị |
|---|---|
| Dự án / mã | Digital Banking MVP / FE-LOCAL |
| Phiên bản / ngày | 1.2 / 09/11/2026 |
| Trạng thái tài liệu | Hoàn thành |
| Phê duyệt v1.2 | Người phụ trách Frontend chốt trong chat ngày 09/11/2026 |
| Baseline đã chốt | Nội dung trước phần bổ sung design được người phụ trách FE xác nhận ngày 09/11/2026 |
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

## 8. Cập nhật design khi làm UI

Mở file của Screen ID tại [[digitalbanking-docs/docs-frontend/design/README.md]] trước khi sửa UI. Khi tạo màn chưa có code, điền khung mô tả trong file đã tạo. Khi sửa màn đã có code, cập nhật phần component/style/state/responsive và nguồn commit, ghi PR/evidence theo [[digitalbanking-docs/docs-frontend/design/README.md#UI-DOC-01 — Cập nhật theo màn hình]].

## 9. Preview mock được cung cấp ngày 13/11/2026

Node 22.14.0, pnpm 10.34.9, Vite 7.3.8, Ant Design 5.26.7; các dependency được pin trong package/lockfile của digitalbanking-frontend. Chạy `pnpm install --frozen-lockfile`, sau đó `pnpm dev:mock --port 5174`; mở http://127.0.0.1:5174/customer và dùng thanh chọn màn/trạng thái. `pnpm dev` cũng mặc định mock. `pnpm dev:api` là đường legacy riêng, chưa nghiệm thu backend đích.

Người dùng đã duyệt fixture chỉ xem và khóa thao tác thiếu contract. MSW đáp ứng auth/account theo YAML mới; adapter UI tách biệt cho source cũ chưa đồng bộ, không bổ sung contract BE. Không cần đăng nhập; persona tổng hợp chỉ phục vụ xem bố cục. Form legacy bị khóa, không nhập thông tin thật. Staff lookup có fixture sau khi nhập 0900000123; các nút ghi bị khóa.

Scripts hiện có: typecheck, test, api:generate/check, format/check, build/preview. Đã bổ sung lint/check trong FE-FND-01-TOOLING; chưa có test:e2e, RHF/Zod mới dùng cho thanh preview; không coi các task cha đã hoàn thành. Xem README package và [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-MOCK/2026-11-13-c1760f1/run-01.md]].

## 10. Gate nền tảng FE-FND-01-TOOLING

`pnpm lint` chạy ESLint 10.14.0 với recommended JS/TypeScript, rules-of-hooks và exhaustive-deps, zero warnings. Generated types và MSW worker không được lint thủ công. `pnpm check` chạy lần lượt lint → typecheck → api:check → format:check → test → build và dừng nếu có lỗi. Các script format bao gồm eslint.config.js. Node engine yêu cầu tối thiểu 22.14.0 và dưới 23, khớp .node-version/CI.

Workflow `.github/workflows/frontend.yml` dùng Actions pin SHA, Node từ .node-version, pnpm từ packageManager, `pnpm install --frozen-lockfile` và `pnpm check`; trigger PR hoặc push frontend/main có thay đổi FE/OpenAPI/workflow. Chưa chạy trên GitHub trong lần này. Browser E2E/API thật và các GAP scope/security vẫn là gate riêng. Chi tiết clean install ở [[digitalbanking-docs/docs-frontend/evidence/FE-FND-01/2026-11-14-cfde9ad/run-01.md]].

## Shared client/session mock v2 — 17/11/2026

Phần mới FE-FND-02-CLIENT-SESSION (17/11/2026) hiện là module nền tảng, chưa thay route preview. Chạy tập trung từ digitalbanking-frontend: `node node_modules/vitest/vitest.mjs run src/shared/api/client.test.ts src/shared/auth`. Full gate dùng scripts package theo quy trình hiện hành. [[digitalbanking-docs/docs-frontend/evidence/FE-FND-02-CLIENT-SESSION/2026-11-17-6bceb5d/result.md]] ghi kết quả, code và giới hạn. Không dùng dev:api để chứng minh controller v2 đã tích hợp; runtime v2 vẫn cần readiness BE/Security.

## FE-01-01 — xem login/session mock v2 (17/11/2026)

Từ `digitalbanking-frontend`, chạy `pnpm dev:mock --port 5174`, mở `http://127.0.0.1:5174/login?authPreview=v2`. Lần chạy này Vite báo ready tại port5174 và HTTP local được kiểm tra; công cụ browser tích hợp timeout và native Chrome approval timeout nên chưa xác nhận UI trong browser. Nếu port bận, dùng đúng port Vite báo. Link “Xem đăng nhập v2” cũng có ở thanh viewer cũ. Đây là mode dev mock riêng; `dev:api` và build production chưa nối UI v2.

| Cách thử | Kết quả fixture dự kiến, cần review browser |
|---|---|
| fixture-customer / fixture-only | Login200, màn “Phiên hiện tại”, chỉ metadata và logout |
| fixture-mfa / fixture-only | Login202, OTP ban đầu123456; nút “Xem mã OTP fixture” trên toolbar lấy mã hiện tại |
| Nhập000000 | OTP sai; resend tạo mã fixture mới, mã cũ không còn hợp lệ, attempts giữ theo server fixture |
| fixture-invalid / fixture-only | Credential401, giữ username/xóa password |
| fixture-locked / fixture-only | Account423 |
| Toolbar → Chưa đăng nhập / Khôi phục fixture đã đăng nhập | Mở lại kịch bản rõ ràng, reset fixture của preview |
| Toolbar → Khôi phục chậm / Lỗi khôi phục phiên | Loading1500ms hoặc503 một lần; retry rõ ràng/đến login |
| Toolbar → Lỗi logout máy chủ → login rồi logout |503 một lần; clear UI/flag, báo chưa xác nhận máy chủ; kiểm tra lại phiên có thể khôi phục trong cùng trang |
| Toolbar → OTP đã hết hạn → fixture-mfa | Challenge hết hạn, khóa verify/resend, còn đăng nhập lại |

Chọn dropdown rồi “Mở kịch bản”. Mã/password trên là dữ liệu tổng hợp, không dùng thông tin thật. Toolbar OTP nằm ngoài flow sản phẩm. Username khác hợp lệ cũng dùng cùng session tổng hợp; đây không phải authentication thực. Fixture TTL/attempts/các lỗi dùng để xem trạng thái, không tự chốt policy còn mở.

Theo chốt người phụ trách FE17/11, key duy nhất `digitalbanking.fixture.v2.signedIn` có giá trị `1` trong sessionStorage giúp reload dựng lại fixture; xóa khi logout. Không chứa user/password/OTP/CSRF/token; nếu storage bị chặn thì chỉ dùng memory. Challenge không persist. BroadcastChannel fixture chỉ “signed-in/signed-out”, channel session chỉ “invalidate”; tab khác clear UI, dùng “Kiểm tra lại phiên” để đối chiếu fixture. Chưa kiểm chứng ordering/đa tab bằng browser; không coi đây là session/cookie thật hay policy v1/v2 coexistence.

Kiểm tra tự động: `pnpm check`; tập trung: `pnpm test src/features/auth/v2 src/shared/auth/mfa.test.ts src/mocks/v2-auth-persistence.test.ts src/mocks/AuthPreview.test.tsx`. Browser checklist và kết quả chi tiết: [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]]. Không đổi backend baseline, không đóng readiness runtime v2/GAP-05/07/04 và không nghiệm thu FE-01-01 từ mock.

Cập nhật18/11/2026: browser17/11 đã hiển thị login và màn phiên chung sau reload; lỗi first-load đã thêm gate chờ ServiceWorker.controller trước mount. Bản sửa chưa retest browser do IAB18/11 không attach được. Các giới hạn browser nêu trên tiếp tục mở; kết quả cuối ở [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-17-6bceb5d/result.md]].

### Xem bố cục mới theo ảnh18/11

URL vẫn `/login?authPreview=v2`. Form mới dùng hình trái/logoDB và Digital Banking bên phải; mobile ẩn hình. Thanh chọn kịch bản/OTP fixture được thu gọn: mở “Điều khiển bản xem thử” ở góc dưới trái trước khi chọn scenario hoặc lấy mã sau resend. User/password/OTP thử và API version không đổi. Có browser smoke login/reload/logout và ảnh1440/768/360 tại [[digitalbanking-docs/docs-frontend/evidence/FE-01-01/2026-11-18-6bceb5d/layout-result.md]]; chưa phải full browser/security acceptance.


### Cập nhật ngày 18/11/2026 — CUSTOMER sau đăng nhập

Người phụ trách FE chốt trong chat: “tôi muốn sau khi đăng nhập thành công thì vào phần dashboard của customer, và trên màn hình chính của customer thì bỏ phần hiển thị trạng thái hoạt động của tài khoản ( đang hoạt động )”. Quyết định này thay thế phần giữ màn phiên chung sau login **đối với CUSTOMER**: trong preview auth v2, người dùng có userType và role CUSTOMER đến tổng quan `/customer?authPreview=v2`, dùng cùng controller phiên; không seed phiên customer riêng. Màn tổng quan không hiển thị status tài khoản; chi tiết vẫn đọc status theo contract. Vai trò khác giữ màn phiên chung; chưa chốt mapping GAP-07. Preview riêng vẫn tồn tại. Bản mock đang chờ review, không chứng minh runtime v2 hay quyền/ownership BE. Chi tiết: [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-03.md]].


### Run-04 ngày 18/11/2026 — trang chủ customer theo ảnh tham khảo

Theo yêu cầu người phụ trách FE trong chat 18/11, trang chủ mock dùng topbar thương hiệu/lời chào, sidebar trắng với icon và vùng active kiểu ảnh tham khảo. Menu chỉ có Trang chủ, Thông tin tài khoản và Hồ sơ của tôi, là các màn v2 đã nối trong FE-02-01. Nội dung chính hiển thị masked account, số dư khả dụng/asOf và thông tin tài khoản đọc từ GET /accounts; không dựng lãi suất, thẻ, vay, hóa đơn, khuyến mãi hoặc số liệu tổng hợp không có nguồn. Transfer/history có contract nhưng UI v2 thuộc task khác nên chưa mở menu ở đây. Dashboard tiếp tục không có nhãn trạng thái; màn chi tiết vẫn hiển thị status. Browser mock đã xem tại `/customer?authPreview=v2`; ảnh và giới hạn ở [[digitalbanking-docs/docs-frontend/evidence/FE-02-01/2026-11-18-b9ba604/run-04.md]]. Chờ review UI, không khẳng định API thật.


## FE-04-01-MOCK — demo customer ngày 19/11/2026

Theo yêu cầu người phụ trách FE ngày 18/11 và tiếp tục ngày 19/11, đã dựng demo CUSTOMER đọc danh sách `/transactions` và chi tiết `/transactions/{reference}` theo v2 FE/mock: status/account/direction/from-to/min-max/page20, masked counterparty, Money string, loading/empty/error và 403/404. `sort` dùng mặc định server theo chốt trong chat ngày 18/11: “Dùng thứ tự mặc định, chờ BE (đề xuất)”; whitelist sort của endpoint vẫn cần BE xác nhận trước bộ chọn. Không triển khai staff scope, transfer mutation/recovery hoặc kết luận quyền BE từ fixture. UI demo còn sửa sau, không ghi duyệt UI. Kết quả và giới hạn tại [[digitalbanking-docs/docs-frontend/evidence/FE-04-01-MOCK/2026-11-18-b9ba604/run-01.md]].
