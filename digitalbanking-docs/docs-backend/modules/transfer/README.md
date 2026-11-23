# Module `transfer` — Chỉ mục

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-MOD-TRANSFER-00`
> **Phiên bản:** v1.5
> **Ngày cập nhật:** 19/11/2026
> **Trạng thái:** T0/T1 đã ghép theo BE-INT-05. T2 Account/Ledger/Limit đã ghép theo BE-INT-06. Fraud thật, Audit C2→C1 và C3 fallback đã được xác minh theo BE-INT-07. Kích hoạt production còn phụ thuộc BE-INT-08/10. [Kiểm chứng Fraud và Audit](../../workofteam/integration%20modules/BE-INT-07-verification.md)
> **Owner:** Vũ
> **Reviewer:** Backend Lead, Account/Ledger/Identity, QA, Security — chờ review
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/03. SRS.md]] §2.4, §2.13; [[digitalbanking-docs/docs-common/06. System architecture.md]] §6–§8; [[digitalbanking-docs/docs-backend/00. architecture & conventions.md]] §12.

`transfer` điều phối chuyển tiền giữa các tài khoản trong cùng ngân hàng mô phỏng; chịu trách nhiệm vòng đời transfer, idempotency và phục hồi PENDING. Module phối hợp với account, identity, ledger, limit, audit, fraud, notification và common qua internal API. Nó không sở hữu số dư, ledger hay cơ chế xác thực của các module đó.

- [Thiết kế chi tiết](design.md): phạm vi, kiến trúc, DB, API, T0–T3, bảo mật, recovery, kiểm thử và các quyết định còn mở.
- [Kế hoạch và checklist triển khai](tasks/README.md): thứ tự, phụ thuộc, tiêu chí nghiệm thu và evidence cho từng task (`BE-TRF-01`..`BE-TRF-09`).
- [BE-TRF-01](tasks/BE-TRF-01.md) & [BE-TRF-02](tasks/BE-TRF-02.md): chốt hợp đồng liên module, candidate DDL (`db/transfer-candidate/V2__transfer.sql` → `V7` khi tích hợp), domain state machine và JPA persistence adapter.
- [BE-TRF-03](tasks/BE-TRF-03.md) & [BE-TRF-04](tasks/BE-TRF-04.md): HTTP boundary (`TransferController`, `TransferCreateService`, `TransferQueryService`), JCS request hash, idempotency, `TransferCreateOrchestrator` và UUID challenge binding (`DEC-TRF-016`, `DEC-TRF-017`).
- [BE-TRF-05](tasks/BE-TRF-05.md) & [BE-TRF-06](tasks/BE-TRF-06.md): T2 settlement candidate (`TransferSettlementCandidate`), recovery/reconciliation/purge (`TransferRecoveryCandidate`, `TransferRecoveryJob`) và runbook vận hành.
- [BE-TRF-07](tasks/BE-TRF-07.md): candidate security/context/audit (`TransferStaffScope`), metrics/alerts (`TransferTelemetry`) và mapping outbox T2 (`TransferNotifications`). [Evidence kiểm thử](tasks/evidence/BE-TRF-07-candidate.md).
- [[digitalbanking-docs/docs-backend/modules/_overview/04-transfer.md]]: tài liệu tổng quan module Transfer (đã đồng bộ với `04`, `07` và `DEC-TRF-001..017`).

**BE-INT-05 (19/11/2026).** `TransferCreateIntegrationConfiguration` nối POST Transfer với Customer, Account, Limit, Identity, Audit và persistence V13/V20. HTTP retry tra global key trước preflight. T0 dispatch sau commit. T1 commit intent, key, reservation, consume/attach nếu cần và C1 trước handoff. [Chi tiết task](../../workofteam/integration%20modules/BE-INT-05.md)

**Hiện trạng (18/11/2026):** Toàn bộ các thành phần ứng viên `BE-TRF-01..07` đã được triển khai và kiểm chứng độc lập trong `com.simulatedbank.banking.transfer` và `com.simulatedbank.transfercandidate` (ngoài component-scan mặc định cho đến khi hoàn tất wiring với các provider thật):
- **Domain & Persistence (`BE-TRF-02`):** Value objects, state machine, `TransferRequestHash`, `IdempotencyDecision`, `StepUpAmountPolicy`, `IdempotencyRetention`, `TransferReferenceGenerator`, SQL primitives (`TransferPersistence`), JPA adapter (`transfercandidate.persistence`) và migration candidate `db/transfer-candidate/V2__transfer.sql` (12 migration tests + 8 JPA PostgreSQL tests).
- **HTTP Boundary & Query (`BE-TRF-03`, `DEC-TRF-016`):** `TransferCreateInput` (10 tests), `TransferController`, `TransferCreateService`, `TransferQueryService`, `SecurityDenyAudit` và 38 unit/slice/OpenAPI contract tests.
- **Preflight, Step-Up, T1 & Orchestrator (`BE-TRF-04`, `DEC-TRF-017`):** `TransferPreflightCandidate` (13 tests), `TransferIntentCandidate` (14 tests), `TransferCreateOrchestrator` (seam điều phối T0/T1/T2 và ánh xạ UUID challenge sang FK nội bộ).
- **T2 Settlement (`BE-TRF-05`):** `TransferSettlementCandidate` (19 unit/fake tests + 5 PostgreSQL transactional tests với synthetic providers).
- **Recovery, Reconciliation & Purge (`BE-TRF-06`):** `TransferRecoveryCandidate` và `TransferRecoveryJob` (quét định kỳ 60 giây, 2-phase claim fence, evidence reconciliation, TTL purge).
- **Security Scope, Telemetry & Notification Outbox (`BE-TRF-07`):** `TransferStaffScope`, `TransferTelemetry`, `TransferNotifications` và bộ quy tắc cảnh báo Ops ([tasks/ops/README.md](tasks/ops/README.md)).

Việc kích hoạt bean production và đóng các task `BE-TRF-02..09` phụ thuộc vào bước ghép tích hợp với các provider thật (`Account`, `Limit`, `Ledger`, `Identity`, `Audit`, `Fraud`, `Notification`) và bằng chứng trên chuỗi Flyway canonical đến V20.

## Change Log

| Version | Ngày | Người cập nhật | Mô tả |
|---|---|---|---|
| 1.0 | 15/11/2026 | Vũ (`transfer`) | Tạo chỉ mục thiết kế và kế hoạch; ghi hiện trạng sau rà soát source. |
| 1.1 | 16/11/2026 | Vũ (`transfer`) | Cập nhật hiện trạng T1/T2 candidate, primitive độc lập và giới hạn evidence. |
| 1.2 | 16/11/2026 | Vũ (`transfer`) | Đồng bộ preflight, T2/recovery candidate và phạm vi kiểm thử synthetic. |
| 1.3 | 16/11/2026 | Vũ (`transfer`) | Ghi nhận JPA mapping/terminal adapter candidate, boundary ArchUnit và evidence `check` đầy đủ (298 test, 95 Transfer). |
| 1.4 | 17/11/2026 | Vũ (`transfer`) | Thêm chỉ mục đặc tả BE-TRF-07. |
| 1.5 | 18/11/2026 | Backend Team | Đồng bộ toàn bộ hiện trạng `BE-TRF-01..07` (`TransferController`, `TransferCreateOrchestrator`, `TransferRecoveryCandidate`, `TransferTelemetry`, `TransferNotifications`) và chuẩn hóa tài liệu. |
