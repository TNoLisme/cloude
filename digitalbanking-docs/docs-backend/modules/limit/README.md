# Module `limit`

Nguồn ưu tiên: **docs-common/01–11 → docs-common/design → docs-backend**. Nguồn mô hình là 04 §4.5.1–4.5.2; ngữ nghĩa chọn hạn mức là 07 §8.5.1 và validation là §8.5.2. Không dùng GLOBAL/scope_type/scope_id từ bản tóm tắt cũ.

- [Kế hoạch epic/feature](DELIVERY-PLAN.md): phạm vi E1–E4 và dependencies.
- [Bằng chứng E1](E1-verification.md): kết quả build/test, traceability và giới hạn nghiệm thu.
- [Tóm tắt module](../_overview/06-limit.md): canonical schema và các hướng tích hợp tiếp theo.
- [Spec E1](specs/001-limit-foundation/spec.md), [plan](specs/001-limit-foundation/plan.md), [tasks](specs/001-limit-foundation/tasks.md): hồ sơ SDD local, được ignore theo cấu hình repo.

## E1 — LIM-01/LIM-02

Migration V3 tạo hai bảng limit_config/limit_usage theo customer/account, lưu version và attribution riêng với usage. FK liên module chưa cài vì bảng customer/account/app_user/approval_request chưa có; đây là quyết định clarify của user ngày 15/11/2026, không phải xác nhận integrity/ownership.

Provider nội bộ `LimitRules` nhận customerId, sourceAccountId và Instant; chọn ACTIVE trong khoảng [effective_from,effective_to), trả cả hai scope, effective per-transaction là min. `checkPerTransaction` chấp nhận equality, vượt bất kỳ trần nào ném `BankingBusinessException(LIMIT_EXCEEDED)`; tiền không hợp lệ là VALIDATION_ERROR. Không cấu hình trả NOT_CONFIGURED riêng, không tự cho phép chuyển tiền hay thêm default.

`LimitJdbcRepository` insert configuration/usage trong transaction của caller (MANDATORY), trả identity/timestamps đã lưu; đọc configuration theo ID, usage theo scope/date. Không update/delete, không activation/approval workflow. E1 chỉ kiểm tra từng lần, chưa check/increment daily usage. Đọc provider không ghi dữ liệu hay lấy khóa enforcement. Caller phải xác thực customer/account pair; lọc pair không thay ownership kiểm chứng từ module account.

Money là BigDecimal (tối đa 17 chữ số nguyên, 2 chữ số thập phân), dương cho config/check, không âm cho usage; kiểm tra trước JDBC để tránh NUMERIC làm tròn. VND; không tự chốt timezone D2 vì ngày/instant được truyền tường minh.

## Chưa nghiệm thu

- FK/reference existence và account/customer ownership: cần migrations thật của owner, thêm/validate FK trong migration tiếp theo.
- D1 policy max/default và enforcement khi NOT_CONFIGURED: còn mở cho E2 và cấu hình sau này.
- Daily locking/increment, transfer retry/recovery/concurrency, API self limits và maker/checker/audit activation: thuộc E2–E4.
- Future activation transition D3 và account scope giữa ngày D4: chưa được giải quyết bằng E1.

Build/test provider pass không thay nghiệm thu các tích hợp trên hoặc human review.

## E2 — LIM-03/LIM-04 provider

`LimitEnforcement.lockAndCheck` bắt buộc transaction caller có thể ghi (READ COMMITTED), dùng DB transaction start và `app.timezone` (nghiệm thu `Asia/Ho_Chi_Minh`). Tạo/khóa customer usage trước account usage, đọc cấu hình hiệu lực trong T2 và kiểm tra cả hai scope. Customer counter luôn là mutex, kể cả chỉ có account config. Thiếu cả hai cấu hình trả NOT_CONFIGURED và không cấp phép cộng usage; mapping lỗi transfer còn hoãn.

Caller xử lý fraud/tiền/ledger thành công rồi gọi `recordCompleted(check)` trước audit/commit. Handle giữ nguyên ngày/scope/amount, chỉ dùng một lần trong đúng transaction; provider kiểm tra transaction JPA vật lý đang active và vô hiệu handle trước completion; không thay thế idempotency của transfer. Business rejection ở check không đánh dấu rollback-only, để caller có thể ghi FAILED; lỗi kỹ thuật/completion/audit rollback cùng usage. Migration V4 cấp UPDATE riêng các cột counter, không sửa V3.

- [Spec E2](specs/002-limit-enforcement/spec.md), [plan](specs/002-limit-enforcement/plan.md), [tasks](specs/002-limit-enforcement/tasks.md).
- [Bằng chứng E2 và bàn giao LIM-05](E2-verification.md).
- LIM-05 hoãn theo clarify: checkout chưa có production transfer/account/ledger/identity. Provider test không nghiệm thu debit/credit, ledger, terminal replay hay recovery. D4 (bật account scope giữa ngày) thuộc quyết định E4, không tự reset/backfill.
