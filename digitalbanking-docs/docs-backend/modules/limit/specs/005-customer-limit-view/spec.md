# Feature Specification: Customer limit view (LIM-06 / E3)

**Feature Branch**: `codex/limit`  
**Created**: 2026-11-17  
**Status**: Draft  
**Input**: Epic 03 in `DELIVERY-PLAN.md`; user mandates source hierarchy and Spring Data JPA.

## Clarifications

### Session 2026-11-17

- Q: Khi khách hàng không chọn tài khoản, hiển thị phạm vi nào? → A: Chỉ hạn mức chung của khách hàng; chọn tài khoản để xem thêm hạn mức của tài khoản đó.

## User Scenarios & Testing

### User Story 1 - Xem hạn mức của mình (Priority: P1)

Khách hàng xem hạn mức hiện tại và phần còn lại để chuẩn bị chuyển tiền.

**Independent Test**: Đăng nhập với quyền thích hợp, đọc hạn mức có và không có accountId; đối chiếu từng scope và ngày nghiệp vụ.

**Acceptance Scenarios**:

1. **Given** cấu hình customer đang hiệu lực, **When** xem không chọn tài khoản, **Then** chỉ hiện cấu hình customer và mức còn lại theo usage của customer.
2. **Given** cấu hình customer và account của mình đang hiệu lực, **When** chọn account đó, **Then** hiện từng cấu hình, usage riêng, effective là minimum tương ứng của các mức per-transaction và remaining.
3. **Given** không có cấu hình phù hợp, **When** xem hạn mức, **Then** danh sách rỗng và các effective fields null.
4. **Given** usage vượt daily limit, **When** xem, **Then** remaining bằng 0 và không âm.

### User Story 2 - Bảo vệ phạm vi dữ liệu (Priority: P1)

Khách hàng chỉ xem dữ liệu của mình và không thể xem account của người khác.

**Independent Test**: Thử các principal/quyền khác nhau và account không thuộc customer.

**Acceptance Scenarios**:

1. **Given** account thuộc người khác, **When** khách hàng chọn account đó, **Then** yêu cầu bị từ chối 403 và dữ liệu không bị lộ.
2. **Given** thiếu phiên hoặc quyền, **When** gọi endpoint, **Then** bị từ chối theo hợp đồng.
3. **Given** provider ownership chưa sẵn sàng, **When** chọn account, **Then** từ chối an toàn và báo dependency chưa sẵn sàng.

### Edge Cases

- Cấu hình pending/rejected/superseded, chưa bắt đầu hoặc đã kết thúc không được chọn; effective_from inclusive, effective_to exclusive.
- Usage không tồn tại tương ứng 0, không tạo row/khóa khi đọc; ngày được xác định từ cùng thời điểm và timezone cấu hình.
- Amount tiền hiển thị dạng decimal 2 chữ số, ID dạng chuỗi; giá trị effective và accountId có thể null như hợp đồng.
- Kiểm tra trước/sau commit, ngày đổi theo timezone, account-only, customer-only, nhiều scope, ownership lỗi và mã lỗi.

## Requirements

### Functional Requirements

- **FR-001**: Chỉ customer đã xác thực có `LIMIT_READ_SELF` được đọc customerId từ phiên và dữ liệu của mình; customerId khác phiên bị từ chối.
- **FR-002**: Bỏ accountId chỉ chọn customer scope; có accountId hợp lệ phải xác minh ownership và chọn customer scope cùng account scope tương ứng.
- **FR-003**: Chỉ trả cấu hình ACTIVE tại cùng `asOf`, kể cả ranh giới hiệu lực; không có cấu hình trả items rỗng và effective null.
- **FR-004**: Mỗi item phản ánh usage của chính scope vào ngày nghiệp vụ, mặc định 0 nếu thiếu và remaining là max(0, dailyLimit - usedToday).
- **FR-005**: Effective per-transaction là min các per-transaction; effective remaining là min các remaining riêng; trả null khi không có item.
- **FR-006**: Read không tạo hoặc sửa counter, không lấy khóa enforcement; dùng một asOf và ngày nghiệp vụ từ timezone cấu hình.
- **FR-007**: Response và lỗi theo `07` §7.2.13, §8.5.1 và quy tắc scope §3.6; không tiết lộ dữ liệu thuộc customer khác.
- **FR-008**: Tái sử dụng authentication, permission, clock, error và context của common; ownership qua account API; dependency thiếu không được giả lập production.
- **FR-009**: Test có PostgreSQL thật cho truy vấn/transaction và mock provider chưa có; chỉ tuyên bố tích hợp thật sau khi provider/transfer thật được kiểm chứng.

### Key Entities

- **Limit configuration**: scope customer/account, giá trị, khoảng hiệu lực, trạng thái, version.
- **Daily usage**: customer và optional account, ngày nghiệp vụ, tiền đã dùng.
- **Limit view**: snapshot từ cấu hình và usage cùng `asOf`.

## Success Criteria

### Measurable Outcomes

- **SC-001**: 100% trường hợp kiểm thử scope và ranh giới hiệu lực trả đúng danh sách và effective.
- **SC-002**: 100% kiểm thử từ chối quyền/ownership không trả dữ liệu customer khác.
- **SC-003**: 100% kiểm thử đọc không thay đổi số bản ghi hoặc usage.
- **SC-004**: Bằng chứng ghi rõ kiểm thử PostgreSQL, mock và các tích hợp thật còn thiếu.

## Assumptions

- `Asia/Ho_Chi_Minh` là default cấu hình hiện tại, không coi là quyết định nghiệm thu D2.
- Transfer thật, identity/account/audit provider thật có thể chưa có; không thay thế bằng production fake.
- Các policy D1/D3/D4 không tự được chốt bởi feature này.
