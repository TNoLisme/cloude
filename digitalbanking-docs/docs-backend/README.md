# Backend Documentation

> **Dự án:** Digital Banking MVP
> **Phạm vi:** Tài liệu triển khai backend
> **Trạng thái thư mục:** Đang viết
> **Cập nhật:** 03/11/2026

## 1. Mục đích

`docs-backend/` chứa tài liệu **triển khai backend**: cách các module được hiện thực, migration dữ liệu, ghi chú tích hợp, runbook vận hành và bằng chứng kiểm thử.

Thư mục này không thay thế các nguồn chính thức:

- Yêu cầu nghiệp vụ và hệ thống: `docs-common/01–03`.
- Kiến trúc: `docs-common/06`.
- Hợp đồng API: `docs-common/07` và `openapi/`.
- Thiết kế chuyên sâu: `docs-common/design/`.

## 2. Trạng thái

| Hạng mục | Trạng thái |
|---|---|
| Tài liệu backend tổng thể | Đang viết |
| Thiết kế module | Đang viết |
| Migration và dữ liệu | Đang viết |
| Ghi chú triển khai API | Đang viết |
| Vận hành và runbook | Đang viết |
| Kiểm thử và bằng chứng | Đang viết |

> Hiện chưa có tài liệu nội dung trong thư mục này. Khi thêm tài liệu, cập nhật bảng trên và README gốc.

## 3. Cấu trúc dự kiến

```text
docs-backend/
├── README.md
├── modules/          # Thiết kế/triển khai theo module
├── database/         # Migration, seed, reconciliation
├── api/              # Mapping API với 07 và OpenAPI
├── operations/       # Cấu hình, observability, runbook
└── testing/          # Chiến lược và bằng chứng kiểm thử backend
```

Các thư mục con sẽ được tạo khi có tài liệu thực tế; không tạo thư mục rỗng.

## 4. Quy ước viết

1. **Liên kết nguồn:** mỗi tài liệu phải trỏ tới yêu cầu (`FR/NFR/AC`) và thiết kế liên quan.
2. **Không sao chép:** mô tả cách hiện thực và quyết định cục bộ; không chép lại đặc tả từ `docs-common`.
3. **Ghi rõ deviation:** nếu triển khai khác thiết kế, ghi lý do, ảnh hưởng và open issue/ADR.
4. **Bằng chứng cụ thể:** test report, log, migration script, commit/PR phải có đường dẫn hoặc mã tham chiếu.
5. **Trạng thái rõ ràng:** `Đang viết`, `Draft — chờ review`, `Hoàn thành`.
6. **Cập nhật README:** khi thêm tài liệu hoặc đổi trạng thái, cập nhật bảng trong README này và README gốc.

## 5. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Yêu cầu hệ thống | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Mô hình dữ liệu | [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] |
| Kiến trúc hệ thống | [[digitalbanking-docs/docs-common/06. System architecture.md]] |
| Hợp đồng API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| Thiết kế chuyên sâu | [[digitalbanking-docs/docs-common/design/README.md]] |
| OpenAPI | [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] |
| README gốc | [[digitalbanking-docs/README.md]] |
