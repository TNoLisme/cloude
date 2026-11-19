# OpenAPI Contract

> **Dự án:** Digital Banking MVP
> **Tệp:** `digital-banking-v1.yaml`
> **Phiên bản:** 1.0.0
> **Trạng thái:** Đã có — cần đối chiếu `07. API contracts`
> **Cập nhật:** 03/11/2026

## 1. Mục đích

Thư mục này chứa hợp đồng API **máy đọc** của Digital Banking MVP, dùng cho validation, sinh client và kiểm thử hợp đồng.

- Tệp chính: [[digitalbanking-docs/openapi/digital-banking-v1.yaml]]
- Nguồn ngữ nghĩa: [[digitalbanking-docs/docs-common/07. API contracts.md]]

## 2. Quan hệ với `07. API contracts`

| Nội dung | Nguồn |
|---|---|
| Quy tắc nghiệp vụ, error semantics, idempotency, step-up | `07. API contracts` |
| Schema, endpoint, status code, ví dụ máy đọc | `digital-banking-v1.yaml` |

Khi hai bên mâu thuẫn, **`07. API contracts` là nguồn ngữ nghĩa**; cập nhật `07` trước, sau đó đồng bộ YAML.

## 3. Nội dung chính

- OpenAPI 3.1.0.
- Server local và demo.
- Nhóm endpoint: Auth, Customer, Account, Transfer, Transaction, Limit, Audit, Fraud, Role, Approval, Health.
- Lỗi theo RFC 9457 Problem Details.
- Tiền và ID dạng chuỗi; thời gian ISO 8601 UTC.

## 4. Quy trình thay đổi

1. Cập nhật mô tả ngữ nghĩa trong `07. API contracts`.
2. Cập nhật `digital-banking-v1.yaml` tương ứng.
3. Tăng `info.version` theo quy ước versioning của dự án.
4. Chạy validation/lint OpenAPI và kiểm thử hợp đồng.
5. Cập nhật traceability trong `07`, README này và README gốc nếu trạng thái thay đổi.

## 5. Quy ước

- Không đưa logic nội bộ hoặc thông tin nhạy cảm vào spec.
- Mọi thay đổi breaking phải được ghi rõ và có kế hoạch migration cho client.
- Ví dụ trong spec phải dùng dữ liệu tổng hợp, không dùng PII thật.
- Giữ `operationId`, tag và schema ổn định để sinh client không bị phá vỡ.

## 6. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Hợp đồng ngữ nghĩa | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Yêu cầu hệ thống | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Kiến trúc | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Tài liệu backend | [[digitalbanking-docs/docs-backend/README.md]] |
| Tài liệu frontend | [[digitalbanking-docs/docs-frontend/README.md]] |
| README gốc | [[digitalbanking-docs/README.md]] |

## 7. Baseline v2 riêng ngày 14/11/2026

| Contract | Version / base | Nguồn ngữ nghĩa | Trạng thái |
|---|---|---|---|
| [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] | 1.0.0 /api/v1 | [[digitalbanking-docs/docs-common/07. API contracts.md]] | Giữ nguyên baseline cũ |
| [[digitalbanking-docs/openapi/digital-banking-v2.yaml]] | 2.0.0 /api/v2 | [[digitalbanking-docs/docs-common/07a. API contracts v2.md]] | Draft FE/mock, chờ BE/Security runtime |

Theo quyết định người phụ trách FE, v2 dùng SESSION_TOKEN/Path=/api/LOGIN_MFA, giữ v1. Tách version vì đây là thay đổi breaking; không sửa evidence/DDL cũ. Cookie header, DB purpose, coexistence và routing v2 phải được BE/Security/DB kiểm chứng trước tích hợp thật. Generated types v1/v2 tách riêng; api:check kiểm tra cả hai.
