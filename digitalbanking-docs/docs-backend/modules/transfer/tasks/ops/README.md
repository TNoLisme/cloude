# BE-TRF-07 — Dashboard và alerts candidate

Các query dùng nhãn scrape `environment`. Mỗi instance Transfer đọc cùng primary, nên dùng `max` cho count/age và kiểm freshness theo từng instance. Các counter process dùng `increase` hoặc `rate` trước khi tổng hợp.

| Panel | Query hoặc series | Cách đọc |
|---|---|---|
| Transfer created / terminal | `sum by (environment,status,failure_code) (increase(banking_transfer_total[5m]))` | Commit đã được process quan sát. Đối chiếu primary khi nghi mất counter sau restart hoặc unknown commit. |
| Latency T1/T2 | `histogram_quantile(0.95, sum by (environment,outcome,le) (rate(transfer_t2_duration_seconds_bucket[5m])))` | Đổi `t2` thành `t1` cho intent. T1 `RETURNED/ERROR` đo transaction chính; T2 `COMMITTED/ROLLED_BACK/UNKNOWN` gồm settlement tham gia recovery. Dùng `0.99` cho p99. |
| Idempotency | `sum by (environment,outcome) (increase(transfer_idempotency_total[5m]))` | Một quyết định đã phân loại. Request bị chặn trước khi phân loại không tạo điểm đo. |
| Pending count / oldest age | `max by (environment) (transfer_pending_count and (transfer_pending_snapshot_available == 1) and (transfer_pending_snapshot_age_seconds <= 120))` | Đổi sang `transfer_pending_age_seconds` để xem tuổi theo đồng hồ DB. Snapshot gồm MANUAL. Empty đọc thành công có giá trị 0. |
| Snapshot health | `transfer_pending_snapshot_available`, `transfer_pending_snapshot_age_seconds` | Trước lần đọc thành công đầu tiên, count/age chưa xác định. Đọc lỗi giữ giá trị cũ, available 0. Dùng `up` theo scrape target để phát hiện mất target. |
| Recovery / purge | `transfer_recovery_total`, `transfer_recovery_incident_total`, `transfer_idempotency_purged_total` | RESUMED, RECONCILED, RETRY và MANUAL ghi sau commit; incident mô tả tín hiệu cần xác minh. |
| Audit | `transfer_audit_required_failure_total`, `transfer_security_deny_fallback_total` | C1/C2 có thể đã rollback. C3 fallback cần kiểm tra durable audit gap. |
| HTTP, DB/pool, Notification | HTTP route template và pool dùng Common/Ops. Notification đã có `banking_notification_outbox_claimed_total` và `banking_notification_outbox_delivery_total`. | Ghép theo môi trường. Chờ projection backlog/age và signal lock/deadlock thật do owner cung cấp. |

[Rules candidate](BE-TRF-07.rules.yml) dùng runbook ID từ [hạ tầng chung](../../../../../docs-common/08.%20Deployment%20infrastructure.md). Owner trong YAML là nhãn định tuyến dự kiến. Cửa sổ 5 phút và ngưỡng freshness 120 giây là giá trị cho fixture/rule candidate. Xem [test fire/resolve](BE-TRF-07.rules.test.yml).

Quy trình diễn tập khi có môi trường tích hợp

1. Bật candidate ở môi trường QA có quyền scrape phù hợp. Đối chiếu series và nhãn với dashboard.
2. Inject lần lượt lỗi audit, bản ghi PENDING già, manual incident và snapshot mất kết nối. Kiểm tra fire, route đến đúng owner và resolve khi tín hiệu hết. Dùng R-AUD-01, R-CON-01 và R-CON-05 tương ứng.
3. Khi có signals do provider công bố, bổ sung rule deadlock/lock wait và backlog/exhausted của Notification, chạy R-CON-03 và R-CON-06. Diễn tập trường hợp scrape target tắt hẳn qua quy tắc `up` của môi trường.
4. Lưu thời điểm fire/resolve, owner/route, evidence synthetic và giới hạn. Không diễn giải cảnh báo thành chứng cứ commit, rollback hay quyền retry.
