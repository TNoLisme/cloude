# BE-COM-IMP-03 — Request context và correlation

> **Dự án:** Digital Banking MVP
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Trạng thái:** Planned
> **Owner / reviewer:** Phương / Chưa review
> **Mốc / phụ thuộc:** W3; task 01–02

## 1. Mục tiêu và phạm vi

Cung cấp context theo request, correlation/request ID và MDC; kết nối với error writer. Filter chạy trước security, không truy cập dữ liệu nghiệp vụ. Propagation vào bảng/job thực tế thuộc owner và task 07; task này cung cấp cách truyền context và kiểm chứng lifecycle ở cấp thành phần.

## 2. Đọc bắt buộc

- [Quy tắc và AC chung](README.md); [[digitalbanking-docs/docs-common/03. SRS.md]] FR-038, AC-022; [[digitalbanking-docs/docs-common/07. API contracts.md]] §2.2, §2.7.
- [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §4.6, §5.6; [[digitalbanking-docs/docs-common/design/04. security.md]] §8.1.
- [[digitalbanking-docs/docs-backend/modules/common/README.md]] §2.2, §5.1, §7.2 (COM-CTX-*).

## 3. Tiêu chí chấp nhận riêng

| ID | Tiêu chí | Kiểm chứng tối thiểu |
|---|---|---|
| COM-IMP-03-AC01 | Header hợp lệ được giữ; thiếu sinh UUID; rỗng/sai pattern/vượt 64 ký tự trả 400 VALIDATION_ERROR; không echo/log giá trị header sai | Tests thiếu, độ dài 1/64/65, ký tự không hợp lệ; response lỗi có correlation an toàn |
| COM-IMP-03-AC02 | Mỗi request có requestId mới do server sinh; context/MDC/correlation response nhất quán; client không điều khiển requestId | Tests retry cùng correlation, request ID giả từ client, success và lỗi |
| COM-IMP-03-AC03 | Filter đăng ký một lần trước security; X-Correlation-ID có trên success, validation, 401/403 và lỗi trước MVC; không double-write khi error/async dispatch | Tests qua filter chain và dispatch liên quan; wiring authentication thật kiểm tiếp ở 05 |
| COM-IMP-03-AC04 | Context/MDC không rò giữa request nối tiếp/cùng worker hoặc đồng thời; cleanup cả khi exception; async/job có cách capture/bind/clear rõ ràng | Tests song song, reuse thread, exception và handoff context; không chỉ kiểm ThreadLocal.get trong happy path |
| COM-IMP-03-AC05 | Metadata IP/UA không tin header proxy tùy ý, không log raw PII/control characters; có hướng dẫn consumer propagate correlation thay vì sinh ID mới giữa flow | Test metadata/log injection theo trust boundary đã xác định; docs usage và giới hạn proxy/async |

## 4. Bàn giao

Áp dụng thêm COM-G01..04. Tạo `report/BE-COM-IMP-03-verification.md` theo [mẫu](../report/VERIFICATION-TEMPLATE.md). Bàn giao trace/context cho 05–07; không nhận đã đạt AC-022 xuyên DB chỉ từ test filter.
