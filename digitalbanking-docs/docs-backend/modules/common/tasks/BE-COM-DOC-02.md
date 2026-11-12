# BE-COM-DOC-02 — Chốt căn cứ tài liệu trước implement

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-COMMON-02`
> **Phiên bản:** v1.0
> **Ngày:** 12/11/2026
> **Trạng thái:** Hoàn thành — quyết định tài liệu, chưa implement
> **Owner:** Phương
> **Reviewer:** Backend Lead, QA, Security (review triển khai tiếp theo)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]] §1.2/§1.5, [[digitalbanking-docs/docs-common/03. SRS.md]] §8.5, [[digitalbanking-docs/docs-common/07. API contracts.md]] §2–§5.

## 1. Yêu cầu đã chốt

Theo chỉ đạo owner: ưu tiên `docs-common`; nếu trong docs-common khác nhau thì ưu tiên `01–11` hơn `design/`. Chốt căn cứ trước implement, không sửa Java, application.yml, migration hoặc OpenAPI. Không biến giá trị Đề xuất thành policy có hiệu lực, không tự bổ sung thiết kế rotation còn thiếu.

## 2. Công việc

| ID | Việc làm | Tiêu chí kiểm chứng | Trạng thái |
|---|---|---|---|
| ALIGN-01 | Ghi quy tắc nguồn vào README common, quy ước backend, configuration và README gốc | `01–11` trước design/backend; OpenAPI theo `07` | Done |
| ALIGN-02 | Phân xử 8 nhóm cũ | 7 nhóm đã phân xử; chỉ OI-COM-05 còn thiếu thiết kế rotation; giữ nhãn nguồn | Done |
| ALIGN-03 | Đồng bộ summary common và convention | DTO trực tiếp, Problem detail/errors, catalog không mã generic, masking `**** ****`, JCS đầy đủ | Done |
| ALIGN-04 | Đồng bộ đoạn cấu hình/identity liên quan | Baseline đề xuất lockout 10 phút, transfer 30/customer/phút, DB_SESSION/Path=/, không trần 3/1 từ design | Done |
| ALIGN-05 | Cập nhật thiết kế kiểm thử và hồ sơ | Thêm COM-CFG-03 kiểm cả scope customer; report cũ là lịch sử; report mới không xác nhận runtime | Done |
| ALIGN-06 | Kiểm tra tài liệu và phạm vi diff | Kiểm links/catalog/decision/test IDs, snapshot nguồn/runtime không đổi, diff không lỗi | Done |

## 3. Bàn giao và giới hạn

- [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]] v1.1 — căn cứ implement common.
- [[digitalbanking-docs/docs-backend/modules/common/report/BE-COM-DOC-02-verification.md]] — kết quả kiểm tra lần chốt này.
- Summary/convention/config/identity chỉ sửa các đoạn liên quan 8 nhóm common; không tuyên bố audit toàn bộ khác biệt toàn dự án.
- Runtime vẫn có baseline cũ 15 phút/20 mỗi user/trần session 3/1; việc code phải được giao và kiểm thử riêng. Rotation chưa được thiết kế/triển khai trong task này.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 12/11/2026 | Chốt ưu tiên 01–11 và đồng bộ tài liệu trước implement theo chỉ đạo owner |
