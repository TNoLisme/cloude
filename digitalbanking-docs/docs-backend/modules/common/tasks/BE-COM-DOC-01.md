# BE-COM-DOC-01 — Hoàn thiện thiết kế chi tiết `common`

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-TASK-COMMON-01`
> **Phiên bản:** v1.0
> **Ngày:** 12/11/2026
> **Trạng thái:** Hoàn thành — công việc tài liệu
> **Owner:** Phương
> **Reviewer:** Backend Lead, QA, Security (chờ review)
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-backend/modules/common/README.md]], [[digitalbanking-docs/docs-backend/modules/_overview/11-common.md]], [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §12.

## 1. Mục tiêu / phạm vi

Viết README đủ 7 phần từ docs-common 03/06/07, design/01/02/04 và bản tóm tắt module. Dùng thêm 04 cho dữ liệu/constraint, 09 cho chiến lược/mã test, OpenAPI đối chiếu schema. Docs-common có thẩm quyền cao nhất.

Chỉ làm tài liệu; không sửa Java, migration, cấu hình hoặc OpenAPI. tasks/report nằm cạnh README module, lưu mô tả và bằng chứng, không thay đặc tả.

## 2. Công việc đã thực hiện

| ID | Công việc | Đầu ra / tiêu chí | Trạng thái |
|---|---|---|---|
| DOC-01 | Rà nguồn và hiện trạng | Đọc nguồn/khung 7 phần; phân biệt config hiện có với thiết kế chưa triển khai | Done |
| DOC-02 | Scope/traceability | README §1 đủ NFR-013..020/025, BR/FR/AC, owner phối hợp; dependency một chiều | Done |
| DOC-03 | Class/interface | README §2 chữ ký 15 class thuộc 4 gói, input/output/lifecycle/boundary | Done |
| DOC-04 | Dữ liệu/API/error | README §3–§4 catalog public lỗi và SQLSTATE có context, không invent generic mã/envelope | Done |
| DOC-05 | Trace/crypto/PII/observability | README §2/§4.5/§5–§6: sequence, cleanup, AES/HMAC/JCS/masking, audit ownership/metric | Done |
| DOC-06 | Test design | README §7 test level/input/oracle/trace, toàn bộ runtime test Planned | Done |
| DOC-07 | Ghi khác biệt nguồn | README §1.5 issue/owner/mốc chốt, không tuyên bố sửa/đóng nguồn | Done |
| DOC-08 | Chỉ mục/verification | Liên kết backend/overview/root; kiểm structure/link/catalog/trace/diff và lưu report | Done |

## 3. Bàn giao / ranh giới nghiệm thu

- Thiết kế: [[digitalbanking-docs/docs-backend/modules/common/README.md]].
- Verification: [[digitalbanking-docs/docs-backend/modules/common/report/BE-COM-DOC-01-verification.md]].
- Chỉ mục: [[digitalbanking-docs/docs-backend/README.md]], [[digitalbanking-docs/docs-backend/modules/_overview/README.md]], [[digitalbanking-docs/README.md]].

Done là hoàn thiện/kiểm tra tài liệu Task 1. Chữ ký mới là đề xuất; owner chốt issue nguồn trước code phần ảnh hưởng. Không chuyển runtime tests Planned thành Verified khi chưa có evidence.

Cập nhật tiếp theo: owner đã chốt ưu tiên `01–11`; kết quả phân xử 7 nhóm và rotation còn mở được ghi tại [[digitalbanking-docs/docs-backend/modules/common/tasks/BE-COM-DOC-02.md]]. Không tiếp tục xem cả 8 nhóm là quyết định nguồn đang chờ chọn.

## Change Log

| Phiên bản | Ngày | Thay đổi |
|---|---|---|
| v1.0 | 12/11/2026 | Ghi phạm vi, 8 đầu việc và đầu ra Task 1 |
