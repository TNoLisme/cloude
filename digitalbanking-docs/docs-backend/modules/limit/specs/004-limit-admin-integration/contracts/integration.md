# Candidate contracts E4 — cần provider/consumer owner review

Không coi chữ ký ở đây là đã được các thành viên khác duyệt. Canonical nguồn: 07 §8.5 và design/05 §5/6/7/10/11/12/13; ba clarify trong spec.

- identity.api.StepUpChallengePort.requestChallenge(ActionBinding) → Challenge: OWN, ngoài business TX; purpose SENSITIVE_ACTION. consumeVerified(UUID, ActionBinding) → void: JOIN/MANDATORY, đúng actor/action/binding/expiry/VERIFIED, single-use; rollback caller hoàn consume. Binding actor/action/requestHash; approvalId null cho maker, có cho checker. Challenge không chứa OTP/token.
- identity.api.StaffDirectory.findActor(userId) → Optional ActorSummary(userId, safe displayName): R; không full profile. Không bịa name khi provider thiếu.
- customer.api.CustomerQuery.findById(customerId) → Optional CustomerSnapshot(customerId): R; xác nhận tồn tại cho staff có quyền tại caller.
- account.api.AccountQuery.findById(accountId) → Optional AccountSnapshot(accountId, customerId, currency): R; admin kiểm account thuộc customer. Provider future giữ ownership/data-scope contract nguồn.
- limit.api.LimitChanges.validateChange: R, policy+money+currency/time preflight, không approval ID giả. prepare/approve/reject hiện có vẫn caller TX. Approve overload expected current version: check trong cùng lock; null nghĩa expect no effective current config. LimitRules.current nhận exact nullable account scope để làm snapshot, không dummy account ID.
- audit.api.AuditModuleApi.recordInTransaction(ApprovalEvent): JOIN/C2, event provider-owned không free text, fail rollback. recordDenied(DeniedEvent): độc lập C3, best-effort fallback log/metric, không biến deny thành allow.

HTTP maker: 201 ApprovalSummary, Location /api/v1/admin/approvals/{id}. Checker: 200 ApprovalDetail APPLIED/REJECTED; no endpoint GET approvals ở feature này. IDs/Money string; money fixed 2 decimals, timestamp ISO; fields theo07. Maker permission LIMIT_MAKER hoặc LIMIT_MANAGE + staff role; checker thêm CHECKER. Cookie CSRF common, bearer policy common. Self-decision 409 ROLE_CONFLICT.

Bộ vector chung: missing/wrong actor/changed payload/purpose/expired/consumed challenge → reject; rollback challenge with caller; missing scope/not owner →404/403; duplicate or stale decision →409; missing provider →503 (hoặc401 khi session SPI cũng thiếu); C2 audit failure rollback all; no fake production bean. Identity/customer/account implementations phải chạy lại vectors khi được ghép thật; hiện chưa nghiệm thu chúng.
