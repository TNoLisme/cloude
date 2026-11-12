# Backend Team Work

> **Dự án:** Digital Banking MVP
> **Phạm vi:** Công việc chung của Backend, không thuộc phạm vi module riêng
> **Trạng thái:** Work log và report index

## Mục đích

Ghi nhận task nền tảng, tích hợp, review và công việc khác của Backend team. Mỗi task có task report và verification report; module-specific work được ghi trong thư mục module tương ứng.

## Report index

| Task ID | Công việc | Task report | Verification report | Trạng thái |
|---|---|---|---|---|
| `BE-FND-01` | Dựng Backend base project theo `00. architecture & conventions.md` và `deployment/configuration.md` | `BE-FND-01-backend-base-project.md` | `BE-FND-01-verification-report.md` | Verified (Spring Data JPA, Security, Validation, MapStruct/Lombok, ArchUnit, Flyway V1, Compose & Testcontainers) |

## Quy ước report

Mỗi task ghi trong `workofteam/` cần có:

1. Task ID, mục tiêu, phạm vi và nguồn tài liệu.
2. Các file đã tạo/sửa.
3. Quyết định và giá trị chưa chốt.
4. Lệnh đã chạy, expected result và actual result.
5. Verification report liên kết rõ với task report.
6. Evidence status: `Verified`, `Blocked`, hoặc `Not verified`.
7. Lỗi, hạn chế, handoff và next step.

Không ghi `Pass`/`Verified` khi chưa chạy đúng kiểm tra. Không ghi secret hoặc PII vào report. Module owner ghi module implementation trong `modules/<module>/`; task nhóm ghi tại đây.
