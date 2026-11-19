package com.simulatedbank.banking.customer.application;

import com.simulatedbank.banking.common.context.RequestContext;
import com.simulatedbank.banking.common.context.RequestContextHolder;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.api.CustomerQuery;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service of `API-CUS-01` `GET /api/v1/customers/me` (BE-CUS-IMP-05).
 *
 * <p>Nguồn principal tin cậy: {@code common.config.SessionAuthenticator.Session} do
 * {@code SessionAuthenticationFilter} đặt vào SecurityContext — controller truyền
 * {@code userId} đã xác thực, KHÔNG nhận customerId/userId từ query/body/header client
 * (design.md §7, DEC-CUS-002). Ánh xạ Identity→Customer là hợp đồng đã duyệt
 * {@link CustomerQuery#findByUserId} (design/05 §6, BE-CUS-IMP-04); tuyệt đối không giả định
 * userId == customerId và không suy mapping từ bảng module khác.</p>
 *
 * <p>Behavior: mapping vắng (user lạ hoặc {@code app_user} chưa có Customer row) →
 * {@code 404 CUSTOMER_NOT_FOUND}; không tạo Customer row, không placeholder, không side effect
 * — GET thuần đọc (design.md §5/§7). Mapping trúng nhưng ownership mismatch (bất biến phòng
 * thủ chống data corruption) → {@code 403 AUTH_FORBIDDEN} từ {@link CustomerPiiService#getForOwner}.
 * Row `INACTIVE` vẫn trả hồ sơ kèm status: chính sách eligibility chưa được duyệt (CUS-OI-06)
 * nên không tự thêm điều kiện từ chối. Thất bại persistence/crypto lan truyền nguyên trạng để
 * rollback, không bị giả thành 404 ({@code 07}: lỗi kỹ thuật ≠ "không thấy").</p>
 *
 * <p>Transaction: đọc thuần {@code readOnly = true}; hai lookup (mapping theo user_id, row theo
 * customer_id) chạy trong một read-only transaction trên port đã duyệt của IMP-04/IMP-03 —
 * tránh mở thêm port hoặc query ngoài hợp đồng.</p>
 */
@Service
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
public class CustomerProfileService {

    private final CustomerQuery customerQuery;
    private final CustomerPiiService piiService;
    private final CustomerAuditPort audit;

    public CustomerProfileService(CustomerQuery customerQuery, CustomerPiiService piiService, CustomerAuditPort audit) {
        this.customerQuery = customerQuery;
        this.piiService = piiService;
        this.audit = audit;
    }

    /**
     * Trả projection owner-safe (fullName đầy đủ, phone/email đã mask) của customer thuộc
     * principal đã xác thực. Không đổi trạng thái persisted; ciphertext không bao giờ ra ngoài.
     */
    @Transactional(readOnly = true)
    public OwnerCustomerPiiView getMyProfile(long actorUserId) {
        CustomerQuery.CustomerIdentity binding = customerQuery.findByUserId(actorUserId)
                .orElseThrow(() -> new BankingBusinessException(ErrorCode.CUSTOMER_NOT_FOUND));
        // Defense-in-depth: getForOwner tái kiểm stored.user_id == actorUserId trước khi decrypt
        // — nếu ánh xạ bị hỏng, request vẫn dừng ở 403, không bao giờ rò PII customer khác.
        try {
            return piiService.getForOwner(binding.customerId(), actorUserId);
        } catch (BankingBusinessException denial) {
            if (denial.code() == ErrorCode.AUTH_FORBIDDEN) {
                RequestContextHolder.current().map(RequestContext::correlationId).ifPresent(correlationId ->
                        audit.recordStandalone(CustomerAuditPort.CustomerAuditEvent.accessDenied(
                                actorUserId, "CUSTOMER", binding.customerId()), correlationId));
            }
            throw denial;
        }
    }
}
