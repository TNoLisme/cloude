# customer

> Baseline thiết kế `dev2@56210cc` (16/11/2026); BE-CUS-IMP-01/02/03 đã có implementation và PostgreSQL evidence trên branch `datht-backend-rework`.

- [Thiết kế chi tiết và phụ thuộc](design.md)
- [Task breakdown và checklist](tasks/README.md)
- [BE-CUS-IMP-01 — Flyway migration `banking.customer`](BE-CUS-IMP-01.md)
- [BE-CUS-IMP-02 — JPA entity, repository và PostgreSQL mapping](BE-CUS-IMP-02.md)
- [BE-CUS-IMP-03 — AES-GCM encrypted PII và masking dùng Common](BE-CUS-IMP-03.md)
- [BE-CUS-IMP-08 — Security/API/Database regression và evidence (PARTIAL)](BE-CUS-IMP-08.md)
- [BE-CUS-IMP-10 — Staff search/full-PII contract gate (P2 BLOCKED)](BE-CUS-IMP-10.md)

Customer sở hữu `banking.customer`, PII hồ sơ và masked customer projection. Identity giữ authentication; Account giữ account/ownership; Admin giữ staff authorization; Audit giữ audit event. Hiện đã có Flyway V5, Customer JPA entity/repository và integration AES-GCM/masking nội bộ cho create/owner-read/field-update; public CustomerQuery/REST thuộc IMP-04/05/06. Cảnh báo: `_overview/02-customer.md` chứa CIF/KYC/national ID/status khác với `docs-common/04` — không tự bổ sung khi triển khai.
