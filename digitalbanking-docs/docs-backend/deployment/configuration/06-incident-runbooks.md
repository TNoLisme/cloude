# 06. Incident Runbooks

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DEP-CFG-06`
> **Phiên bản:** v2.0
> **Ngày cập nhật:** 09/11/2026
> **Trạng thái:** Planning-level runbook — chưa phải evidence đã diễn tập
> **Nguồn chuẩn:** [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] (§10), [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]], [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]]

> [!caution]
> Runbook không cho phép sửa/xóa dữ liệu chứng cứ, không cho phép báo thành công giả và không cho phép đánh dấu `COMPLETED` khi thiếu ledger/audit evidence.

---

## 0. Nguyên tắc chung

1. Ghi lại `correlation_id`, `reference`, timestamp và alert hiện tại.
2. Bảo toàn log, audit, backup và evidence trước khi thay đổi trạng thái.
3. Không `UPDATE`/`DELETE` `ledger_entry` hoặc `audit_log`.
4. Không trả success cho giao dịch tiền khi outcome chưa chắc chắn.
5. Retry/reconcile chỉ bằng quy trình idempotent và **cùng idempotency key**.
6. Escalate khi không chứng minh được final state.

---

## 1. Danh mục runbook

| Runbook | Tình huống | Alert liên quan |
|---|---|---|
| `R-CON-01` | Transfer kẹt `PENDING` | Pending transfer age |
| `R-CON-02` | Idempotency record kẹt | Idempotency stuck |
| `R-CON-03` | Deadlock/lock timeout | Deadlock/lock timeout |
| `R-CON-04` | Ledger mismatch | Ledger mismatch |
| `R-CON-05` | Audit gap (transfer) | Audit gap |
| `R-CON-06` | Notification backlog | Notification backlog |
| `R-CON-07` | Connection pool exhaustion | Connection pool exhaustion |
| `R-CON-08` | Database outage/connection loss | Database unavailable |
| `R-AUD-01` | Audit write failure | Audit write failure |
| `R-AUD-02` | Audit chain invalid | Audit chain invalid |
| `R-AUD-03` | Audit gap (audit) | Audit gap / Verification stale |
| `R-AUD-04` | Legal hold/purge conflict | Retention/purge alert |
| `R-AUD-05` | Export anomaly | Export anomaly |

---

## 2. Runbooks `R-CON`

### `R-CON-01` — Transfer remains PENDING

1. Ghi `correlation_id`, `reference`, timestamp, alert.
2. Kiểm tra transfer state, idempotency state, ledger entries và audit completion.
3. Nếu ledger/audit chưa đủ, giữ transfer ở trạng thái chưa terminal; không báo thành công.
4. Chỉ retry/reconcile bằng quy trình idempotent đã duyệt.
5. Escalate khi ledger thiếu/không cân hoặc không xác định được state.
6. Đính kèm query output và quyết định cuối vào evidence.

### `R-CON-02` — Idempotency record stuck

1. Tìm transfer liên kết và state hiện tại.
2. Xác định request đã commit, rollback hay còn xử lý.
3. Không tạo transfer mới với key khác khi chưa rõ outcome.
4. Resume/reconcile theo idempotency policy.
5. Alert nếu record vượt retention/processing threshold.

### `R-CON-03` — Deadlock or lock timeout

1. Ghi request lỗi, correlation ID, loại lỗi (`40P01`, `55P03`) — không ghi payload nhạy cảm.
2. Kiểm tra lock wait, transaction duration và global lock order.
3. Chỉ retry công việc an toàn/idempotent theo bounded retry.
4. Không tăng isolation hoặc pool size một cách mù quáng.
5. Thêm regression test khi xác định root cause.

### `R-CON-04` — Ledger mismatch

1. Freeze việc chấp nhận evidence bị ảnh hưởng; xác định reference liên quan.
2. So sánh account balance, ledger entry, transfer state và audit fact.
3. Không sửa/xóa ledger hoặc audit thủ công.
4. Dùng quy trình reconciliation/restore có kiểm soát.
5. Escalate audit/security review và ghi root cause.

### `R-CON-05` — Audit gap (transfer)

1. Xác định transfer/action/correlation ID thiếu audit fact.
2. Không đánh dấu money movement `COMPLETED` khi thiếu audit bắt buộc.
3. Retry audit/reconciliation bằng quy trình đã duyệt.
4. Nếu ảnh hưởng chain integrity, dừng retention/purge và bảo toàn backup.
5. Ghi cách khắc phục gap và regression test.

### `R-CON-06` — Notification backlog

1. Kiểm tra record pending/retrying, age và retry count.
2. Kiểm tra adapter availability và timeout behavior.
3. Retry với backoff và bounded attempts.
4. Không rollback giao dịch tiền đã `COMPLETED` vì notification lỗi.
5. Alert khi backlog vượt policy.

### `R-CON-07` — Connection pool exhaustion

1. Kiểm tra DB availability, active connections và waiting requests.
2. Xác định transaction dài, idle transaction và lock wait.
3. Chỉ dừng/hủy công việc không critical đã được duyệt.
4. Bảo toàn transaction outcome; retry chỉ với idempotency.
5. Tune timeout/pool sau khi có root-cause evidence, không phải hành động đầu tiên.

### `R-CON-08` — Database outage or connection loss

1. Đánh dấu dependency unavailable và fail closed.
2. Xác định request chưa rõ kết quả đã commit hay rollback.
3. Restore/reconnect theo DR procedure đã duyệt.
4. Verify migration, ledger, audit và idempotency invariant.
5. Chỉ retry client request với original idempotency key sau khi outcome an toàn.

---

## 3. Runbooks `R-AUD`

### `R-AUD-01` — Audit write failure

1. Kiểm tra database, trigger, constraint và lock condition.
2. Không bypass audit; không trả completion nếu thiếu evidence bắt buộc.
3. Giữ transfer `PENDING`/`FAILED` theo hành vi SRS.
4. Retry/reconcile với original idempotency key.
5. Xác nhận không còn audit gap.

### `R-AUD-02` — Audit chain invalid

1. Dừng retention, purge và archive.
2. Bảo toàn database backup và verification evidence.
3. Xác định mismatch đầu tiên; so sánh với evidence đã lưu.
4. Không sửa audit record để che mismatch.
5. Chỉ restore/repair bằng quy trình đã duyệt và được audit.

### `R-AUD-03` — Audit gap

1. Làm theo `R-AUD-01`.
2. Verify liên kết transfer, ledger, audit và correlation ID.
3. Escalate nếu reconciliation tự động không chứng minh được final state.
4. Ghi gap, root cause và regression test.

### `R-AUD-04` — Legal hold or purge conflict

1. Xác nhận hold scope và retention policy.
2. Dừng purge cho record bị ảnh hưởng.
3. Chỉ release hold khi có approval, strong authentication và audit.
4. Bảo toàn archive và evidence manifest.

### `R-AUD-05` — Export anomaly

1. Verify actor permission, filter, row count và export scope.
2. Verify file integrity và evidence manifest.
3. Rate-limit hoặc block export đáng ngờ.
4. Không phân phối audit evidence qua kênh chưa được duyệt.

---

## 4. Kiểm chứng

| ID | Kịch bản | Kỳ vọng | Trạng thái |
|---|---|---|---|
| `DVT-006` | Failure/recovery exercise | Không false success, duplicate debit hoặc ledger imbalance | **Not verified** |
| `DVT-007` | Observability/runbook review | Alert map tới runbook đúng | **Not verified** |
