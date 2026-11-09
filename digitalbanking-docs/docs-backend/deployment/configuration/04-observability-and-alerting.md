# 04. Observability và Alerting

> **Dự án:** Digital Banking MVP
> **Mã tài liệu:** `BE-DEP-CFG-04`
> **Phiên bản:** v2.0
> **Ngày cập nhật:** 09/11/2026
> **Trạng thái:** Baseline đã đối chiếu `docs-common/` — chưa có bằng chứng runtime
> **Nguồn chuẩn:** [[digitalbanking-docs/docs-common/08. Deployment infrastructure.md]] (§9–§10), [[digitalbanking-docs/docs-common/07. API contracts.md]] (`API-HLT-01`), [[digitalbanking-docs/docs-common/06. System architecture.md]] (§12), [[digitalbanking-docs/docs-common/design/01. audit-traceability.md]] (§12), [[digitalbanking-docs/docs-common/design/02. consistency-concurrency.md]] (§13)

---

## 1. Health contract

### 1.1. Hai endpoint khác nhau

| Endpoint | Đối tượng | Nội dung | Ghi chú |
|---|---|---|---|
| `/actuator/health` | Probe, reverse proxy, vận hành | Trạng thái tối giản `UP`/`DOWN`; có thể dùng liveness/readiness | Public chỉ ở mức tối giản; `show-details: never`. |
| `/api/v1/health` (`API-HLT-01`) | Admin/Auditor | `checkedAt`, `version`, `components.database`, `components.notificationOutbox` | Không lộ connection string, secret hoặc dữ liệu nhạy cảm. |

> Không mô tả `/api/v1/health` là `{"status":"UP"}` tối giản. Đó là contract của probe; API health có component detail theo `07`. Chi tiết đầy đủ phải khớp OpenAPI.

### 1.2. Hành vi

| Trạng thái | Kỳ vọng | Ý nghĩa vận hành |
|---|---|---|
| `UP` | Request health thành công | Có thể nhận traffic. |
| `DOWN` | Trả failure status (ví dụ `503`) | Dừng routing; điều tra dependency/application. |
| Unknown | Không có kết quả đáng tin | Coi như unavailable cho đến khi kiểm chứng. |

Health không chứng minh mọi capability nghiệp vụ hoạt động; vẫn cần smoke test và kiểm tra invariant tài chính.

---

## 2. Structured logging

Mỗi dòng log ra `stdout` là JSON một dòng, có tối thiểu:

- `timestamp` (UTC ISO-8601), `level`, `service`, `environment`.
- `correlation_id`, `request_id`.
- `user_id` đã mask hoặc ID nội bộ.
- `route`, `http_status`, `action`, `outcome`, `latency_ms`, `error_code`.
- `audit_id` khi có.

### 2.1. Cấm log

- Password, PIN, passcode, OTP, token, cookie `SESSION_TOKEN`, `X-CSRF-Token`.
- Secret, khóa mã hóa, credential.
- Số tài khoản đầy đủ, CCCD/CMND, số điện thoại/email đầy đủ hoặc PII không cần thiết.
- Toàn bộ payload chuyển tiền hoặc trạng thái nhạy cảm trước/sau.

Technical log retention/rotation chưa được quy định (`Open`); phải định nghĩa rotation trước demo và không thay thế audit retention.

---

## 3. Metrics

| Nhóm | Tín hiệu cần có | Yêu cầu |
|---|---|---|
| HTTP | Request rate, error rate, p95/p99 latency | `NFR-001..004` |
| Transfer | Created, completed, failed, pending age/duration | `NFR-001`, `NFR-006`, `NFR-025` |
| Idempotency | Replay, conflict, in-progress, stuck | `FR-017`, `NFR-011` |
| Concurrency | Lock wait, deadlock, retry, pool waiting | `NFR-009..010` |
| Audit | Write success/failure, latency, audit gap | `NFR-021..023` |
| Fraud | Flag count theo rule, review result | `FR-044..048` |
| Notification | Pending backlog, retry, failure | `NFR-008` |
| Database | Availability, connection pool, slow query, storage | `NFR-006`, `NFR-025` |
| Jobs | Reconciliation, verification, retention, purge | `FR-022`, `FR-053` |

**Không dùng nhãn cardinality cao**: raw `account_id`, `user_id`, token, idempotency key, correlation ID, reference.

---

## 4. Alert contract

| Alert | Severity | Trigger | Hành động ngay | Runbook |
|---|---|---|---|---|
| Negative balance | Critical | Bất kỳ invariant violation | Freeze luồng ảnh hưởng; bảo toàn evidence | Audit/security incident |
| Duplicate debit | Critical | Bất kỳ duplicate money side effect | Dừng nhận transfer; điều tra | Audit/security incident |
| Audit chain invalid | Critical | Hash verify mismatch | Dừng purge/archive; bảo toàn backup | `R-AUD-02` |
| Ledger mismatch | Critical | Reconciliation phát hiện lệch | Dừng xử lý ảnh hưởng; đối soát | `R-CON-04` |
| Database unavailable | High | Health/database check fail | Fail closed; dừng routing | `R-CON-08` |
| Pending transfer age | High | `transfer_pending_age_seconds > 300` (Proposed) | Kiểm tra state, reconcile | `R-CON-01` |
| Idempotency stuck | High | Vượt ngưỡng processing (ngưỡng **Open**) | Chặn side effect mới; điều tra | `R-CON-02` |
| Deadlock/lock timeout | High | Vượt ngưỡng Proposed trong `design/02` §13.3 | Kiểm tra lock order; retry có giới hạn | `R-CON-03` |
| Connection pool exhaustion | High | Active > 80% (Proposed) hoặc pool wait kéo dài | Kiểm tra transaction/lock dài | `R-CON-07` |
| Transfer error rate | High | > 1% trong 5 phút (Proposed) | Kiểm tra DB/adapter; bảo toàn outcome | Incident review |
| Audit write failure | High | Audit bắt buộc ghi thất bại | Không báo hoàn tất; retry/reconcile | `R-AUD-01` |
| Audit gap | High | Thiếu audit fact cho action/transfer | Không mark `COMPLETED`; reconcile | `R-CON-05` / `R-AUD-03` |
| Export anomaly | High | Volume/content export bất thường | Chặn/rate-limit export; review quyền | `R-AUD-05` |
| Notification backlog | Medium | Backlog/age vượt ngưỡng Proposed | Retry backoff; không rollback tiền | `R-CON-06` |
| Verification stale | Medium | Verify vượt interval (Proposed) | Chạy verify; điều tra job | `R-AUD-02` / `R-AUD-03` |

> Ngưỡng alert chưa được phê duyệt (`CFG-OPEN-04`). Các giá trị `Proposed` chỉ là khởi điểm; không tuyên bố đã hiệu lực. `design/04` là dependency cho severity/escalation của security event.

---

## 5. Mapping runbook

| Runbook | Tình huống |
|---|---|
| `R-CON-01` | Transfer kẹt `PENDING` |
| `R-CON-02` | Idempotency record kẹt |
| `R-CON-03` | Deadlock/lock timeout |
| `R-CON-04` | Ledger mismatch |
| `R-CON-05` | Audit gap (góc nhìn transfer) |
| `R-CON-06` | Notification backlog |
| `R-CON-07` | Connection pool exhaustion |
| `R-CON-08` | Database outage/connection loss |
| `R-AUD-01` | Audit write failure |
| `R-AUD-02` | Audit chain invalid |
| `R-AUD-03` | Audit gap (góc nhìn audit) |
| `R-AUD-04` | Legal hold/purge conflict |
| `R-AUD-05` | Export anomaly |

Chi tiết tại [[digitalbanking-docs/docs-backend/deployment/configuration/06-incident-runbooks.md]].

---

## 6. Kiểm chứng

| ID | Kịch bản | Kỳ vọng | Trạng thái |
|---|---|---|---|
| `DVT-003` | Health check | Phân biệt healthy/unavailable; không lộ secret | **Not verified** |
| `DVT-007` | Observability review | Log, metric, alert và runbook link được định nghĩa | **Not verified** |

---

## 7. Điểm mở

- `CFG-OPEN-03`: interval job.
- `CFG-OPEN-04`: ngưỡng alert chưa phê duyệt.
- Log retention/rotation chưa được quy định.
