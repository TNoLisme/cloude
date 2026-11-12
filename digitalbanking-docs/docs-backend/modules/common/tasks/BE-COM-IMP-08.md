# BE-COM-IMP-08 — Readiness, Cloud verification và bàn giao

> **Dự án:** Digital Banking MVP
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Trạng thái:** Planned
> **Owner / reviewer:** Phương phối hợp owner hạ tầng (chưa phân công trong tài liệu 11) / Chưa review
> **Mốc / phụ thuộc:** Chuẩn bị W6; Cloud W7; kiểm chứng/bàn giao W8; release candidate qua quality gate, có môi trường và đầu mối hạ tầng

## 1. Mục tiêu và phạm vi

Chuẩn bị cấu hình/hướng dẫn vận hành common, hỗ trợ triển khai và kiểm tra lại hành vi trên Cloud. Owner common phụ trách phần ứng dụng; không mặc định nhận toàn bộ provisioning, database backup/restore hoặc CI/CD. Task không được đóng bằng checklist local; thiếu Cloud/owner/release candidate thì hoàn thiện phần độc lập và ghi Blocked cho AC tương ứng.

## 2. Đọc bắt buộc

- [Quy tắc và AC chung](README.md); [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] §4–§7, §9, §11; [[digitalbanking-docs/docs-common/09. Test plan.md]] §8–§10.
- [[digitalbanking-docs/docs-common/10 Master timelines.md]] §4.6–4.8; [[digitalbanking-docs/docs-common/11. Resource & Staffing Plan.md]] §7–§10.
- [[digitalbanking-docs/docs-common/07. API contracts.md]] §2–§4, §8.9.1; [[digitalbanking-docs/docs-backend/deployment/README.md]] và [[digitalbanking-docs/docs-backend/deployment/configuration.md]] phần liên quan common, đối chiếu nguồn chuẩn.

## 3. Tiêu chí chấp nhận riêng

| ID | Tiêu chí | Kiểm chứng tối thiểu |
|---|---|---|
| COM-IMP-08-AC01 | Readiness ghi đúng artifact/revision, config/profile, secret references, dependency và owner hạ tầng; các policy Đề xuất/rotation còn mở có trạng thái và quyết định phạm vi release rõ ràng | Checklist có link report 07 và quyết định thực tế; không ghi secret value, không tự đóng OQ/OI |
| COM-IMP-08-AC02 | Bản triển khai Cloud khởi động, app/DB health đúng; cấu hình thiếu/sai bắt buộc thất bại an toàn; có hướng dẫn restart/rollback phần ứng dụng | Deployment/startup log và health trên môi trường thực; negative config test ở môi trường cô lập tương đương, không phá môi trường đang phục vụ |
| COM-IMP-08-AC03 | HTTPS/proxy, cookie/CSRF/CORS, headers và trace thực tế đúng contract, gồm lỗi tại proxy/filter; không suy TLS hay Cloud behavior từ unit test | Request/response evidence trên URL Cloud với cả success, origin/CSRF sai, 401/403, body quá lớn; không lưu token/cookie thật |
| COM-IMP-08-AC04 | Log/metrics/health quan sát được trên Cloud, truy request đến dữ liệu/luồng liên quan; không lộ secret/PII, endpoint vận hành được bảo vệ | Cloud log/metric/health evidence và kiểm truy cập; test synthetic, ghi cấu hình liên quan đã che dữ liệu nhạy cảm |
| COM-IMP-08-AC05 | Regression phần common trong các hành trình W8 đạt; lỗi sau deploy có fix/redeploy/retest; không còn lỗi vi phạm AC chưa xử lý | Dẫn test/report của luồng identity/account/transfer và vận hành dùng common trên Cloud; chỉ rõ phần dựa evidence owner khác |
| COM-IMP-08-AC06 | Tài liệu cấu hình, usage, vận hành, giới hạn và hồ sơ bàn giao khớp bản đã kiểm chứng; người khác có đủ bước để tái kiểm tra | Link docs/runbook/report/artifact; owner/việc còn lại rõ ràng; không nhận toàn bộ hệ thống đã nghiệm thu từ report common |

## 4. Bàn giao

Áp dụng thêm COM-G01..04. Tạo `report/BE-COM-IMP-08-verification.md` theo [mẫu](../report/VERIFICATION-TEMPLATE.md). Tách evidence W6, W7, W8 trong cùng report; ghi URL/môi trường và revision của từng lần. Nếu chưa có Cloud evidence, giữ task chưa Done dù code và tài liệu local đã hoàn thiện.
