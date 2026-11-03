# Frontend Documentation

> **Dự án:** Digital Banking MVP
> **Phạm vi:** Tài liệu triển khai frontend
> **Trạng thái thư mục:** Đang viết
> **Cập nhật:** 03/11/2026

## 1. Mục đích

`docs-frontend/` chứa tài liệu **triển khai frontend**: kiến trúc ứng dụng, bản đồ màn hình, tích hợp API, xử lý lỗi, bảo mật phía client, kiểm thử và kịch bản demo.

Thư mục này không thay thế các nguồn chính thức:

- Yêu cầu và use case: `docs-common/02–03`.
- Hợp đồng API: `docs-common/07` và `openapi/`.
- Kiến trúc tổng thể: `docs-common/06`.
- Thiết kế bảo mật: `docs-common/design/04`.

## 2. Trạng thái

| Hạng mục | Trạng thái |
|---|---|
| Tài liệu frontend tổng thể | Đang viết |
| Kiến trúc và route | Đang viết |
| Bản đồ màn hình | Đang viết |
| Tích hợp API | Đang viết |
| Bảo mật phía client | Đang viết |
| Kiểm thử và demo | Đang viết |

> Hiện chưa có tài liệu nội dung trong thư mục này. Khi thêm tài liệu, cập nhật bảng trên và README gốc.

## 3. Cấu trúc dự kiến

```text
docs-frontend/
├── README.md
├── architecture/      # Kiến trúc SPA, route, state, API client
├── screens/           # Bản đồ màn hình và luồng người dùng
├── api-integration/   # Mapping với 07 và OpenAPI
├── testing/           # Test plan và bằng chứng
└── demo/              # Kịch bản demo và walkthrough
```

Các thư mục con sẽ được tạo khi có tài liệu thực tế; không tạo thư mục rỗng.

## 4. Quy ước viết

1. **Liên kết nguồn:** mỗi màn hình/luồng phải trỏ tới use case (`UC`), yêu cầu (`FR/AC`) và endpoint tương ứng.
2. **Không sao chép:** mô tả cách triển khai UI/UX và quyết định cục bộ; không chép lại SRS/API spec.
3. **Xử lý lỗi:** mọi luồng phải nêu cách hiển thị lỗi Problem Details, retry và trạng thái rỗng.
4. **Bảo mật:** ghi rõ cách lưu session/token, chống XSS/CSRF, masking dữ liệu nhạy cảm và logout.
5. **Bằng chứng:** ảnh chụp, video demo, test report phải có đường dẫn cụ thể.
6. **Trạng thái rõ ràng:** `Đang viết`, `Draft — chờ review`, `Hoàn thành`.

## 5. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Yêu cầu nghiệp vụ | [[digitalbanking-docs/docs-common/02. BRD.md]] |
| Use case và acceptance | [[digitalbanking-docs/docs-common/03. SRS.md]] |
| Hợp đồng API | [[digitalbanking-docs/docs-common/07. API contracts.md]] |
| OpenAPI | [[digitalbanking-docs/openapi/digital-banking-v1.yaml]] |
| Thiết kế bảo mật | [[digitalbanking-docs/docs-common/design/04. security.md]] |
| README gốc | [[digitalbanking-docs/README.md]] |
