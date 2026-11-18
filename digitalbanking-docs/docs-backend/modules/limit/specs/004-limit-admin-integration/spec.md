# Feature Specification: Hoàn thiện endpoint E4 — LIM-09/LIM-10

**Feature Branch**: `phuong/limit`
**Created**: 2026-11-16
**Status**: Clarified — sẵn sàng lập plan
**Input**: Thực hiện nốt E4 theo Spec Kit; không dùng subagent reviewer; ghi rõ dependency thiếu và mock.

## Clarifications

### Session 2026-11-16

- Q: Maker chưa có approval ID thì bind challenge thế nào? → A: Maker bind actor + action + hash payload chuẩn hóa; checker thêm approvalId và quyết định.

- Q: D5 maker tự duyệt trả mã nào? → A: 409 ROLE_CONFLICT theo API contracts; đồng bộ ghi chú SRS.

- Q: Phạm vi admin khi thiếu implementation? → A: Làm approval_request và workflow LIMIT_CHANGE thật, giới hạn E4; limit/audit thật, identity/customer/account qua contract và fake chỉ trong test.

## User Scenarios & Testing

### User Story 1 — Maker tạo đề xuất hạn mức (Priority: P1)

Nhân viên có quyền và step-up hợp lệ tạo đề xuất thay đổi, nhận approval pending; hạn mức áp dụng chưa đổi.

**Why this priority**: Hoàn thiện LIM-09 trên nền provider LIM-07 đã có.
**Independent Test**: Gửi yêu cầu hợp lệ/không hợp lệ; kiểm tra response, approval, config và audit cùng commit hoặc rollback.

**Acceptance Scenarios**:
1. **Given** maker đủ quyền và step-up, **When** tạo đề xuất, **Then** trả 201, Location theo hợp đồng, approval PENDING + config PENDING_APPROVAL + audit LIMIT_CHANGE_REQUESTED cùng transaction.
2. **Given** thiếu quyền/CSRF/step-up hoặc scope sai, **When** yêu cầu được gửi, **Then** trả lỗi đúng hợp đồng và không tạo approval/config.
3. **Given** lỗi ở config hoặc audit, **When** tạo đề xuất, **Then** không để approval/config dở dang.

### User Story 2 — Checker quyết định đề xuất (Priority: P1)

Checker khác maker, đủ quyền và step-up có thể approve/reject đúng một lần; lưu quyết định, config và audit nguyên tử.

**Why this priority**: Hoàn thiện LIM-10, bảo vệ maker/checker và chống áp dụng lặp.
**Independent Test**: Gọi approve/reject qua endpoint, kiểm tra trạng thái PostgreSQL, các quyết định đồng thời và rollback khi audit lỗi.

**Acceptance Scenarios**:
1. **Given** đề xuất pending và checker hợp lệ, **When** approve, **Then** approval APPLIED, config mới ACTIVE, config cũ SUPERSEDED và audit LIMIT_CHANGE_APPROVED cùng commit; usage giữ nguyên.
2. **Given** đề xuất pending, **When** reject, **Then** approval/config REJECTED và audit LIMIT_CHANGE_REJECTED cùng commit, active/usage giữ nguyên.
3. **Given** tự duyệt, đã quyết định, challenge sai hoặc hai checker cạnh tranh, **When** quyết định, **Then** chặn sai phạm và chỉ một quyết định thành công.
4. **Given** lỗi audit sau khi cập nhật, **When** transaction kết thúc, **Then** approval/config và consume challenge rollback cùng nhau khi provider identity thật đã tích hợp.

### User Story 3 — Tích hợp an toàn khi provider chưa sẵn sàng (Priority: P1)

Các owner có contract rõ để nối identity/customer/account; triển khai endpoint không cần đợi implementation của họ, nhưng không cấp quyền giả hoặc tự xác nhận tích hợp thật.

**Why this priority**: Theo design/05 §13, phát triển song song và phân biệt bằng chứng thật/test double.
**Independent Test**: Endpoint thiếu provider bắt buộc từ chối an toàn; test double chỉ tồn tại trong test; phần PostgreSQL thật kiểm chứng độc lập.

**Acceptance Scenarios**:
1. **Given** thiếu implementation identity hoặc tra cứu ownership, **When** endpoint được gọi, **Then** không tạo/duyệt dữ liệu bằng đường đi giả lập production.
2. **Given** các test dùng fake identity/scope, **When** báo cáo kết quả, **Then** ghi rõ capability giả lập và integration chưa nghiệm thu, không tuyên bố toàn E4 đã verified.

### Edge Cases

- Maker chưa có approval ID tại thời điểm yêu cầu challenge; binding phải chống đổi actor/scope/amount/action sau verify.
- Checker dùng challenge của approval/decision/user khác; challenge đã consume; rollback khi apply thất bại.
- Maker tự duyệt: SRS UC-011 ghi 403, API §8.5.3 ghi 409 ROLE_CONFLICT.
- Scope không tồn tại hoặc account không thuộc customer; provider bắt buộc vắng mặt.
- Body sai kiểu tiền/ID, field ngoài hợp đồng, reason/decisionNote quá dài; không đưa PII/secret vào log/audit.
- Approve đồng thời, approve lại, reject sau approve, hạ hạn mức dưới usage và D4 account mới trong ngày đã dùng.

## Requirements

### Functional Requirements

- **FR-001**: Cung cấp POST /api/v1/admin/limits và POST /api/v1/admin/limits/{approvalId}/approve đúng payload/status/Location từ 07 §8.5; không thêm API quản trị chung hoặc role/retention workflow.
- **FR-002**: Actor phải từ session/principal server; kiểm staff role, quyền maker/checker và service-level authorization; cookie bắt buộc CSRF, bearer theo chính sách common hiện có.
- **FR-003**: Tạo/consume challenge SENSITIVE_ACTION qua contract identity, kiểm actor/purpose/action binding; chưa verify không có approval/config; không tự xây OTP/step-up engine production.
- **FR-004**: Triển khai persistence approval_request và workflow LIMIT_CHANGE thật tối thiểu trong admin; dùng PostgreSQL thật chứng minh atomicity với limit/audit. Không mở rộng thành generic approval platform.
- **FR-005**: Tạo approval pending, gọi LimitChanges.prepare và ghi audit C2 trong một transaction; provider limit không ghi bảng approval.
- **FR-006**: Quyết định khóa và kiểm lại approval pending, chặn self/repeat, gọi approve/reject của LimitChanges, cập nhật approval và audit cuối trong cùng transaction; usage không reset/tăng do đổi cấu hình. Tự duyệt được ghi security audit độc lập; lỗi security audit không biến deny thành thành công.
- **FR-007**: Maker tự duyệt trả HTTP 409 ROLE_CONFLICT theo API contracts; đồng bộ SRS UC-011 để bỏ mâu thuẫn D5.
- **FR-008**: Customer/account references và ownership qua public provider contract; chỉ nhận snapshot tối thiểu; không truy cập bảng/repository/entity của provider khác.
- **FR-009**: Thiếu provider bắt buộc phải fail-closed; fake/stub chỉ test/dev; không thêm bean production giả hoặc dựng các bảng khách hàng/identity giả.
- **FR-010**: Kế thừa quyết định E4 trước: policy max cấu hình rõ; từ chối effectiveFrom tương lai; hiệu lực ngay khi duyệt; D4 chặn account scope chưa tracking nếu customer đã dùng trong ngày.
- **FR-011**: Test endpoint/security/contract bằng test double đúng phạm vi; dùng PostgreSQL thật cho approval/config/audit locking và rollback khi đã có implementation thật. Báo cáo tiếng Việt phân biệt rõ mock và integration thiếu.
- **FR-012**: Tự chạy converge theo spec/plan/tasks, sửa gap đến khi đạt; không dùng subagent. Không chạy full regression không liên quan.

### Key Entities

- **Approval LIMIT_CHANGE**: ID, loại/target/payload, pending/decision status, maker/checker, timestamps, reason/note và correlation.
- **Cấu hình hạn mức**: Version pending/active/superseded/rejected do limit sở hữu; giữ usage riêng.
- **Actor/challenge/scope snapshots**: Giá trị do server/provider xác thực; không nhận quyền hoặc danh tính từ body client.

## Success Criteria

### Measurable Outcomes

- **SC-001**: Mọi tạo đề xuất hợp lệ trả đúng response và ba thành phần approval/config/audit cùng kết quả transaction trong phạm vi implementation thật.
- **SC-002**: Mọi quyết định hợp lệ được áp dụng tối đa một lần; cạnh tranh không mất lịch sử hoặc làm lệch usage.
- **SC-003**: Tất cả ca thiếu quyền, step-up hoặc dependency bắt buộc không tạo thay đổi nhạy cảm trái phép.
- **SC-004**: Build thành công, test liên quan đạt, tự converge không còn gap của phạm vi đã clarify; báo cáo không gộp mock với nghiệm thu provider thật.

## Assumptions

- docs-common/01–11 > design/05 > backend; quyết định user đã chốt của E4 được giữ nguyên. Docs 05 v0.1 là draft: công bố candidate interface/DTO để owner review, không tự ghi nhận đã được các owner phê duyệt.
- Contract ở provider.api; admin là orchestrator và write-owner approval, limit giữ cấu hình/usage, audit giữ writer. Không build generic approval platform, role/retention UI hay E3/transfer mới chỉ để đóng acceptance.
- Chưa có production session/step-up/customer/account; triển khai consumer theo contract và ghi Blocked cho nghiệm thu provider thật. Session SPI common và provider limit/audit hiện có được tái sử dụng.
- Maker binding đã chốt: actor + action + hash payload chuẩn hóa, chưa có approvalId. Checker bind thêm approvalId và decision. Không tạo approval trước step-up; đồng bộ ghi chú 07 §6.5 về trường hợp maker.
- Test/build giới hạn max-workers=2; không commit/push tự động trong lượt triển khai này.
