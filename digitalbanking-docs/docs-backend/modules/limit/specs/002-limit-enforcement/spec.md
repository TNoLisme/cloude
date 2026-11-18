# Feature Specification: E2 — Thực thi hạn mức trong chuyển tiền

**Feature Branch**: `skills/phuong/limit` | **Created**: 2026-11-15 | **Status**: Implemented LIM-03/04 — independent converge PASS; LIM-05 deferred
**Input**: Implement E2 (LIM-03, LIM-04, LIM-05) trong DELIVERY-PLAN.

## Context

Digital Banking MVP tự quản lý chuyển tiền nội bộ, số dư và sổ cái. Limit sở hữu cấu hình và usage, tham gia transaction tiền của transfer. Nguồn ưu tiên: docs-common/01–11 → design → docs-backend. Nguồn cụ thể: SRS FR-016/AC-009–013/034; Database §4.5.2/6.6/7.7; Architecture §6–8; API §8.3.1; Consistency §5.3/7.3/7.5/9.2; Test plan T-LIM-01–03.

## Clarifications

### Session 2026-11-15

- Q: Timezone dùng nghiệm thu ngày nghiệp vụ? → A: Asia/Ho_Chi_Minh; vẫn cấu hình qua app.timezone, lấy DB time một lần cho transaction.

- Q: Không có cấu hình hiệu lực ở cả hai scope? → A: Trả NOT_CONFIGURED, không cấp phép cộng usage; hoãn mapping lỗi transfer đến LIM-05.

- Q: Checkout chưa có dependency thật cho LIM-05; phạm vi triển khai? → A: Hoàn tất provider LIM-03/04; hoãn LIM-05 đến khi dependency thật sẵn sàng.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Không vượt hạn mức khi cạnh tranh (Priority: P1)

Khách hàng gửi nhiều giao dịch từ cùng hoặc khác tài khoản nhưng tổng tiền hoàn tất vẫn trong hạn mức áp dụng.
**Why this priority**: Bảo vệ FR-016 và AC-009.
**Independent Test**: Hai giao dịch độc lập tranh phần còn lại; chỉ giao dịch đủ hạn mức được chấp nhận.
**Acceptance Scenarios**:
1. **Given** usage 0 và daily 100, **When** hai giao dịch 60 cạnh tranh, **Then** chỉ một được chấp nhận và usage cuối là 60/count 1.
2. **Given** cấu hình customer/account cùng hiệu lực, **When** một scope vượt per-transaction hoặc daily, **Then** trả LIMIT_EXCEEDED; bằng ngưỡng được chấp nhận.
3. **Given** đánh giá sớm đã đạt, **When** usage/cấu hình thay đổi trước thực thi tiền, **Then** đánh giá lại dữ liệu hiện hành tại bước thực thi quyết định kết quả.

### User Story 2 — Usage nguyên tử với kết quả tiền (Priority: P1)

Usage chỉ tăng khi giao dịch tiền hoàn tất, được giữ khóa từ kiểm tra đến ghi nhận.
**Why this priority**: Tránh tiền và usage lệch nhau khi lỗi.
**Independent Test**: Kiểm tra/cộng usage trong transaction caller; commit/rollback và business rejection có kết quả đúng.
**Acceptance Scenarios**:
1. **Given** hạn mức cho phép, **When** caller hoàn tất và commit, **Then** customer counter và account counter áp dụng tăng amount/count đúng một lần.
2. **Given** đã cộng usage, **When** lỗi kỹ thuật hoặc audit làm caller rollback, **Then** usage trở về trước giao dịch.
3. **Given** business rejection sau kiểm tra, **When** caller kết thúc giao dịch thất bại, **Then** usage không tăng.
4. **Given** chỉ có account config, **When** hoàn tất, **Then** vẫn có customer usage tổng hợp nhiều tài khoản và chỉ các account có config được ghi account usage.

### User Story 3 — Bàn giao tích hợp transfer (Priority: P2, deferred)

Transfer thật gọi kiểm tra cuối và ghi usage trong cùng transaction tiền; replay/recovery không ghi nhận lần hai.
**Why this priority**: LIM-05 cần bằng chứng consumer thật, provider test không thay thế.
**Independent Test**: Transfer thật qua nhánh vượt ngưỡng, đúng ngưỡng, retry, recovery và rollback sau increment.
**Acceptance Scenarios**:
1. **Given** transfer thật vượt hạn mức, **When** thực thi, **Then** LIMIT_EXCEEDED và không debit/credit/ledger/usage.
2. **Given** transfer đã hoàn tất, **When** retry/recovery, **Then** không cộng usage lần hai.

### Edge Cases

- Cạnh tranh tạo counter đầu ngày không tạo bản trùng; khóa customer trước account.
- Một transaction dùng một ngày nghiệp vụ, không đổi khi chờ khóa hay đi qua nửa đêm.
- Cấu hình hết/chưa hiệu lực không được áp dụng; kiểm tra lại cả hai scope theo E1.
- Thiếu cấu hình không tự coi là vô hạn hoặc thêm default.
- Amount sai/âm/quá precision bị từ chối trước ghi dữ liệu.
- Gọi enforcement ngoài transaction caller phải bị chặn.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-E2-001**: Thực thi tiền MUST kiểm tra lại hạn mức từng lần/ngày theo cấu hình hiệu lực, không dùng quyết định sớm thay kiểm tra cuối.
- **FR-E2-002**: MUST tạo/lấy và khóa customer usage trước account usage; customer counter luôn là điểm đồng bộ kể cả chỉ có account config.
- **FR-E2-003**: MUST chỉ tạo/khóa account usage khi có account config; các giao dịch cùng customer chia sẻ customer counter.
- **FR-E2-004**: MUST dùng một ngày nghiệp vụ xác định một lần theo timezone cấu hình, giữ nguyên từ check đến increment.
- **FR-E2-005**: MUST chỉ tăng amount và count sau khi caller xử lý tiền thành công; dùng cùng transaction và khóa của bước check.
- **FR-E2-006**: MUST rollback toàn bộ usage khi caller lỗi kỹ thuật/audit; business rejection không tăng usage.
- **FR-E2-007**: MUST từ chối vượt bất kỳ per-transaction/daily scope nào bằng LIMIT_EXCEEDED; equality được phép.
- **FR-E2-008**: Khi cả hai scope không có cấu hình hiệu lực, MUST trả NOT_CONFIGURED và không cấp phép cộng usage; không thêm default hay hiểu là PASS. Mapping lỗi transfer thuộc LIM-05, chưa chốt trong đợt này.
- **FR-E2-009**: Ngày nghiệp vụ MUST theo app.timezone, nghiệm thu Asia/Ho_Chi_Minh; thời gian MUST lấy từ PostgreSQL tại đầu transaction và giữ nguyên từ kiểm tra đến ghi nhận, kể cả qua nửa đêm.
- **FR-E2-010**: Phạm vi triển khai hiện tại MUST hoàn tất provider LIM-03/04 và tài liệu bàn giao LIM-05. LIM-05 hoãn đến khi production transfer/account/ledger/identity sẵn sàng; không tạo production giả, không kết luận nghiệm thu retry/recovery bằng provider harness. Các acceptance của US3 là điều kiện nghiệm thu tương lai, không phải buildable obligations của đợt này.

### Key Entities *(include if feature involves data)*

- **Applicable configuration**: Cấu hình customer/account ACTIVE và trong khoảng hiệu lực của E1.
- **Daily usage**: Customer, account tùy chọn, ngày nghiệp vụ, tiền tệ, tổng amount và count hoàn tất.
- **Checked execution**: Amount, cấu hình và usage đã kiểm tra, gắn với ngày và transaction caller; chỉ được ghi nhận trong transaction đó.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Không giao dịch nào vượt hạn mức trong các ca cạnh tranh cùng/khác tài khoản; tổng chấp nhận khớp usage cuối.
- **SC-002**: 100% ca rollback/rejection không để usage tăng; ca commit tăng đúng amount/count ở các scope áp dụng.
- **SC-003**: 100% ca chuyển ngày giữ một ngày cho mỗi lần thực thi và chọn ngày mới cho lần thực thi tiếp theo.
- **SC-004**: Không counter trùng khi cạnh tranh tạo mới; giao dịch bị chặn tới khi transaction giữ khóa kết thúc.
- **SC-005**: Có bằng chứng transfer thật cho retry/recovery trước nghiệm thu LIM-05; thiếu dependency phải báo chưa nghiệm thu.

## Assumptions

- Tái sử dụng E1; không thay mô hình thành GLOBAL, không xây endpoint E3/E4.
- Không sửa các thay đổi có sẵn của user; không xây transfer giả trong production để đóng LIM-05.
- FK/ownership liên module đã được hoãn ở E1; caller tương lai phải xác thực ownership trước limit.
- Build đóng gói phải thành công sau focused PostgreSQL integration tests và unit/module boundary tests liên quan; không chạy full regression không liên quan.
- Constitution hiện là template chưa điền; áp dụng AGENTS.md và thứ bậc docs do user chốt.
