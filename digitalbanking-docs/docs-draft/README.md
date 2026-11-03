# Docs Draft

> **Dự án:** Digital Banking MVP
> **Vai trò:** Bản nháp và lưu trữ lịch sử
> **Trạng thái:** Lưu trữ
> **Cập nhật:** 03/11/2026

## 1. Mục đích

`docs-draft/` lưu các bản nháp, snapshot trước baseline và biên bản rà soát. Đây **không phải** nguồn chính thức để triển khai hoặc nghiệm thu.

Nguồn chính thức tương ứng nằm trong `docs-common/`:

- [[digitalbanking-docs/docs-common/01. Introduction.md]]
- [[digitalbanking-docs/docs-common/02. BRD.md]]
- [[digitalbanking-docs/docs-common/03. SRS.md]]
- [[digitalbanking-docs/docs-common/04. Database design & analysis.md]]

## 2. Nội dung lưu trữ

| Tài liệu | Loại | Trạng thái | Ghi chú |
|---|---|---|---|
| [[digitalbanking-docs/docs-draft/01. Introduction.md]] | Bản nháp | Lưu trữ | Snapshot trước khi baseline vào `docs-common` |
| [[digitalbanking-docs/docs-draft/02. BRD.md]] | Bản nháp | Lưu trữ | Snapshot trước khi baseline vào `docs-common` |
| [[digitalbanking-docs/docs-draft/03. SRS.md]] | Bản nháp | Lưu trữ | Snapshot trước khi baseline vào `docs-common` |
| [[digitalbanking-docs/docs-draft/04. Database Design & Analysis.md]] | Bản nháp | Lưu trữ | Bản trùng nội dung với `docs-common/04` |
| [[digitalbanking-docs/docs-draft/_reviews/phase-4-documentation-review.md]] | Biên bản review | Tham khảo | Ghi nhận findings và trạng thái resolved; không thay thế tài liệu chính thức |

## 3. Quy tắc sử dụng

1. **Không sửa bản lưu trữ** để thay đổi quyết định; mọi thay đổi phải thực hiện trên tài liệu chính thức trong `docs-common/`.
2. **Không dùng làm căn cứ triển khai/nghiệm thu**; chỉ dùng để tra cứu lịch sử hoặc so sánh thay đổi.
3. Khi cần lưu một snapshot mới, thêm tệp có ngày hoặc hậu tố rõ ràng và cập nhật bảng trên.
4. Biên bản review chỉ ghi nhận lịch sử; trạng thái cuối cùng của tài liệu do tài liệu chính thức quyết định.

## 4. Liên kết nhanh

| Mục đích | Tài liệu |
|---|---|
| Bản chính thức | [[digitalbanking-docs/docs-common/README.md]] |
| README gốc | [[digitalbanking-docs/README.md]] |
| Biên bản review | [[digitalbanking-docs/docs-draft/_reviews/phase-4-documentation-review.md]] |
