# BE-COM-IMP-02 — Mô hình lỗi và exception mapping

> **Dự án:** Digital Banking MVP
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Trạng thái:** Planned
> **Owner / reviewer:** Phương / Chưa review
> **Mốc / phụ thuộc:** W3; task 01; trace filter và security wiring nghiệm thu tiếp ở 03/05

## 1. Mục tiêu và phạm vi

Cung cấp catalog lỗi, exception, Problem và cơ chế tạo/ghi lỗi dùng chung cho MVC, filter, security. Mapper không query bảng owner, ghi audit, thay trạng thái transfer hoặc retry transaction. Không tạo success envelope hay mã lỗi public ngoài nguồn.

## 2. Đọc bắt buộc

- [Quy tắc và AC chung](README.md); [[digitalbanking-docs/docs-common/07. API contracts.md]] §3.2, §4, §7.2, §10; [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] phần ErrorCode/Problem.
- [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §7.10, §11.5–11.6, Appendix C, chỉ áp dụng khi không trái `07`.
- [[digitalbanking-docs/docs-backend/modules/common/README.md]] §2.4, §4; [[digitalbanking-docs/docs-common/09. Test plan.md]] §3, §7.7.

## 3. Tiêu chí chấp nhận riêng

| ID | Tiêu chí | Kiểm chứng tối thiểu |
|---|---|---|
| COM-IMP-02-AC01 | Toàn bộ public code, HTTP status và retryable khớp catalog `07` §4.3; internal codes không trả client; không dùng alias từ nguồn cấp dưới | Test đối chiếu đầy đủ catalog, không chỉ vài code mẫu |
| COM-IMP-02-AC02 | Problem có đủ field bắt buộc, content type, detail an toàn, errors[] và extension đúng ngữ cảnh; extension không ghi đè core; success DTO không bị auto wrap | Serialization/contract tests cả success, validation và extension; `requestId` không bị tự thêm thành field bắt buộc |
| COM-IMP-02-AC03 | Validation, JSON sai, media/method/route sai và exception bất ngờ được map đúng; writer dùng được ngoài MVC; hỗ trợ 401/403, WWW-Authenticate và Retry-After theo contract | Component tests qua HTTP cho MVC và harness filter/security; body-limit enforcement thuộc 05, wiring trace thật thuộc 03 |
| COM-IMP-02-AC04 | DB mapping phân biệt 55P03 in-progress có ngữ cảnh với lock timeout; unique idempotency do owner phân loại, invariant không thành lỗi client; lỗi audit đã xác định không bị generic mapper nuốt mất | Tests bảng mapping §4.4 README sau đối chiếu `07`; mock chỉ chứng minh mapping, PostgreSQL integration thực ở 07 |
| COM-IMP-02-AC05 | Response/log do error layer tạo không lộ SQL, schema/constraint, provider detail, rejected secret/PII hoặc stack trace chưa redaction | Negative tests đưa dữ liệu synthetic nhạy cảm vào cause/validation; kiểm output thực |

## 4. Bàn giao

Áp dụng thêm COM-G01..04. Tạo `report/BE-COM-IMP-02-verification.md` theo [mẫu](../report/VERIFICATION-TEMPLATE.md). Report chỉ rõ phần writer/mapper đã kiểm chứng và phần cần 03/05/07 kiểm chứng trong pipeline/DB thật; không coi harness là toàn hệ thống đã đạt.
