# Database 02. Locking, Concurrency & SQL Patterns

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DB-02`
> **Phiên bản:** 1.0
> **Ngày:** 07/11/2026
> **Trạng thái:** Hoàn thành
> **Nguồn tham chiếu:** [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]], [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]]

---

## 1. Quy tắc Khóa & Isolation trong PostgreSQL 16

1. **Isolation Level:** Mặc định dùng `READ COMMITTED` kết hợp **Explicit Row-Level Locking** (`SELECT ... FOR UPDATE`). Không bật `SERIALIZABLE` toàn cục.
2. **Thứ tự khóa bắt buộc (Canonical Lock Order):**
   Mọi transaction cần chạm đến nhiều bảng phải lấy khóa theo đúng thứ tự từ trên xuống dưới để triệt tiêu Deadlock:
   ```text
   (1) banking.transfer          -> WHERE transfer_id = :id FOR UPDATE NOWAIT (hoặc SKIP LOCKED)
   (2) banking.auth_challenge    -> WHERE challenge_id = :id FOR UPDATE
   (3) banking.account           -> WHERE account_id IN (:src, :dst) ORDER BY account_id ASC FOR UPDATE
   (4) banking.limit_usage       -> Customer-level row FOR UPDATE -> Account-level row FOR UPDATE
   (5) pg_advisory_xact_lock     -> hashtextextended('audit:chain', 0) — luôn ở bước cuối cùng trước COMMIT
   ```
3. **Xử lý SQLSTATE khi tranh chấp khóa:**
   - `55P03` (`lock_not_available` do `NOWAIT` hoặc hết `lock_timeout = 2s`):
     - Nếu xảy ra khi claim `transfer` trong `handleExisting`: trả về `409 IDEMPOTENCY_IN_PROGRESS` kèm header `Retry-After: 2`.
     - Nếu xảy ra khi chờ khóa `account` / `limit_usage` / `audit`: rollback `T2` (giữ `transfer = PENDING`), retry tối đa 3 lần (backoff 50ms -> 150ms + jitter) ở ngoài transaction; nếu vẫn hết lượt thì trả `503 SERVICE_UNAVAILABLE`.
   - `40P01` (`deadlock_detected`) hoặc `40001` (`serialization_failure`):
     - Rollback `T2`, retry tối đa 3 lần ở ngoài transaction bằng cùng `transferId`.

---

## 2. Danh mục 16 Mẫu SQL Chuẩn (`NamedParameterJdbcTemplate`)

> [!TIP]
> Khuyến nghị dùng trực tiếp các câu SQL dưới đây thông qua `NamedParameterJdbcTemplate` hoặc `JdbcClient` của Spring thay vì để Hibernate tự sinh câu lệnh cho luồng tiền (`T1`/`T2`), đối soát và outbox.

### A.1. Thiết lập Timeout cục bộ đầu Transaction `T2`
Chỉ có hiệu lực trong phạm vi transaction hiện tại (`SET LOCAL`), tự động reset khi commit/rollback:
```sql
SET LOCAL lock_timeout = '2s';
SET LOCAL statement_timeout = '5s';
SET LOCAL idle_in_transaction_session_timeout = '60s';
```

### A.2. Claim `transfer` bằng `FOR UPDATE NOWAIT` (Đầu `T2` hoặc Resume)
```sql
SELECT transfer_id, status, customer_id, source_account_id, destination_account_id,
       step_up_challenge_id, amount, currency, reference_no, correlation_id
FROM banking.transfer
WHERE transfer_id = :transferId
FOR UPDATE NOWAIT;
```
- Bắt lỗi SQLSTATE `55P03` -> đang có worker khác chạy `T2` -> trả `409 IDEMPOTENCY_IN_PROGRESS`.
- Nếu `status IN ('COMPLETED', 'FAILED')` -> đọc `idempotency_record` và replay kết quả.

### A.3. Khóa hai tài khoản theo thứ tự `account_id ASC` (Chống Deadlock A↔B)
```sql
SELECT account_id, customer_id, status, available_balance, ledger_balance,
       account_no_last4, currency
FROM banking.account
WHERE account_id IN (:sourceId, :destinationId)
ORDER BY account_id ASC
FOR UPDATE;
```

### A.4. Trừ tiền (Debit) và Cộng tiền (Credit) có điều kiện bảo vệ
```sql
-- 1. Debit tài khoản nguồn (chỉ trừ khi ACTIVE và đủ số dư)
UPDATE banking.account
SET available_balance = available_balance - :amount,
    ledger_balance    = ledger_balance - :amount,
    updated_at        = now()
WHERE account_id = :sourceId
  AND status = 'ACTIVE'
  AND available_balance >= :amount
RETURNING available_balance, ledger_balance;

-- 2. Credit tài khoản đích (chỉ cộng khi ACTIVE)
UPDATE banking.account
SET available_balance = available_balance + :amount,
    ledger_balance    = ledger_balance + :amount,
    updated_at        = now()
WHERE account_id = :destinationId
  AND status = 'ACTIVE'
RETURNING available_balance, ledger_balance;
```
- Nếu câu Debit không trả về dòng nào (row count = 0): kiểm tra trạng thái từ bước A.3 để kết luận `ACCOUNT_INACTIVE` hay `INSUFFICIENT_BALANCE`.

### A.5. Ghi cặp bút toán Sổ cái (Double-Entry Ledger)
```sql
INSERT INTO banking.ledger_entry
  (transfer_id, account_id, entry_seq, entry_type, amount, currency,
   balance_after, correlation_id)
VALUES
  (:transferId, :sourceId, 1, 'DEBIT', :amount, 'VND',
   :sourceBalanceAfter, :correlationId),
  (:transferId, :destinationId, 2, 'CREDIT', :amount, 'VND',
   :destinationBalanceAfter, :correlationId);
```

### A.6. Insert-First cho `idempotency_record` trong `T1`
```sql
INSERT INTO banking.idempotency_record
  (idempotency_key, request_hash, transfer_id, status, expires_at)
VALUES
  (:key, :requestHash, :transferId, 'RECEIVED', :expiresAt)
ON CONFLICT (idempotency_key) DO NOTHING
RETURNING idempotency_key;
```
- Nếu không trả về dòng nào -> có request trùng `idempotency_key` vừa thắng race -> ném `IdempotencyRaceException` để rollback `T1`, sau đó Orchestrator đọc lại bằng câu A.7.

### A.7. Tra cứu `idempotency_record` kèm trạng thái `transfer`
```sql
SELECT ir.idempotency_key, ir.request_hash, ir.status AS idem_status,
       ir.response_status, ir.response_body, ir.transfer_id, ir.expires_at,
       t.customer_id, t.reference_no, t.status AS transfer_status
FROM banking.idempotency_record ir
JOIN banking.transfer t ON t.transfer_id = ir.transfer_id
WHERE ir.idempotency_key = :key;
```

### A.8. Khởi tạo (Upsert) và Khóa `limit_usage` theo thứ tự Customer → Account
```sql
-- Bước 1: Customer-level usage row (account_id IS NULL)
INSERT INTO banking.limit_usage
  (customer_id, account_id, usage_date, currency, used_amount, transfer_count)
VALUES
  (:customerId, NULL, :usageDate, 'VND', 0.00, 0)
ON CONFLICT (customer_id, usage_date) WHERE account_id IS NULL DO NOTHING;

SELECT usage_id, used_amount, transfer_count
FROM banking.limit_usage
WHERE customer_id = :customerId
  AND account_id IS NULL
  AND usage_date = :usageDate
FOR UPDATE;

-- Bước 2: Account-level usage row (chỉ chạy nếu tài khoản nguồn có cấu hình limit riêng)
INSERT INTO banking.limit_usage
  (customer_id, account_id, usage_date, currency, used_amount, transfer_count)
VALUES
  (:customerId, :accountId, :usageDate, 'VND', 0.00, 0)
ON CONFLICT (account_id, usage_date) WHERE account_id IS NOT NULL DO NOTHING;

SELECT usage_id, used_amount, transfer_count
FROM banking.limit_usage
WHERE account_id = :accountId
  AND usage_date = :usageDate
FOR UPDATE;
```

### A.9. Cộng dồn `limit_usage` sau khi ghi nhận hạch toán
```sql
UPDATE banking.limit_usage
SET used_amount    = used_amount + :amount,
    transfer_count = transfer_count + 1,
    updated_at     = now()
WHERE usage_id = :usageId;
```

### A.10. Chuyển `transfer` sang trạng thái Terminal (`COMPLETED` / `FAILED`)
```sql
UPDATE banking.transfer
SET status          = :status,
    completed_at    = CASE WHEN :status = 'COMPLETED' THEN now() ELSE completed_at END,
    failed_at       = CASE WHEN :status = 'FAILED' THEN now() ELSE failed_at END,
    failure_code    = :failureCode,
    failure_message = :failureMessage,
    updated_at      = now()
WHERE transfer_id = :transferId
  AND status = 'PENDING'
RETURNING transfer_id;
```

### A.11. Lưu kết quả Terminal vào `idempotency_record`
```sql
UPDATE banking.idempotency_record
SET status          = :status,
    response_status = :responseStatus,
    response_body   = CAST(:responseBody AS jsonb),
    completed_at    = now()
WHERE idempotency_key = :key
  AND status IN ('RECEIVED', 'IN_PROGRESS')
RETURNING idempotency_key;
```

### A.12. Đẩy thông báo vào Transactional Outbox (`banking.notification`) trong `T2`
```sql
INSERT INTO banking.notification
  (customer_id, transfer_id, notification_type, channel, template_code,
   payload_masked, status, correlation_id)
VALUES
  (:customerId, :transferId, 'TRANSFER_RESULT', :channel, :templateCode,
   CAST(:payload AS jsonb), 'PENDING', :correlationId);
```

### A.13. Worker Claim thông báo từ Outbox bằng `FOR UPDATE SKIP LOCKED`
```sql
UPDATE banking.notification
SET status        = 'RETRYING',
    next_retry_at = now() + interval '1 minute',
    retry_count   = retry_count + 1,
    updated_at    = now()
WHERE notification_id = (
  SELECT notification_id
  FROM banking.notification
  WHERE status IN ('PENDING', 'RETRYING')
    AND (next_retry_at IS NULL OR next_retry_at <= now())
  ORDER BY created_at ASC
  LIMIT 1
  FOR UPDATE SKIP LOCKED
)
RETURNING notification_id, customer_id, transfer_id, channel, template_code,
          payload_masked, retry_count, correlation_id;
```

### A.14. Job Reconciliation tìm và khóa giao dịch `PENDING` bị kẹt
```sql
SELECT transfer_id
FROM banking.transfer
WHERE status = 'PENDING'
  AND created_at < now() - CAST(:staleThreshold AS interval)
ORDER BY created_at ASC
LIMIT 1
FOR UPDATE SKIP LOCKED;
```

### A.15. Khóa tuần tự Chuỗi Hash Audit & Đọc `prev_hash` (Cuối `T1` / `T2`)
```sql
-- 1. Lấy transaction-level advisory lock (tự nhả khi COMMIT/ROLLBACK)
SELECT pg_advisory_xact_lock(hashtextextended('audit:chain', 0));

-- 2. Đọc record_hash mới nhất làm prev_hash (nếu rỗng thì dùng 64 số '0')
SELECT record_hash
FROM banking.audit_log
ORDER BY audit_id DESC
LIMIT 1;
```

### A.16. Kiểm tra tính toàn vẹn Sổ cái của một `transfer` (Dùng cho Reconciliation & Test)
```sql
SELECT t.transfer_id, t.status, COUNT(le.entry_id) AS entries,
       COALESCE(SUM(CASE WHEN le.entry_type = 'DEBIT'  THEN le.amount ELSE 0 END), 0) AS debit_sum,
       COALESCE(SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE 0 END), 0) AS credit_sum
FROM banking.transfer t
LEFT JOIN banking.ledger_entry le ON le.transfer_id = t.transfer_id
WHERE t.transfer_id = :transferId
GROUP BY t.transfer_id, t.status;
```
