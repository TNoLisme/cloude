# BE-NOT-01 — Transactional outbox, delivery và simulator

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-NOTIFICATION-01; **Task:** BE-NOT-01  
> **Phiên bản:** 0.4; **Ngày:** 14/11/2026
> **Trạng thái:** Common-aligned draft — component implementation có thể làm độc lập; caller/migration/provider/identity còn chờ review
> **Owner:** Thịnh; **Reviewer đề xuất:** Vũ (transfer), Đạt (identity), Phương (common); chưa sign-off  
> **Nguồn:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.7.3; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]; [[digitalbanking-docs/docs-backend/modules/common/CURRENT-STATE.md]]; [[digitalbanking-docs/docs-backend/modules/common/TKCT.md]].

## 1. Mục tiêu và scope

FR-041–043, AC-025, NFR-008: ghi notification result/OTP/security alert với payload masked. Insert outbox trong T2; T3 worker delivery sau commit. Delivery failure/duplicate không đảo transfer/ledger. MVP chỉ claim **at-least-once attempts**, bounded retries và duplicate-send window; không claim exactly-once, guaranteed delivery hoặc provider dedupe.

**Nguồn ưu tiên:** docs-common/01–11 là source contract; design/02 bổ sung concurrency detail; backend module docs chỉ triển khai chi tiết. Conflict giữa source phải ghi owner decision, không tự đổi baseline.

### 1.1. Phần có thể triển khai độc lập

- Immutable `TransferNotice`/masked payload contract.
- Payload allowlist/secret/PII rejection.
- Repository và worker claim/retry component trên disposable PostgreSQL.
- `FOR UPDATE SKIP LOCKED`, lease recovery, stale-worker conditional update.
- Fake simulator success/failure/success-then-crash.
- Metrics/event model không chứa PII.

### 1.2. Phần chờ owner/caller

- Transfer T2 caller thật, recipient resolution và final status: chờ Vũ.
- OTP/challenge integration: chờ Đạt.
- Flyway version/lease schema final: chờ Phương + migration coordination.
- Provider idempotency/delivery guarantee: ngoài bằng chứng simulator.
- Production retry/SLO/resource policy: cần owner approval.

## 2. Common integration

Reuse:
- Common `RequestContextHolder`/correlation IDs; capture immutable context at enqueue, explicitly bind in worker thread.
- `PiiMaskingUtils`/common crypto where policy applies; outbox payload already masked, common utility không thay field allowlist.
- Common `ErrorCode`/Problem chỉ ở HTTP boundary; worker lưu safe internal failure code, không expose exception/provider text.
- Common structured logging/metrics; no OTP/PII/secrets/high-cardinality tags.
- `AppProperties.Jobs.outboxPollIntervalMs=2000`, `outboxBatchSize=50`, `outboxLeaseSeconds=30` hiện tồn tại trong Common config; values là config defaults, chưa phải SLO/retry policy approval. Không sửa Common trong task này.

Không reimplement Common context/crypto/error/metrics. CorrelationId không tự propagate qua async boundary; persist trong outbox và bind vào worker explicitly.

## 3. Port/schema proposal

### 3.1. Port boundary

```java
NotificationOutboxPort.enqueueTransferNotificationsInT2(List<TransferNotice> notices)
```

Đây là proposal, không phải public port đã duyệt. DTO immutable, không nhận ORM entities. Transfer owner cung cấp recipient `customerId`, transfer ID/reference, outcome, masked display data và correlation ID; notification validate payload, channel/template và ghi outbox. Caller sở hữu T2 transaction; port dùng `Propagation.MANDATORY` hoặc semantics tương đương đã test. Không gateway call trong T2.

**Source conflict:** module overview mô tả port nhận transfer/sourceAccount/destAccount objects; spec này đề xuất DTO list. DTO giảm coupling và test độc lập, nhưng Vũ phải review recipient ownership và shape trước integration; không tự đổi port của Transfer.

### 3.2. DB baseline từ `04 §4.7.3`

| Field | Contract baseline |
|---|---|
| `notification_id` | BIGINT identity PK |
| `customer_id` | NOT NULL FK customer |
| `transfer_id`, `auth_challenge_id` | Nullable FK transfer/challenge |
| `notification_type` | `TRANSFER_RESULT`, `OTP_DELIVERY`, `SECURITY_ALERT` |
| `channel` | `IN_APP`, `EMAIL`, `SMS` |
| `template_code` | VARCHAR(32), NOT NULL |
| `payload_masked` | JSONB NOT NULL; không full PII/secret |
| `status` | `PENDING`, `SENT`, `FAILED`, `RETRYING`, `CANCELLED`; default `PENDING` |
| `retry_count`, `next_retry_at`, `failure_code`, `sent_at` | Retry/delivery state; FAILED cần failure code; SENT cần sent timestamp |
| `correlation_id`, `created_at`, `updated_at` | Trace và timestamps |

Indexes và constraints theo 04 §4.7.3. Foreign key đến customer/transfer/challenge phải coordinate migration order; schema fixture không chứng minh FK integration thật.

### 3.3. Claim/lease conflict và hướng đề xuất

- `04` không có `PROCESSING`, `locked_by`, `locked_until`, `claim_version` hoặc dedupe unique key.
- design/02 §5.4 minh họa claim bằng `RETRYING`, `next_retry_at`, `retry_count`; worker commit claim rồi send ngoài transaction. Notification overview lại dùng `PROCESSING` + `locked_by`/`locked_until`, khác status baseline.
- **Proposal:** giữ status enum 04; thêm lease token/version và expiry như schema extension có review, dùng conditional update theo token để stale worker không overwrite claim mới. `locked_by`/`locked_until`/`claim_version` là ví dụ implementation, không phải baseline; chọn tối thiểu sau test failure/reclaim/stale completion. Không thêm `PROCESSING` như contract đã duyệt.
- `RETRYING` biểu thị retry/claim theo source design, nhưng tách trạng thái nghiệp vụ với lease là proposal cần Phương/Vũ review. Không tự quyết định `retry_count` tăng khi claim hay khi gateway failure; source/example khác semantics attempts và cần chốt.
- Dedupe `(transfer_id, customer_id, notification_type, channel)` hoặc key khác là **proposal**, không phải constraint đã có trong 04. Provider key `transfer_id:notification_type:channel` từ design/02 có thể va chạm nếu cùng transfer có nhiều recipients; owner phải xác nhận recipient/channel/type uniqueness trước dùng. Provider dedupe chỉ có hiệu lực nếu provider hỗ trợ.
- Migration version, FK dependencies, schema grants và extension cần Phương coordinate. Không đụng migration của Audit agent.

## 4. Delivery semantics và worker transaction

Baseline consistency từ design/02 §5.4/§5.5, §10.4, §A.13/§B.6:

1. Claim một batch có giới hạn bằng `FOR UPDATE SKIP LOCKED` trong transaction ngắn. `AppProperties.Jobs.outboxBatchSize` hiện mặc định 50; đó là cấu hình hiện hữu, không phải giới hạn nghiệp vụ đã phê duyệt. Eligible rows theo baseline claim example: `PENDING` hoặc `RETRYING` đến hạn; expired lease rows phải theo cơ chế được chốt.
2. Commit claim/lease trước gateway call.
3. Gọi simulator/provider ngoài DB transaction và không giữ row lock/connection trong suốt network call.
4. Cập nhật outcome trong transaction riêng, conditional trên claim token/version còn sở hữu. Stale worker không được ghi đè kết quả claim mới.
5. Thành công: `SENT`, `sent_at`, clear lease.
6. Thất bại: safe `failure_code`, retry schedule bounded; max attempt đạt thì `FAILED` + metric/alert. Không persist exception/provider response text.
7. Worker fail/crash sau gateway success nhưng trước SENT có thể gửi trùng. Chỉ claim at-least-once; không exactly-once.
8. Polling delay phụ thuộc interval/backlog/capacity/latency; không claim immediate delivery.

### 4.1. Retry policy proposal

`04 §4.7.3` chỉ yêu cầu retry/backoff, không chốt max attempts/backoff schedule. `design/02` minh họa 1 phút initial claim/retry và max theo config; notification overview có 30s lease, timeout 5s, max 5, backoff 5/10/20/40s. Các giá trị backend overview/Common config là implementation proposals, không thay baseline.

**Proposal cho component tests:** inject `Clock`/`RetryPolicy`; test deterministic schedule và bounded terminal state. Không hard-code production policy hoặc assert SLO cho đến Phương/architecture chốt config/retry/lease/dead-letter/operator recovery.

### 4.2. T2/T3 failure invariants

- T2 enqueue failure: throw system failure; entire caller T2 rolls back, gồm money/ledger/transfer terminal/outbox/audit theo consistency design. Không swallow DB exception trong T2.
- T3 gateway/DB update failure: không sửa transfer/ledger/audit đã commit. Retry/FAILED chỉ đổi outbox state.
- Notification insert nằm trong T2 nhưng vị trí tương đối với terminal/idempotency/audit phải tuân thứ tự `design/02 §5.3`; audit insert là cuối cùng. Module này không tự áp ordering thay Transfer owner.
- Không gọi scheduler/gateway trong T2.

## 5. OTP/security boundary

Identity owns OTP/challenge, TTL, attempts, resend and authentication result. OTP plaintext must not persist in outbox, DB, logs or files. Không khẳng định masked reference một mình đủ để gateway gửi OTP: hash không thể khôi phục OTP. **Đề xuất cần Đạt/Security chốt:** identity cung cấp OTP qua kênh transient an toàn tới gateway, giữ TTL và báo lỗi dispatch theo contract; không đưa OTP vào generic persistent worker. Cần quyết định lifetime, process crash/retry, simulator output và cách xác nhận dispatch thành công mà không lộ OTP. Notification không verify OTP, quyết định auth success hoặc tạo session. Chưa có integration này nên FR-042/043 remain Not verified/Blocked; simulator-only tests không chứng minh OTP flow.

## 6. PII/payload contract

Payload allowlist is notification-owned at application boundary but source masking rules come from SRS §2.13.4 and `04 §4.7.3`. Transfer result may include only needed masked reference/amount/currency/masked account/balanceAfter; OTP/security payload must exclude OTP, password, token, full account/phone/email and free text. Store correlation ID separately. Do not log payload or provider exception text.

## 7. Tests and acceptance

### Independent component evidence

- Unit: immutable notice, channel/type allowlists, payload masking/secret rejection, correlation required, retry schedule, terminal transition and idempotency key derivation.
- PostgreSQL Testcontainers: T2 rollback leaves zero outbox rows; two independent claims do not claim same row; lease expiry/reclaim; stale completion rejected; batch bound; conditional SENT/FAILED; retry/backoff/max attempts; no write to transfer/ledger on T3 failure.
- Fake simulator: success, timeout, failure, success-then-crash-before-SENT; prove duplicate window without claiming exactly-once.
- Synthetic artifact/log scan: no OTP/PIN/password/token/full PII.

### Blocked integration evidence

- Transfer caller T2 recipient/final-status mapping: blocked until Vũ supplies port/fixture.
- Identity OTP flow: blocked until Đạt supplies challenge/notification contract.
- Provider idempotency/delivery: fake simulator cannot prove provider behavior.
- Production resource/SLO/polling latency: measure only after config/owner thresholds.

No task completion claim until component evidence is fresh and integration blockers are explicitly reported.

## 8. Owner decision register

| Owner | Cần chốt | Hướng đề xuất | Lý do / evidence cần review |
|---|---|---|---|
| Vũ — Transfer | `TransferNotice` shape, recipient selection, T2 insert order, business FAILED notification | DTO immutable, caller resolve customer/transfer/outcome; notification chỉ validate/enqueue | Giữ module ownership; T2 rollback/replay tests và transfer integration |
| Đạt — Identity | OTP challenge linkage, delivery handoff/expiry/resend | Không persist OTP plaintext; identity owns TTL/attempt/success | FR-042/043 cần real identity integration và artifact/log scan |
| Phương — Common/DB | Migration version/FKs, lease token/version, dedupe constraint, DB role | Giữ baseline enum/columns; extension proposal tối thiểu sau concurrency tests | 04 thiếu lease columns; stale completion/reclaim/dedupe DB tests |
| Architecture/Security | Retry/lease/backoff/resource/SLO và provider duplicate semantics | Configurable bounded attempts; no exactly-once claim | Fake provider không chứng minh delivery guarantee; đo backlog/latency khi policy có target |

Phương xem xét D1/D2/D3 migration/lease/dedupe; Vũ/Đạt chốt caller và OTP; Architecture/Security chốt retry/resource/payload policy. Không coi Common config defaults là approval.

## 9. Phối hợp với Fraud/Audit agents

NOT agent sở hữu source/tests/docs notification, không sửa Fraud/Audit/Common/docs-common/OpenAPI. Trao đổi DTO/transaction assumptions qua handoff; migration version và shared Gradle build/test outputs cần điều phối, không stage/commit/push hoặc thay index có sẵn. Không khẳng định AUD-01 đã Done hoặc public `AuditModuleApi` đã approved.

## 10. Change log

| Version | Date | Change |
|---|---|---|
| 0.1 | 11/11/2026 | Outbox proposal |
| 0.2 | 11/11/2026 | Duplicate/failure and polling latency limits |
| 0.3 | 12/11/2026 | Aligned Common context/security/config ownership |
| 0.4 | 14/11/2026 | Reconciled Common/01–11 contracts; resolved port/schema/status conflicts as proposals; separated independent component scope and owner decisions |
