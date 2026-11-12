# BE-COM-IMP-05 — HTTP security và tích hợp identity

> **Dự án:** Digital Banking MVP
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Trạng thái:** Blocked — phần common đã triển khai/kiểm chứng; thiếu tích hợp identity thật; [verification](../report/BE-COM-IMP-05-verification.md)
> **Owner / reviewer:** Phương, phối hợp owner identity / Chưa review
> **Mốc / phụ thuộc:** W3; task 01–03 và contract/code xác thực liên quan

## 1. Mục tiêu và phạm vi

Hoàn thiện security chain, CORS/CSRF, HTTP headers, giới hạn request và điểm nối authentication/authorization. Common không triển khai vòng đời session, OTP, ownership hoặc maker/checker. Chốt trách nhiệm enforcement rate limit giữa lớp HTTP và owner; không coi property là enforcement. TLS qua proxy được kiểm chứng môi trường ở 08.

## 2. Đọc bắt buộc

- [Quy tắc và AC chung](README.md); [[digitalbanking-docs/docs-common/06. System architecture.md]] §9; [[digitalbanking-docs/docs-common/07. API contracts.md]] §2.8–2.9, §3, §4.
- [[digitalbanking-docs/docs-common/design/04. security.md]] §6.1, §8, chỉ bổ sung khi không trái `01–11`.
- [[digitalbanking-docs/docs-backend/modules/common/README.md]] §1.5, §2.1, §4.1, §7.2; [[digitalbanking-docs/docs-backend/modules/identity/README.md]] phần tích hợp security hiện có, đối chiếu nguồn chuẩn.

## 3. Tiêu chí chấp nhận riêng

| ID | Tiêu chí | Kiểm chứng tối thiểu |
|---|---|---|
| COM-IMP-05-AC01 | Security chain dùng contract identity và bảo vệ endpoint/method đúng nguồn; 401/403 là Problem có trace, không redirect/HTML | Tests anonymous, session không hợp lệ và thiếu quyền; kiểm 401 header theo `07`; nếu identity chưa sẵn sàng thì phần tích hợp thật Blocked |
| COM-IMP-05-AC02 | Cookie-authenticated mutation cần CSRF token gắn session; thiếu/sai trả 403 AUTH_FORBIDDEN; Bearer không dựa cookie được miễn, preflight không yêu cầu CSRF | Positive/negative tests cookie, token sai/khác session, Bearer, OPTIONS; không tắt CSRF toàn cục hoặc miễn chỉ vì header Bearer xuất hiện |
| COM-IMP-05-AC03 | CORS chỉ cho origin cấu hình, credentials đúng, methods/headers/exposed headers hỗ trợ contract; origin ngoài allowlist không được cấp quyền đọc qua CORS | Preflight và actual request tests allow/deny; không wildcard origin với credentials |
| COM-IMP-05-AC04 | Security/cache headers theo `07` §2.9 trên response liên quan, gồm cả lỗi; cookie thuộc ứng dụng tích hợp đúng thuộc tính ở §3.2 | Tests HTTP headers và Set-Cookie qua identity; nêu rõ header nào do proxy sở hữu và cần kiểm ở 08 |
| COM-IMP-05-AC05 | Body-limit và rate-limit có enforcement tại lớp được xác định; quá giới hạn trả 413/429 đúng Problem/trace, 429 có Retry-After; transfer bucket theo customer, không mỗi session/user | Boundary tests với cấu hình thực, nhiều session cùng customer; ngưỡng 64 KB/30 transfer mỗi phút là Đề xuất theo `07`, không tự nhận policy release đã phê duyệt |

## 4. Bàn giao

Áp dụng thêm COM-G01..04. Tạo `report/BE-COM-IMP-05-verification.md` theo [mẫu](../report/VERIFICATION-TEMPLATE.md). Phần owner/proxy chưa có ghi Blocked ở AC bị ảnh hưởng; harness chỉ chứng minh phần common, không thay integration thật. Bàn giao enforcement ownership, cấu hình và case cần retest ở 07/08; không mở rộng task thành viết module identity/transfer.
