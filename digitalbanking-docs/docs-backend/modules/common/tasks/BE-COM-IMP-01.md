# BE-COM-IMP-01 — Hoàn thiện cấu hình nền và thời gian

> **Dự án:** Digital Banking MVP
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Trạng thái:** Done — implementer verified; chờ review/sign-off
> **Owner / reviewer:** Phương / Chưa review
> **Mốc / phụ thuộc:** W2–W3; căn cứ tài liệu BE-COM-DOC-02, kiểm tra lại code hiện tại

## 1. Mục tiêu và phạm vi

Hoàn thiện cấu hình dùng chung, Clock UTC, timezone nghiệp vụ và hợp đồng tiêu thụ cấu hình. Sửa baseline lệch nguồn trong cấu hình hiện có; không thực thi session/OTP/rate limit, job hay transaction nghiệp vụ ở task này. Kiểm tra khóa mật mã thuộc task 04; HTTP security thuộc 05.

## 2. Đọc bắt buộc

- [Quy tắc và AC chung](README.md); [[digitalbanking-docs/docs-common/06. System architecture.md]] §6.1–6.3, §9, §11.2.
- [[digitalbanking-docs/docs-common/07. API contracts.md]] §2.4, §2.8, §3.2, §3.7; [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] §3–§4, §7.
- [[digitalbanking-docs/docs-backend/modules/common/README.md]] §1.5, §2.1; [[digitalbanking-docs/docs-backend/deployment/configuration.md]] các mục cấu hình bị sửa, đối chiếu lại nguồn chuẩn.

## 3. Tiêu chí chấp nhận riêng

| ID | Tiêu chí | Kiểm chứng tối thiểu |
|---|---|---|
| COM-IMP-01-AC01 | Cấu hình được bind và override theo môi trường đúng hợp đồng; cấu hình bắt buộc sai bị phát hiện khi khởi động với lỗi không lộ giá trị nhạy cảm | Test binding/override và trường hợp sai cho các trường đã thay đổi; không chỉ đọc YAML |
| COM-IMP-01-AC02 | Default liên quan tuân nguồn: lockout 5 lần/10 phút và transfer 30/phút/customer giữ nhãn Đề xuất; không áp trần session 3/1 từ design cấp dưới; các nơi khai báo không mâu thuẫn nhau | Test default + override, đối chiếu config/code/docs; kiểm enforcement thực tế ở 05/07 |
| COM-IMP-01-AC03 | Clock dùng UTC; timezone nghiệp vụ cấu hình được; kiểm thử không phụ thuộc timezone/giờ thật của máy | Test với clock cố định và thời điểm đổi ngày giữa UTC/business zone |
| COM-IMP-01-AC04 | Hợp đồng cấu hình/thời gian có thể dùng từ module khác mà common không import ngược module nghiệp vụ hoặc tự chạy rule/job | Kiểm dependency và usage; build/check kiến trúc hiện có đạt |
| COM-IMP-01-AC05 | Có hướng dẫn ngắn về cấu hình bắt buộc, default/override và tham số còn mở; phân biệt khả năng đã triển khai với config chỉ dành cho owner dùng sau | Docs + report đối chiếu implementation; không tuyên bố session/rate limit/rotation đã hoạt động chỉ vì có property |

## 4. Bàn giao

Áp dụng thêm COM-G01..04. Tạo `report/BE-COM-IMP-01-verification.md` theo [mẫu](../report/VERIFICATION-TEMPLATE.md). Bàn giao cấu hình/Clock cho 02–06, ghi rõ các consumer cần cập nhật; không sửa ngưỡng nghiệp vụ ngoài phạm vi để tự đóng open issue.

Verification ngày 12/11/2026: [report và evidence](../report/BE-COM-IMP-01-verification.md). Không đồng nghĩa session/rate limit/job hoặc toàn module đã được nghiệm thu.
