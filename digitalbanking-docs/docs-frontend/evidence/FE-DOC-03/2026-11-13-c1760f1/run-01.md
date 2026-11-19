# Phiếu chạy FE-DOC-03 / run-01

## 1. Định danh và đầu vào

| Trường | Giá trị |
|---|---|
| Ngày / người thực hiện | 13/11/2026, Asia/Bangkok / Codex |
| Task | FE-DOC-03; phụ thuộc triển khai FE-FND-02-MOCK |
| FE SHA / working tree | Nền `c1760f10f6b90c1a79764c675c95d2f76a8c930e`; thay đổi tài liệu chưa commit |
| BE SHA / môi trường | Không áp dụng — đọc tài liệu, chưa chạy mock hoặc API thật |
| Contract | OpenAPI 1.0.0; SHA256 `71EFC546FB6645BE128EC83D391706343A6FF6B1FA6991910E42770D62EF796A` |
| Mức chứng minh | Đọc tài liệu/source; kiểm tra liên kết tài liệu |
| Nguồn | FE README; tracker mục 2/2.1/3; delivery plan; local guide mục 1–3; FE-TEST-01/03/04; UI-DOC-01; evidence README; ADR-001; stack mục 4.2 |
| Phạm vi được phép | Lập lộ trình, quy trình tài liệu kiểm tra task, mock trước để người dùng xem/sửa UI rồi tích hợp BE từng phần; không tự quyết điểm chưa rõ |

## 2. Kiểm tra sẵn sàng

| Câu hỏi | Căn cứ / ảnh hưởng | Quyết định | Tiếp tục? |
|---|---|---|---|
| Q-01 / GAP-06 | ADR-001 chờ review; local guide yêu cầu chốt toolchain/UI trước cài dependency | Đã hỏi trong chat ngày 13/11/2026; chưa có trả lời | Soạn quy trình được phép; dựng app/cài dependency chờ |
| Q-02 | Người dùng yêu cầu mock trước, chưa chốt phạm vi đợt preview đầu | Đã hỏi nền tảng/auth/accounts trước hoặc toàn bộ source UI cũ | Lộ trình có thứ tự đề xuất; triển khai đợt preview chờ |

## 3. Thay đổi

Tạo lộ trình FE-ROADMAP, guide FE-RUN, template phiếu task; cập nhật README/delivery plan/tracker. Tạo AGENTS.md ở root, áp dụng cho công việc FE, để các lần chạy sau đọc nguồn, ghi evidence và hỏi trước phần chưa rõ. Cho phép theo dõi AGENTS.md trong .gitignore để quy trình có thể đi cùng repo.

Chưa có mock server/service worker hay URL xem UI. Không cài dependency, không thay backend, không commit hoặc merge.

## 4. Đối chiếu hoàn thành

| Đầu ra | Expected / actual | Kết quả |
|---|---|---|
| Lộ trình | Có thứ tự task, phụ thuộc, review UI và cổng tích hợp từng phần | Đã soạn; chờ người dùng review |
| Vòng đời tài liệu | Có đầu vào, kiểm tra nguồn, phiếu từng lần chạy, kiểm chứng, cập nhật docs/tracker và đánh giá hoàn thành | Đã soạn; chờ review |
| Không tự đoán | Điểm mở Q-01/02 được hỏi; không triển khai phần phụ thuộc | Đạt trong lần chạy này |
| Mock preview | Có app và MSW chạy được để xem UI | Blocked — chờ Q-01/02; chưa chạy |
| Link tài liệu mới/thay đổi | Script Node đọc 8 file Markdown mới/thay đổi: 78 đích file wikilink tồn tại, không có đích thiếu | Pass — chỉ kiểm tra đích file, không kiểm tra heading |
| Whitespace | `git diff --check` không phát hiện lỗi whitespace | Pass — Git có cảnh báo chuyển LF/CRLF |

Kiểm tra ngày 13/11/2026, môi trường Windows/PowerShell, Node v22.14.0. Lần gọi Python ban đầu không chạy được vì executable alias không truy cập được; đã dùng Node để hoàn tất kiểm tra liên kết. Không chạy test/build ứng dụng vì chưa có package đích và đây là thay đổi tài liệu. Không có bằng chứng Browser/API/Mock runtime trong lần này.

## 5. Review và kết quả

FE-DOC-03 Chờ review. FE-FND-02-MOCK Bị chặn do Q-01/02. Người dùng chưa duyệt chi tiết lộ trình/UI/toolchain; chưa có review PR/readiness BE/xác nhận merge. Các task integration vẫn mở. Sau câu trả lời, ghi quyết định vào nguồn liên quan rồi triển khai phần được chốt.
