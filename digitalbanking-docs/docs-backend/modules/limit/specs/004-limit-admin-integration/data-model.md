# Mô hình dữ liệu

approval_request theo 04 §4.5.3: approval_id identity; request_type/target_type theo enum nguồn; target_id 1–64; payload JSONB; status PENDING/APPROVED/REJECTED/APPLIED/CANCELLED; maker/checker IDs, decision_note ≤500, correlation_id ≤64, created/decided/applied timestamps.

Checks: checker != maker; terminal APPROVED/REJECTED/APPLIED yêu cầu checker và decided_at; APPLIED yêu cầu applied_at. Index status/time, type/status, target, maker, checker. Không hard-delete. Foreign keys identity và historical limit approval vẫn staged.

Workflow được triển khai: LIMIT_CHANGE PENDING → APPLIED hoặc REJECTED. Approval + limit config + C2 audit và JOIN challenge consume cùng TX. Payload current snapshot nullable (chưa có config hiệu lực), proposed money/VND/effectiveFrom, reason an toàn. Current.version là expected version được kiểm lại dưới limit lock khi approve; reject không cần config version còn mới.

Không thêm bảng production cho challenge/customer/account/app_user. Test-only e4_test.challenge là fake identity fixture; không migration production.
