# BE-INT-09 — Admin Maker/Checker với Identity, Limit và Audit

> **Trạng thái · Verified** trên HTTP/PostgreSQL với provider thật. Nghiệm thu mới 30/30 PASS; 228 test liên quan có kết quả PASS theo từng class sau lượt sửa kỳ vọng. [Báo cáo](BE-INT-09-verification.md).
>
> Phạm vi · AC-028, FR-033/050/051, UC-011/013; DEC-ID-011, DEC-ADM-002/003/015, DEC-INT-003/005/007/011/018.

Admin dùng các provider hiện có: `identity.api.IdentityRoleCommand`, `identity.api.StepUpChallengePort`, `limit.api.LimitConfigMutationPort` và `audit.api.AuditModuleApi`. Maker tạo approval PENDING; Checker khác Maker approve thành APPLIED hoặc reject thành REJECTED trong cùng PostgreSQL transaction với provider và Audit C2. Version được server capture, không nhận từ REST. Replay dùng JSON/status đã lưu theo scope actor/operation/resource; không consume proof hoặc apply lần hai.

Đối chiếu AC-028, schema `04`, API `07`/OpenAPI, [Modules contract](../../../docs-common/design/05.%20Modules%20contract.md) §§5.3/10/11.1/12.1 và source/test Admin cho thấy provider đã có, nhưng evidence cũ dùng chủ DB che lỗi quyền khóa role. PostgreSQL `banking_app` trên V22 tái hiện `42501`; kiểm thứ tự DB tái hiện UPDATE approval sau Audit.

DEC-INT-018 xử lý hai điểm nối này:

- V23 thêm hàm Identity-owned chỉ đọc và giữ `FOR SHARE` role trong transaction caller; runtime chỉ được EXECUTE, vẫn không có UPDATE catalogue. Hàm dùng SQL tĩnh và search_path cố định, không cấp EXECUTE cho PUBLIC.
- Payload approval JPA bất biến sau insert; update quyết định flush trước khi hoàn tất idempotency và ghi Audit. Lỗi provider/C2 rollback approval, assignment/version/session hoặc limit, challenge consume và idempotency. Attempt vận hành vẫn ghi riêng theo DEC-ADM-015.

Kiểm qua HTTP server thật, PostgreSQL canonical, role runtime `banking_app` và provider production, gồm Notification HTTP adapter với SMS simulator. Bộ test nghiệm thu riêng kiểm cả role và limit: Maker không tự duyệt, binding actor/key/payload/approval/decision/note, replay, hai Checker, stale version từ hai approval thật, lỗi sau provider write, Audit ở proposal/approve/reject, và không business DML sau Audit append. Test migration kiểm nâng V22→V23 giữ checksum, quyền và khóa thật trên hai connection. Chạy các class liên quan bằng [verify.sh](evidence/BE-INT-09/verify.sh); không chạy toàn suite theo DEC-INT-007.

Giá trị numeric policy OI-API-05 giữ nguyên cơ chế cấu hình hiện có; không tự chọn chính sách mới. Bằng chứng này không đóng BE-INT-10/11/12, retention/export Audit hoặc `ADM-OI-11` cho result lịch sử trên GET approval. Scope staff single-bank theo DEC-INT-008; đa tenant cần contract riêng.
