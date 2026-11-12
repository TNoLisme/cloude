# BE-COM-IMP-06 — Logging, metrics và health dùng chung

> **Dự án:** Digital Banking MVP
> **Phiên bản / ngày:** v1.0 / 12/11/2026
> **Trạng thái:** Done — verified common/local; chờ review; xem [verification](../report/BE-COM-IMP-06-verification.md)
> **Owner / reviewer:** Phương / Chưa review
> **Mốc / phụ thuộc:** W3–W5; task 02–05

## 1. Mục tiêu và phạm vi

Hoàn thiện structured logging, redaction, HTTP metrics và health cho backend. Giữ log kỹ thuật tách khỏi audit; nghiệp vụ tự phát metric/audit theo owner. Không dựng hệ thống log tập trung, dashboard hay job nghiệp vụ ngoài yêu cầu MVP. Phối hợp điểm tích hợp health API nếu endpoint nằm ngoài common.

## 2. Đọc bắt buộc

- [Quy tắc và AC chung](README.md); [[digitalbanking-docs/docs-common/03. SRS.md]] NFR-020/025, AC-022/023; [[digitalbanking-docs/docs-common/06. System architecture.md]] §12.
- [[digitalbanking-docs/docs-common/07. API contracts.md]] §2.6–2.7, §8.9.1; [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] §9.
- [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] §5.6; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]] §6.2, §7.2 (COM-OBS-01).

## 3. Tiêu chí chấp nhận riêng

| ID | Tiêu chí | Kiểm chứng tối thiểu |
|---|---|---|
| COM-IMP-06-AC01 | JSON log có timestamp và context/route/status/latency theo nguồn, correlation/request ID khớp request; log kết thúc được ghi trước cleanup | Thu và parse log thực cho success, validation, security và server error; không chỉ kiểm file cấu hình logger |
| COM-IMP-06-AC02 | Log không lộ credential/key/full PII, raw auth/transfer body, sensitive query/header; control characters không tạo log record giả | Synthetic leak/injection tests trên output log thực, gồm cả exception; không lưu secret thật trong evidence |
| COM-IMP-06-AC03 | HTTP metrics phản ánh request count, lỗi và latency; labels theo route/method/status, không chứa account/user/correlation/request/idempotency ID | Gọi request và kiểm metric export/snapshot thay đổi; kiểm tag/cardinality; không đếm lặp metric nghiệp vụ ở generic handler |
| COM-IMP-06-AC04 | Health phản ánh app/DB; phân biệt infrastructure probe và `/api/v1/health` Admin/Auditor; metrics/chi tiết nhạy cảm được bảo vệ | Tests DB UP/DOWN, anonymous/role đúng/sai, public probe không lộ secret; HealthResponse 503 theo `07` không bị cưỡng chế thành Problem |
| COM-IMP-06-AC05 | Có hướng dẫn đọc trace/log/metric/health và trách nhiệm owner phát tín hiệu nghiệp vụ; không ghi technical log thay audit trail | Docs + evidence một request truy được qua log/response; hạn chế metric/monitoring chưa triển khai được ghi rõ |

## 4. Bàn giao

Áp dụng thêm COM-G01..04. Tạo `report/BE-COM-IMP-06-verification.md` theo [mẫu](../report/VERIFICATION-TEMPLATE.md). Bàn giao cho 07/08; output từ local chưa chứng minh observability trên Cloud hoặc toàn bộ NFR-025 của hệ thống.
