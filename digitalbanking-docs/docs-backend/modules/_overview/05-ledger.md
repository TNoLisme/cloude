# Module 05: `ledger` — Tóm tắt

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-SUM-05`
> **Module:** `com.simulatedbank.banking.ledger`
> **Owner:** Phương; **Phối hợp:** Transfer, Account, DB, API, Audit, Security, QA, Ops

Ledger sở hữu `banking.ledger_entry` theo `04` §4.4.2. Transfer điều phối T2, Account cập nhật số dư. Ledger ghi cặp append-only, seq 1 DEBIT nguồn và seq 2 CREDIT đích, cùng amount/currency, với balance snapshot do Account trả trong T2. Ledger kiểm cặp trước pha audit cuối và cung cấp evidence từng entry để Transfer đối soát. Provider posting BE-LED-03 đã triển khai (DEC-LED-005) với evidence candidate LED-POST-01–14; ràng buộc schema, role DB và atomicity money path thật cần kiểm chứng ở BE-LED-02–04/08.

API-TXN-01/02 theo `07` §8.4 dùng projection do Transfer sở hữu với ownership và masked display từ Account theo DEC-LED-002. Ledger cung cấp dữ kiện bút toán cho đối soát. Đối soát số dư cần baseline opening có provenance do Account sở hữu theo DEC-LED-003.

- [Thiết kế Ledger](../ledger/design.md)
- [BE-LED-01 và oracle](../ledger/tasks/BE-LED-01.md)
- [BE-LED-02 và evidence schema PostgreSQL](../ledger/tasks/evidence/BE-LED-02.md)
- [BE-LED-03 và evidence provider posting](../ledger/tasks/evidence/BE-LED-03.md)
- [Backlog triển khai và nghiệm thu](../ledger/tasks/README.md)
- [[digitalbanking-docs/docs-common/design/05. Modules contract.md]] §9, §12.3 và §13.4
