# Nghiên cứu và quyết định

Nghiên cứu từ repo và nguồn 01–11/design05; không cần tra cứu bên ngoài, không dùng subagent theo yêu cầu lượt làm.

- **Decision**: approval thật hẹp trong admin. **Rationale**: clarify user; cần chứng minh approval/config/audit cùng TX. **Alternative**: fake approval chỉ chứng minh điều phối, không đáp ứng mục tiêu lần này.
- **Decision**: provider API + ObjectProvider, không fallback production. **Rationale**: design05 phát triển song song, missing capability phải fail-closed. **Alternative**: stub production làm giả readiness bị loại.
- **Decision**: TransactionTemplate sau challenge; READ COMMITTED; actor từ SessionAuthenticator.Session. **Rationale**: common có filter/CSRF/session SPI thật, chưa có identity. **Alternative**: @WithMockUser cho mọi test che mất principal/CSRF wiring.
- **Decision**: normalized JCS binding, maker chưa có approvalId; checker có approval/decision hash. **Rationale**: clarify user; không tạo pending trước step-up.
- **Decision**: expected config version checked in limit lock; approval FOR UPDATE. **Rationale**: design05 stale conflict và no double apply; preflight check alone có race.
- **Decision**: audit public facade hẹp, recordStandalone C3 dùng cùng append path. **Rationale**: admin chỉ import audit.api; self-deny phải có audit mà không commit config. C2 giữ fail-closed, C3 fallback không đảo deny.
- **Decision**: không giả ActorSummary.displayName; dùng StaffDirectory. **Rationale**: session SPI hiện chỉ có userId/authorities, API bắt buộc displayName.
- **Decision**: fake identity scope trong test, challenge consumption test schema JOIN caller. **Rationale**: kiểm wiring/contract với SQL thật trong test, không tự nhận OTP/session/ownership thật đã hoàn thành.
