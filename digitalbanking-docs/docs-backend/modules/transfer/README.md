# Module `transfer` — Chỉ mục

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-TRANSFER-00`
> **Phiên bản:** v1.0
> **Ngày cập nhật:** 15/11/2026
> **Trạng thái:** Draft — chờ review
> **Owner:** Vũ
> **Reviewer:** Backend Lead, Account/Ledger/Identity, QA, Security — chờ review
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] §2.4, §2.13; [[digitalbanking-docs/docs-common/06. System architecture.md]] §6–§8; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §12.

`transfer` điều phối chuyển tiền giữa các tài khoản trong cùng ngân hàng mô phỏng; chịu trách nhiệm vòng đời transfer, idempotency và phục hồi PENDING. Module phối hợp với account, identity, ledger, limit, audit, fraud, notification và common qua internal API. Nó không sở hữu số dư, ledger hay cơ chế xác thực của các module đó.

- [Thiết kế chi tiết](design.md): phạm vi, kiến trúc, DB, API, T0–T3, bảo mật, recovery, kiểm thử và các quyết định còn mở.
- [Kế hoạch và checklist triển khai](tasks/README.md): thứ tự, phụ thuộc, tiêu chí nghiệm thu và evidence cho từng task.
- [[digitalbanking-docs/docs-backend/modules/_overview/04-transfer.md]]: overview cũ; phần key/schema và một số mã lỗi cần đồng bộ theo `04`/`07`, xem TKCT §1.4.

**Hiện trạng rà soát 15/11/2026:** package Transfer và các module nghiệp vụ mới là khung `.gitkeep`; `V1__init_schema.sql` chỉ tạo schema `banking`. Common đã có các primitive và thành phần HTTP có thể tái sử dụng, nhưng chưa chứng minh integration với Transfer. Không có task nghiệp vụ nào trong checklist được đánh dấu hoàn thành chỉ từ thiết kế hoặc common. Mốc W4 là kế hoạch, không phải báo cáo tiến độ. Chi tiết: [TKCT §1.3](design.md).

## Change Log

| Version | Ngày | Người cập nhật | Mô tả |
|---|---|---|---|
| 1.0 | 15/11/2026 | Codex | Tạo chỉ mục thiết kế và kế hoạch; ghi hiện trạng sau rà soát source. |
