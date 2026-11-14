# BE-NOT-01 — Transactional outbox, delivery và simulator

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-NOTIFICATION-01; **Task:** BE-NOT-01  
> **Phiên bản:** 0.3; **Ngày:** 12/11/2026  
> **Trạng thái:** Draft — chờ review; implementation chưa bắt đầu  
> **Owner:** Thịnh; **Reviewer đề xuất:** Vũ (transfer), Đạt (identity), Phương (common); chưa sign-off  
> **Nguồn:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.7.3; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]].

## 1. Mục tiêu và scope

T2 enqueue notification trong transaction tiền; T3 worker gửi sau commit. Delivery failure/duplicate không được đảo transfer/ledger. MVP at-least-once attempts, retries bounded; có thể hết retry chưa gửi hoặc gửi trùng. Polling latency không phải immediate delivery.

## 2. Common integration

Reuse:
- Common `RequestContextHolder`/correlation IDs; capture immutable context at enqueue, explicitly bind in worker thread.
- `PiiMaskingUtils`/common crypto where policy applies; outbox payload already masked, common utility không thay field allowlist.
- Common `ErrorCode`/Problem chỉ ở HTTP boundary; worker lưu safe internal failure code, không expose exception/provider text.
- Common structured logging/metrics; no OTP/PII/secrets/high-cardinality tags.
- `AppProperties` + `deployment/configuration.md` cho polling/lease/retry; values remain proposals unless source owner approves.

Không reimplement Common context/crypto/error/metrics. CorrelationId không tự propagate qua async boundary; persist trong outbox và bind vào worker explicitly.

## 3. Port/schema proposal

Port `NotificationOutboxPort.enqueueTransferNotificationsInT2(List<TransferNotice>)` là proposal; DTO không nhận ORM entities. Transfer owner supplies authoritative recipients and final status. No app call to gateway during T2.

DB `notification` follows `04 §4.7.3`; lease/claim_version/dedupe fields are migration proposals and need schema coordination. Worker claims with `FOR UPDATE SKIP LOCKED`, commits lease, sends outside transaction, then conditional completion.

## 4. Delivery semantics/failure

- At-least-once attempts, not guaranteed delivery and not exactly-once.
- Success at gateway then crash before SENT can produce duplicate.
- Finite max attempts can end FAILED without delivery.
- Delivery key helps only when real provider honors idempotency; fake simulator is not evidence of provider dedupe.
- Polling means delay depends on poll interval, backlog, worker capacity and gateway latency.
- No notification failure may reverse committed money state.

Measure enqueue-to-send p50/p95/p99, oldest backlog, exhausted retries and observable duplicates. Do not assert SLO until owners define it.

## 5. OTP/security boundary

Identity owns OTP/challenge, TTL, attempts, resend and authentication result. OTP plaintext must not persist in outbox, DB, logs or files. Do not replay old OTP with generic worker. Identity integration missing means FR-042/043 remain Not verified/Blocked; do not claim from simulator-only test.

## 6. Tests and acceptance

Unit payload/retry; PostgreSQL T2 rollback, lease, concurrency and retry; fake gateway success/failure/success-then-crash; transfer integration for money unchanged; identity integration for OTP secrecy/result. Component and real provider/caller evidence reported separately.

## 7. Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Outbox proposal |
| 0.2 | 11/11/2026 | Duplicate/failure and polling latency limits |
| 0.3 | 12/11/2026 | Aligned Common context/security/config ownership |
