# Module `ledger` — Chỉ mục

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** BE-MOD-LEDGER-00
> **Phiên bản:** v1.1 — 17/11/2026
> **Trạng thái:** Draft — chờ review
> **Owner:** Phương; **Reviewer:** Transfer, Account, DB, API, Audit, Security, QA, Ops

Ledger sở hữu bút toán kép append-only và cung cấp evidence cho đối soát. Transfer điều phối money path; Account sở hữu số dư. Baseline DEC-LED-002 giao projection lịch sử PENDING/FAILED/COMPLETED cho Transfer với facts từ Account.

- [Thiết kế chi tiết](design.md): phạm vi, hiện trạng, workflow, API, database, security và rủi ro.
- [Kế hoạch và checklist](tasks/README.md): công việc, phụ thuộc, tiêu chí nghiệm thu và evidence.
- [BE-LED-03 — Posting](tasks/BE-LED-03.md): contract, domain/persistence, transaction, rollback và vector kiểm thử cho provider Ledger. [Evidence provider](tasks/evidence/BE-LED-03.md) (LED-POST-01–14, candidate).
- [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §9, §12.3, §13.4: contract và điểm chờ review.
- [[digitalbanking-docs/docs-backend/modules/_overview/05-ledger.md]] tóm tắt boundary và dẫn tới các nguồn chính thức.

**Hiện trạng rà soát 17/11/2026:** provider posting BE-LED-03 đã triển khai trong `banking.ledger` (api/domain/application/adapter, MANDATORY, read-back native projection) với 103 test mới LED-POST-01–14 PASS trên PostgreSQL 16 candidate; service/adapter đăng ký tường minh, chưa kích hoạt production. Account vẫn chưa có Java provider; migration production cho `account`/`ledger_entry` chưa có trong chain Flyway (duplicate V2 chờ DB owner). Transfer có candidate và test PostgreSQL dùng fixture giả lập, chưa chứng minh tích hợp provider thật hay money path. Security common đã khai báo matcher cho GET transactions; ownership/scope vẫn cần kiểm chứng. BE-LED-01 có baseline DEC-LED-001–003 và bộ fixture contract; owner review và evidence tích hợp còn chờ. [BE-LED-02](tasks/BE-LED-02.md) có schema candidate và kiểm thử PostgreSQL 16 với fixture FK Account/Transfer. Việc kích hoạt production chờ chain Flyway và migration thật của các owner.
