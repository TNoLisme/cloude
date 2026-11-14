# Phiếu FE-FND-01 / run-01 — hoàn thiện kiểm tra nền tảng

## 1. Đầu vào

Ngày 14/11/2026, Asia/Bangkok; Codex. Branch frontend, baseline `cfde9ad205f4bc341d979f58b28bc2a065438fe2`, working tree sạch lúc bắt đầu. BE: không áp dụng. OpenAPI 1.0.0 không thay đổi.

Đã đọc AGENTS.md, FE README/nguồn chính thức, tracker FE-FND-01 và GAP-01/06, roadmap đợt 0, ADR-001, FE-LOCAL mục 1–3, FE-ARCH-01/02, FE-TEST-03/04, FE-RUN và template task; stack gốc mục 4.2 xác định ESLint/Prettier và GitHub Actions theo baseline dự án; OpenAPI README quy định nguồn contract.

## 2. Phạm vi được phép và phụ thuộc

Người dùng yêu cầu ngày 14/11/2026: “hãy bắt đầu thực hiện task theo lộ tình và vòng đời kiểm tra như đã chốt”. UI/mock và lộ trình đã được duyệt ở lần trước. Lần này hoàn thiện phần độc lập của FE-FND-01: lint, scripts/checks CI và xác minh cài sạch bằng package/lockfile đã pin. Không thay UI, API contract, session hoặc quyết định nghiệp vụ.

GAP-01: chỉ preview fixture được chốt; phạm vi tích hợp register/recovery/PIN/seed/block/unblock vẫn chờ. GAP-06: người dùng đã chọn AntD/pnpm/Vite/RHF/Zod; không ghi nhận thay phê duyệt kiến trúc. FE-FND-01 còn điều kiện chốt phạm vi/review, không tự đóng task cha vì các checks local đạt.

## 3. Đầu ra và tiêu chí kiểm tra

| Hạng mục | Expected | Actual / kết quả |
|---|---|---|
| Lint | ESLint chạy trên source/config/scripts với dependency exact; không bỏ qua lỗi để làm gate đạt | Pass — ESLint 10.14.0, zero warnings |
| CI | GitHub Actions dùng runtime/package manager pin, frozen lockfile, lint/typecheck/API/format/test/build | YAML đã parse, trigger/command/SHA pin đã kiểm tra; chưa chạy trên GitHub |
| Cài sạch | Thư mục kiểm chứng mới không có node_modules, cài bằng frozen lockfile, chạy checks theo phạm vi | Pass — 398 packages từ cache offline, install 4m 2.7s, esbuild postinstall đạt |
| Regression | Checks hiện có đạt sau thay đổi cấu hình | Pass — toàn bộ pnpm check, 6 file/19 tests, build đạt |
| Docs | Local guide/README/tracker và phiếu ghi đúng lệnh/kết quả/GAP | Đã cập nhật, chờ review cùng task con |

## 4. Review và kết quả

FE-FND-01-TOOLING Chờ review sau khi các checks local đạt; task cha FE-FND-01 vẫn Đang làm vì contract cho scope đầu, xác nhận kiến trúc và review/CI chưa đầy đủ. Browser: không áp dụng nếu không đổi UI. Không có API thật, CI remote, commit/push hoặc merge main trong lần này nếu chưa được thực hiện/xác nhận cụ thể.

## 5. Quyết định GAP-01 trong lần chạy

Người phụ trách FE trả lời ngày 14/11/2026: “Giữ các luồng này trong đợt tích hợp đầu; chờ BE/BA bổ sung contract rồi mới triển khai phần tương ứng.” Các luồng gồm đăng ký, recovery, PIN, tạo khách tại quầy, nạp số dư, khóa/mở khóa tài khoản. Phạm vi mong muốn được chốt phía FE; GAP-01 vẫn mở cho đến khi BE/BA bổ sung ngữ nghĩa/schema/AC tại nguồn. Phần implementation phụ thuộc chưa được phép suy đoán. Preview fixture chỉ xem đã duyệt tiếp tục giữ.

Trong kiểm tra môi trường đã thấy file generated/34 file được checkout CRLF trên Windows. Bổ sung .gitattributes để frontend/workflow dùng LF; chuẩn hóa file source bằng Prettier (không đổi hành vi UI). scripts/check-api.mjs chỉ chuẩn hóa CRLF trước so sánh để không báo drift giả; vẫn so sánh toàn bộ nội dung types. Kiểm tra API sau sửa đạt.

## 6. Bằng chứng tái lập và giới hạn

Clean source ở `.tmp/fe-fnd-01-cfde9ad-20261114-01/digitalbanking-frontend`, được tạo mới và xác nhận chưa có node_modules trước install. Lệnh install thực tế: `npm exec --yes --offline --package=pnpm@10.34.9 -- pnpm install --offline --frozen-lockfile`. Không sửa package/lockfile sau install. Dùng cache đã tải, không tuyên bố tải mới toàn bộ từ Internet. pnpm bỏ qua postinstall MSW theo cấu hình chỉ cho esbuild; worker MSW đã có trong repo, không tự nới danh sách build scripts.

Lệnh kiểm chứng: `npm exec --yes --offline --package=pnpm@10.34.9 -- pnpm check` trong bản cài sạch. [Log checks](test-report/clean-checks.log) có kết quả sáu gate và 19 tests; [kiểm tra drift cố ý](test-report/drift-negative.log) xác nhận code 1 khi thêm declaration sai trong bản sao, sau đó khôi phục. [Manifest 46 input files](test-report/source-manifest.json) ghi SHA256 và xác nhận bytes bằng source working tree thực tế. Chỉ sửa scripts/config/docs, không đổi hành vi hay bố cục UI. Chuẩn hóa LF không tạo diff nội dung component. Browser không chạy lại trong task này.

Hai vấn đề đã phát hiện/sửa: API check báo drift giả do generated CRLF/LF; format check báo 34 file sau checkout Windows. .gitattributes và chuẩn hóa source bằng Prettier giải quyết phần format; API check vẫn từ chối thay đổi types thật.

Workflow chưa push/chạy GitHub; kiểm tra YAML local không thay thế CI remote. Bundle lớn/annotation Zod vẫn là cảnh báo cũ, không tự đổi scope sang tối ưu UI. Chưa có test:e2e và chưa có API thật. Quyết định scope GAP-01 đã ghi nguyên ý ở mục 5; không xác nhận thay BA/BE/Security/kiến trúc. Người review task con: chờ người phụ trách FE. Baseline cfde9ad + working tree/manifest, chưa có commit mới hoặc push trong lần này.

Kiểm tra tài liệu cuối lần chạy: 7 file Markdown mới/thay đổi, 81 đích wikilink tồn tại; diff --check đạt (cảnh báo chuyển xuống dòng). Các file UI có thể xuất hiện M trong working tree do chuẩn hóa LF, không có thay đổi hành vi/component trong task tooling này.
