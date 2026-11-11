# BE-NOT-01 — Outbox, delivery và simulator

> **Dự án:** Digital Banking MVP  
> **Mã tài liệu:** BE-MOD-NOTIFICATION-01; **Task:** BE-NOT-01  
> **Phiên bản:** 0.2; **Ngày:** 11/11/2026  
> **Trạng thái:** Draft — chờ review; **Implementation:** Planned; **Verification:** Not verified  
> **Người đề xuất / Owner:** Thịnh; **Reviewer đề xuất:** Vũ (transfer), Đạt (identity), Thắng (hiển thị kết quả); chưa có approval  
> **Nguồn:** [[digitalbanking-docs/docs-common/04. Database design & analysis.md]] §4.7.3; [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] §5.4, §10.4, §12.5; [[digitalbanking-docs/docs-backend/deployment/configuration.md]] §5; [[digitalbanking-docs/docs-common/09. Test plan.md]] §6.

## 1. Phạm vi, yêu cầu và cách làm trước

FR-041–043, AC-025, NFR-008: thông báo kết quả giao dịch, delivery mô phỏng và lỗi gửi không đảo tiền. Thịnh đề xuất contract; có thể code producer/worker với fake caller trước khi transfer/identity hoàn thành. Chỉ caller thật mới chứng minh end-to-end.

Trong phạm vi: T2 enqueue, T3 worker, simulator thành công/lỗi/timeout, retry/recovery, payload masking. Ngoài phạm vi: SMS/email thật, template management, inbox REST, sửa transfer transaction. Security alert và OTP boundary ở §5.2–5.3; transfer notice không thay OTP dispatch.

## 2. Interface và file dự kiến — proposal v0.1

Root source `digitalbanking-backend/src/main/java/com/simulatedbank/banking/notification/`; tests dưới package tương ứng.

| File | Trách nhiệm |
|---|---|
| `api/NotificationOutboxPort.java`, `api/dto/TransferNotice.java` | Caller contract; không nhận ORM entity |
| `application/EnqueueNotificationService.java` | Validate và insert trong transaction caller, `MANDATORY` |
| `application/NotificationOutboxWorker.java` | Claim, gửi ngoài DB transaction, complete theo lease |
| `domain/RetryPolicy.java` | Backoff và exhausted attempts |
| `application/port/NotificationGateway.java` | Gateway adapter contract |
| `adapter/out/NotificationJdbcRepository.java` | SQL insert/claim/complete |
| `adapter/out/SimulatedNotificationGateway.java` | In-memory simulator, không gửi dữ liệu ngoài process |

```java
public interface NotificationOutboxPort {
    void enqueueTransferNotificationsInT2(List<TransferNotice> notices);
}
public record TransferNotice(
    long transferId, long customerId, String reference,
    String templateCode, String channel, String transferStatus,
    BigDecimal amount, String currency, String maskedAccount,
    BigDecimal balanceAfter, String correlationId) {}
```

Caller chuẩn bị recipient customer IDs qua owner ports, không truyền entity. Một notice cho một recipient/template/channel. `balanceAfter` có thể null cho transfer FAILED; không dựng số dư giả. `amount` không âm, VND, scale tối đa 2; status terminal COMPLETED/FAILED; templateCode allowlist; collection defensive-copy. DTO và method name là proposal, giữ nhỏ để đổi sau owner review.

## 3. Schema, lease và claim SQL — proposal

Giữ schema baseline `04 §4.7.3`: BIGINT `notification_id`, `customer_id`, `notification_type`, `payload_masked`, `retry_count`; status `PENDING/SENT/FAILED/RETRYING/CANCELLED`. Không dùng `user_id`, `payload`, `attempt_count`, `PROCESSING` từ overview như DB contract.

**NOT-P01 lease fields:** `locked_by`, `locked_until`, `claim_version` là schema proposal; cần migration mới và source review trước tích hợp. **NOT-P02 dedupe:** partial unique `(transfer_id, customer_id, notification_type, channel, template_code)`; recipient nằm trong key để hai recipient không loại nhau. `ON CONFLICT` chỉ target đúng constraint.

```sql
WITH candidates AS (
    SELECT notification_id
    FROM banking.notification
    WHERE status IN ('PENDING', 'RETRYING')
      AND retry_count < :max_attempts
      AND (next_retry_at IS NULL OR next_retry_at <= now())
      AND (locked_until IS NULL OR locked_until <= now())
    ORDER BY created_at, notification_id
    FOR UPDATE SKIP LOCKED
    LIMIT :batch_size
)
UPDATE banking.notification n
SET status = 'RETRYING', locked_by = :worker_id,
    locked_until = now() + make_interval(secs => :lease_seconds),
    claim_version = n.claim_version + 1,
    retry_count = n.retry_count + 1, updated_at = now()
FROM candidates c
WHERE n.notification_id = c.notification_id
RETURNING n.*;
```

Claim chỉ khi retry_count < max_attempts. Hết budget chỉ mark FAILED khi không còn lease active. Completion phải match notification_id, worker, claim_version, status và lease. Update 0 rows nghĩa worker mất ownership; bỏ kết quả cũ. Hết attempts có thể không gửi được notification. Dùng DB time cho lease; không giả clock ứng dụng đã advance DB `now()`.

## 4. Transaction và gateway failure

1. Transfer tạo notice terminal sau money decision, trước audit cuối T2.
2. Enqueue PENDING cùng T2; audit/step khác fail thì outbox insert rollback.
3. Worker claim committed rows, commit lease trước gọi gateway.
4. Gateway call ngoài DB transaction, không giữ row lock.
5. Update SENT/sent_at hoặc RETRYING/backoff; hết budget thành FAILED với safe failure code.

Enqueue failure trong T2 propagate/rollback; chỉ delivery failure sau commit là best-effort. Không catch enqueue failure rồi báo COMPLETED. Gateway exception body/credentials không lưu. Không thêm public REST endpoint; không tự đưa internal `NOTIFICATION_FAILED` ra transfer response.

## 5. Identity và OTP boundary

### 5.1. Security alert

Đề xuất `enqueueSecurityAlert(customerId, templateCode, safeFields, correlationId)`; allowlist field/template. Nếu enqueue không được phép rollback security state, identity gọi sau commit và giữ log/metric khi enqueue fail. Contract này cần Đạt review; không claim alert guaranteed delivery.

### 5.2. OTP

Identity sở hữu OTP generation/hash/TTL/attempt/challenge. Hash không thể khôi phục plaintext OTP. OTP simulator nhận one-time value trực tiếp trong memory gateway; plaintext không được lưu outbox, DB, audit, log hay file. Resend gọi identity để áp dụng challenge policy; generic worker không replay OTP cũ. Nếu OTP dispatch bắt buộc mà fail, không báo auth success. Identity integration chưa sẵn sàng thì ghi Not verified.

## 6. Delivery semantics và vận hành

Outbox delivery **at-least-once attempt**, không phải guaranteed success và không phải exactly-once. Gateway có thể gửi thành công rồi worker crash trước khi ghi SENT; retry có thể duplicate. Đây là accepted MVP limitation; duplicate notification không được đảo transfer/ledger. Delivery key chỉ giảm duplicate nếu gateway/provider thật hỗ trợ idempotency; simulator không chứng minh SMS/email dedupe.

Polling tạo latency theo poll interval, backlog, batch/capacity và gateway latency; không hứa gửi tức thời. Verification ghi enqueue-to-send p50/p95/p99 và backlog age cùng runtime config. Các giá trị polling/batch/lease/timeout/backoff lấy từ `deployment/configuration.md` nếu có; nếu chưa chốt thì ghi proposal, không tự tạo config chính thức. Không tăng poll/worker count trước khi đo DB contention và gateway capacity.

Theo audit design, retry thường ghi technical state/log, không audit từng retry. Giữ correlationId trên row/log, không metric tag. Đo sent/failure/exhausted/backlog; không log PII/OTP.

## 7. Thứ tự code và acceptance

- [ ] Unit `NotificationPayloadTest`, `RetryPolicyTest`: field allowlist, PII, amount, backoff, final attempt.
- [ ] Migration/JDBC + `NotificationOutboxIT`: T2 rollback zero row; hai recipient hai row; dedupe đúng scope.
- [ ] `NotificationLeaseIT`: hai connections không claim cùng row; lease expiry/reclaim; stale worker không complete claim mới.
- [ ] `NotificationDeliveryTest`: success/failure/timeout; gateway chạy ngoài DB transaction; success-then-crash chứng minh duplicate window.
- [ ] `NotificationTransferIT`: T-OUT-01 gateway fail không đổi money state; T-OUT-02 crash/recovery. Chưa có transfer thật thì chỉ claim component verified.
- [ ] `NotificationIdentityIT`: OTP secret không persistence/log; fail dispatch không báo auth success.
- [ ] Load/latency test ghi config, backlog, enqueue-to-send và DB contention; không có SLO thì chỉ report measurements, không claim pass/fail capacity.

Lệnh dự kiến từ Backend: `./gradlew test --tests '*notification.*'`. Tests chưa có; chưa chạy. Kết quả thực ghi [report](BE-NOT-01-verification-report.md).

## Proposal và handoff

NOT-P01/P02/P03/P04 là proposal chưa được duyệt. Thịnh triển khai core với fake/test adapters; trước khi ghép Vũ/Đạt review DTO, recipient, schema, error behavior và OTP boundary. Giữ duplicate/failure limitation trong README/report; không hứa exactly-once.

## Change log

| Version | Ngày | Nội dung |
|---|---|---|
| 0.1 | 11/11/2026 | Thịnh đề xuất outbox, lease, retry và OTP boundary |
| 0.2 | 11/11/2026 | Ghi rõ at-least-once duplicate/failure và polling latency |
