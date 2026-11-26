# Phiếu chạy FE-REL-V1-M4 / run-03

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 27/11/2026 Asia/Bangkok / Codex |
| Task cha / con | M4; kiểm chứng readiness BE cho FE-06-01/05-02/06-02/07-01 read API |
| FE SHA / working tree | Nền `bef460a`; tài liệu handoff M4 và script QA chưa commit; code FE chưa sửa |
| BE SHA / môi trường | BE source dev2@`740ab5f` đã ghép; image QA local `sha256:e2d688a212fde6c54c45f9f30f249474fc552c90f9bc6321f4357920142a51c0` từ M3; Compose project `banking-qa-m4`, backend `127.0.0.1:18084`, SMS `:18085`, Postgres `:15434`, DB mới |
| Contract | OpenAPI BE v1.1.0; common 07; handoff BE/Security M4 ngày 27/11/2026 |
| Mức chứng minh | API HTTP thật trên QA local với seed tổng hợp; chưa browser/UI M4 |
| Nguồn đã đọc | FE README/tracker/plan/lifecycle/template; handoff M4; BE compose.yaml/compose.qa.yaml, LocalQaSeed, qa-api-smoke.py, SecurityFilterChainConfig và controllers M4 |
| Phạm vi được phép | Người phụ trách FE chốt handoff và yêu cầu thực hiện rồi merge M4 trong chat 27/11/2026. QA dùng credential tạm, không ghi vào repo. |

## 2. Kiểm tra sẵn sàng

| Phụ thuộc / GAP / câu hỏi | Căn cứ và ảnh hưởng | Quyết định người dùng, ngày và nguồn | Có được tiếp tục? |
|---|---|---|---|
| QA credential | M2/M3 dùng env file tạm, xóa sau QA; lần đầu M4 bỏ `--env-file` nên Compose báo thiếu biến | Người dùng chỉ rõ M2/M3 từng kiểm được; đã đối chiếu evidence và dùng lại quy trình tạm | Có; tạo `.env` ignored với password/SMS token dùng một lần |
| Docker build trên checkout Windows | `gradlew` CRLF gây `./gradlew: not found`; build lần hai tải Gradle lâu | Dùng image M3 đã ghi SHA, source BE chưa sửa trong phạm vi M4; Dockerfile thử sửa rồi hoàn nguyên | Có với image M3 đã được ghi evidence; build mới chưa đạt |
| Cổng Postgres | 15432 đang được dùng | Chọn cổng QA riêng 15434 trong env tạm; không đổi source | Có |
| Handoff policy | BE/Security cho người phụ trách FE tự chốt theo thông tin chat | Ma trận handoff đã ghi riêng ở management 12/13 | Có cho QA; không tự đóng source GAP toàn cục |

## 3. Thay đổi và kịch bản

Thêm script `qa-m4-http.py` gọi API v1 với người dùng tổng hợp từ `LocalQaSeed`. Kiểm profile/account exact ID, approval list/detail missing, fraud list, audit list/detail missing theo OPERATIONS, ADMIN, AUDITOR, CUSTOMER. Script không lưu password/OTP/cookie. Env QA tạm ở `digitalbanking-backend/.env` bị `.gitignore` loại trừ; sẽ xóa sau khi hạ stack.

## 4. Đối chiếu hoàn thành

| Đầu ra / AC / FET | Expected | Actual | Trạng thái | Evidence / command / timestamp |
|---|---|---|---|---|
| QA backend startup | Health UP trên DB mới | `banking-qa-m4` backend UP; image SHA như trên | Pass | `docker compose --env-file .env -p banking-qa-m4 -f compose.yaml -f compose.qa.yaml up -d --no-build`; `/actuator/health`; 27/11/2026 |
| Role/read HTTP M4 | 200 đúng role, 403 quyền thiếu, 404 missing detail | Script in `PASS M4 HTTP: staff read roles, customer/auditor denial, approval/audit 404` | Pass subset | `python ..\digitalbanking-docs\docs-frontend\evidence\FE-REL-V1-M4\2026-11-27-bef460a\qa-m4-http.py` với QA env tạm; 27/11/2026 |
| Shape/masking/scope sâu | Response từng field đúng OpenAPI, cross-scope, detail có dữ liệu, cache/đổi phiên | Chưa kiểm; fixture không có approval/fraud item sẵn | Chưa chạy | Cần trước khi nhập từng lát M4 |
| FE auth→staff/browser | Staff sau login vào màn M4 và gọi v1; direct URL/403/cache | Chưa nối UI; hiện màn phiên chung | Chưa chạy | Chưa đủ điều kiện merge M4 |

## 5. Tài liệu và review

| Hạng mục | Kết quả |
|---|---|
| Handoff BE/Security | `management/12-m4-be-handoff.md`, `13-m4-security-handoff.md` — policy FE đã chốt |
| Tracker/plan | M4 đang làm, chưa hoàn thành; cần cập nhật theo kết quả thực tế |
| Review UI | Preview staff đã được người dùng chốt cho lát M4 theo chat trước; chưa nối vào phiên thật |
| Merge | Chưa thực hiện; FE/browser và QA response/scope còn thiếu |

## 6. Kết quả lần chạy

Đã khôi phục được QA backend bằng credential tạm theo đúng cách M2/M3 và đạt HTTP role smoke cho M4. Lệnh Compose đầu tiên thiếu env file là lỗi thao tác kiểm tra, không phải blocker BE. Sau QA, `docker compose --env-file .env -p banking-qa-m4 -f compose.yaml -f compose.qa.yaml down --volumes` đạt; đã xóa `.env` và Python `__pycache__` tạm. `git diff --check` đạt (Git chỉ cảnh báo chuyển LF/CRLF của plan). Không dùng kết quả này để đánh dấu task hoàn thành hoặc merge FE/BE; cần nối UI staff vào session v1, kiểm browser và các response có dữ liệu.
