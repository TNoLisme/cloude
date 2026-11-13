# Phiếu chạy FE-FND-02-MOCK / run-01

## 1. Đầu vào và phạm vi

Ngày 13/11/2026, Asia/Bangkok; Codex thực hiện. Commit nền FE `c1760f10f6b90c1a79764c675c95d2f76a8c930e`, working tree có thay đổi chưa commit. Source legacy theo `9e09a71fd2c79c0486c4ee958e02fa2ef884314e`. BE không áp dụng. OpenAPI 1.0.0, SHA256 `71EFC546FB6645BE128EC83D391706343A6FF6B1FA6991910E42770D62EF796A`.

Nguồn đã đọc: AGENTS.md, FE README/tracker/delivery plan/roadmap/local guide, FE-TEST-01/03/04, ADR-001, stack mục 4.2, API contracts mục 1, security mục 1, FE-01/02, design registry/AUTH-LOGIN, source legacy client/App/accounts/history/operator/audit.

Người dùng chốt trong câu trả lời ngày 13/11/2026:

- “Giữ Ant Design; chuyển sang pnpm, Vite 7 và React Hook Form/Zod theo stack đích (đề xuất trong ADR-001).”
- “Toàn bộ màn đã có source cũ trước; sau đó lần lượt đồng bộ contract mới và bổ sung màn còn thiếu.”

Các quyết định này cho phép khôi phục source/cài stack và chuẩn bị mock theo API mới. Không đóng GAP nghiệp vụ. Q-03 đã hỏi: màn legacy khác/thiếu contract mới chỉ xem fixture, khóa thao tác hay mô phỏng riêng đầy đủ theo contract cũ? Người dùng trả lời trong cùng lần chạy: “Cho phép xem UI bằng fixture; khóa thao tác chưa có contract rõ ràng.” Q-03 đã chốt cho phạm vi preview, không đóng GAP nghiệp vụ.

## 2. Thay đổi hiện tại

Khôi phục toàn bộ source từ commit tham chiếu vào `digitalbanking-frontend`. Pin pnpm 10/Vite 7/RHF/Zod/MSW và Node; thêm cấu hình mock không proxy BE. Sinh DTO mới tại `src/shared/api/generated/openapi.d.ts`, giữ DTO legacy riêng cho source chưa chuyển. MSW có các handler theo OpenAPI cho login 200, session, logout, profile, account list/detail; fixture tổng hợp. Sau khi Q-03 được chốt, đã nối worker và adapter fixture chỉ xem vào app legacy. PreviewShell dùng RHF/Zod để chọn màn và trạng thái, khóa form legacy phụ thuộc contract, giữ khả năng điều hướng; route SPA có tín hiệu cập nhật trạng thái khóa. Staff seed/block bị vô hiệu; mọi legacy mutation bị adapter chặn trước network. Không tạo endpoint legacy ở BE.

## 3. Kiểm tra

| Kiểm tra | Expected / actual | Kết quả |
|---|---|---|
| Khôi phục source | Source từ git show đúng commit, không ghi đè file có sẵn | Pass |
| Sinh DTO mới | openapi-typescript 7.6.1 chạy với YAML hiện hành | Pass |
| Cài stack | pnpm 10.34.9 cài thành công; chạy lại install --offline --frozen-lockfile đạt; esbuild postinstall đạt | Pass |
| Typecheck/build | tsc -b đạt; Vite 7.3.8 production build đạt, còn cảnh báo Zod annotation và chunk 1.20 MB (gzip 374 KB) | Pass với giới hạn bundle |
| Test mock/session và legacy regression | Vitest 3.2.4: 6 file, 19 tests đạt; gồm mutation bị chặn, không mất phần thập phân, thiếu handler không gọi BE; các test legacy chỉ chứng minh source cũ | Pass |
| Browser/toàn bộ UI legacy | Browser smoke các route legacy có h1; audit và dữ liệu account được quan sát; 403/404/empty/error/bootstrap/OTP/receipt được quan sát. Staff lookup fixture có seed/block disabled; điều hướng SPA tới transfer cập nhật form disabled | Pass — smoke, chưa E2E nghiệp vụ |
| Format/API check | Prettier --check đạt; generated types khớp YAML qua scripts/check-api.mjs | Pass |

## 4. Kết quả và giới hạn

Kiểm tra tài liệu cuối lần chạy: 35 file Markdown mới/thay đổi, 185 đích wikilink tồn tại (hỗ trợ basename duy nhất trong vault); không kiểm tra heading. `git diff --check` đạt, chỉ có cảnh báo LF/CRLF. Các bản bổ sung design được ghi chờ review, không gán phê duyệt v1.2 cho nội dung mới.

Mức chứng minh hiện tại: đọc source, Mock/unit/component và Browser smoke. FE-FND-01 đang làm; FE-FND-02-MOCK Chờ review UI. Không có UI approval, API thật, bằng chứng ledger/security/cookie, PR hoặc merge. Dependency RHF/Zod được thêm không đồng nghĩa các form AntD legacy đã được chuyển. Form migration và architecture feature/shared đầy đủ tiếp tục ở task cha tương ứng.

## 5. Cách tái lập và artifact

Windows/PowerShell, Node 22.14.0, pnpm 10.34.9; contract 1.0.0; dữ liệu fixture preview-v1 tại src/mocks/fixtures.ts và legacy-preview.ts. Thời gian checks 13/11/2026 khoảng 01:24–01:30, Asia/Bangkok. Command thực tế: node node_modules/typescript/bin/tsc -b; node scripts/check-api.mjs; node node_modules/prettier/bin/prettier.cjs --check src scripts vite.config.ts tsconfig.json package.json index.html; node node_modules/vitest/vitest.mjs run; node node_modules/vite/bin/vite.js build. Cài tái lập được kiểm tra bằng npm exec --yes --offline --package=pnpm@10.34.9 -- pnpm install --offline --frozen-lockfile. Không tuyên bố đã cài sạch trên máy thứ hai.

Dev server mock: http://127.0.0.1:5174/customer. Chạy lại: pnpm dev:mock --port 5174 trong digitalbanking-frontend. Server sandbox cổng 5173 không truy cập được từ browser nên bản được xem chạy trên localhost 5174 ngoài sandbox; không mở host ra LAN. IAB/browser smoke qua cua_repl, viewport theo cửa sổ thực tế có cả layout desktop và mobile được quan sát; chưa kiểm tra ma trận 360/768/1440 và chưa ghi nhận browser version cụ thể nên FET-14/15 chưa nghiệm thu.

[Ảnh dashboard](screenshots/dashboard.png). Ảnh chụp working tree chưa commit, không thay cho phê duyệt người dùng. Kiểm tra chỉ xác nhận smoke hiển thị; không phải toàn bộ FET/AC. Một lượt locator chờ thông báo lỗi sai copy đã timeout; sau đó đọc AX xác nhận thông báo thực tế “Hệ thống chưa xử lý được yêu cầu. Vui lòng thử lại.”. Không có lỗi ứng dụng được suy ra từ lượt locator đó. MSW có dòng stderr cố ý trong test thiếu handler, test này đạt.

Các form register/recovery/PIN ở bước sâu chưa được mô phỏng vì thao tác ghi bị khóa theo quyết định. Filter/paging legacy chỉ là fixture tối thiểu, không cam kết behavior BE. Chưa có lint/test:e2e script; feature/shared, session/quyền, form migration toàn bộ và tối ưu bundle còn task cha. Người review UI: chờ người phụ trách FE; quyết định merge: chưa có.
