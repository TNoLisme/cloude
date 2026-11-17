# Module `transfer` — Chỉ mục

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-TRANSFER-00`
> **Phiên bản:** v1.4
> **Ngày cập nhật:** 17/11/2026
> **Trạng thái:** Draft — chờ review
> **Owner:** Vũ
> **Reviewer:** Backend Lead, Account/Ledger/Identity, QA, Security — chờ review
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] §2.4, §2.13; [[digitalbanking-docs/docs-common/06. System architecture.md]] §6–§8; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §12.

`transfer` điều phối chuyển tiền giữa các tài khoản trong cùng ngân hàng mô phỏng; chịu trách nhiệm vòng đời transfer, idempotency và phục hồi PENDING. Module phối hợp với account, identity, ledger, limit, audit, fraud, notification và common qua internal API. Nó không sở hữu số dư, ledger hay cơ chế xác thực của các module đó.

- [Thiết kế chi tiết](design.md): phạm vi, kiến trúc, DB, API, T0–T3, bảo mật, recovery, kiểm thử và các quyết định còn mở.
- [Kế hoạch và checklist triển khai](tasks/README.md): thứ tự, phụ thuộc, tiêu chí nghiệm thu và evidence cho từng task.
- [BE-TRF-06](tasks/BE-TRF-06.md): recovery, reconciliation, purge, runbook và giới hạn activation của candidate.
- [BE-TRF-07](tasks/BE-TRF-07.md): candidate security/context/audit, metrics/alerts và mapping outbox T2. [Evidence kiểm thử](tasks/evidence/BE-TRF-07-candidate.md). Nghiệm thu provider và T3 thật còn Blocked.
- [[digitalbanking-docs/docs-backend/modules/_overview/04-transfer.md]]: overview cũ; phần key/schema và một số mã lỗi cần đồng bộ theo `04`/`07`, xem TKCT §1.4.

**Hiện trạng 16/11/2026:** `TransferCreateInput` là parser candidate với 8 test đạt về shape/UTF-8, giới hạn byte, header và validation; chưa được nối endpoint và chưa thực thi policy từ chối PII. Transfer có domain/value objects và các primitive `TransferRequestHash`, `IdempotencyDecision`, `StepUpAmountPolicy`, `IdempotencyRetention`; chúng chưa nối runtime. `TransferPreflightCandidate` dùng payload immutable, tự hash và kiểm tra binding command T1; có 11 test fake; `TransferIntentCandidate` có 10 test fake; `TransferSettlementCandidate` có 19 test fake, gồm nhánh recovery tham gia transaction `MANDATORY`. Năm test T2 PostgreSQL, gồm rollback-only recovery, dùng provider và dữ liệu synthetic; mười hai test migration và tám test JPA PostgreSQL dùng fixture provider synthetic, kèm hai test boundary ArchUnit có fixture âm. Lượt `check` có 298 test backend, gồm 95 test Transfer (70 unit/fake + 25 PostgreSQL), không failure/error/skip theo [evidence](tasks/evidence/BE-TRF-04-05-candidate.md). JPA mapping/terminal adapter (`transfercandidate.persistence`) là candidate, `MANDATORY`, throw khi lệch fence và không detach entity provider; các candidate chưa đăng ký runtime, không có provider adapter thật, chưa hoàn chỉnh recovery, xử lý commit chưa rõ, bảo mật/PII hay money path. Account-number resolution vẫn phụ thuộc provider Account thật; ownership, authorization và kiểm tra bảo mật/PII cần được tích hợp và xác minh trước activation. Migration ứng viên vẫn ngoài Flyway production và `V1__init_schema.sql` production chỉ tạo schema `banking`. Evidence synthetic không chứng minh tích hợp provider thật, nghiệm thu hay release; xem [BE-TRF-02](tasks/BE-TRF-02.md), [BE-TRF-04/05](tasks/evidence/BE-TRF-04-05-candidate.md) và [TKCT §1.3](design.md).

## Change Log

| Version | Ngày | Người cập nhật | Mô tả |
|---|---|---|---|
| 1.0 | 15/11/2026 | Codex | Tạo chỉ mục thiết kế và kế hoạch; ghi hiện trạng sau rà soát source. |
| 1.1 | 16/11/2026 | Codex | Cập nhật hiện trạng T1/T2 candidate, primitive độc lập và giới hạn evidence. |
| 1.2 | 16/11/2026 | Codex | Đồng bộ preflight, T2/recovery candidate và phạm vi kiểm thử synthetic. |
| 1.3 | 16/11/2026 | Codex | Ghi nhận JPA mapping/terminal adapter candidate, boundary ArchUnit và evidence `check` đầy đủ (298 test, 95 Transfer); provider/integration vẫn Blocked. |
| 1.4 | 17/11/2026 | Codex | Thêm chỉ mục đặc tả BE-TRF-07; không thay trạng thái implementation hoặc sign-off. |
