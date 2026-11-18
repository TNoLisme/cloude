package com.simulatedbank.banking.customer.application;

import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Application service của `API-ADM-01` `GET /api/v1/admin/customers/{customerId}`
 * (07 §8.2.4, BE-CUS-IMP-06).
 *
 * <p>Bảo mật (07 §3.4–§3.6, design.md §7): chỉ staff có authority {@code CUSTOMER_READ} mới được
 * tra cứu. Coarse authorization chạy ở security chain dùng chung ({@code hasAuthority("CUSTOMER_READ")},
 * 401/403 trước handler); service TÁI KIỂM permission từ principal tin cậy (snapshot
 * {@code SessionAuthenticator.Session} của {@code SessionAuthenticationFilter}) — defense-in-depth
 * theo design.md §7 "kiểm soát quyền ở service, không chỉ security filter", không tin header/
 * query/body của client cho danh tính, role hay scope. CUSTOMER không bao giờ có
 * {@code CUSTOMER_READ} (07 §3.4 + {@code JpaAuthorityReader} user-type isolation) → 403 + audit.</p>
 *
 * <p>Data scope: CUS-OI-05 (Admin/Audit/Security chưa chốt runtime scope) — phần scope ngoài
 * role/permission chưa có hợp đồng, KHÔNG tự chế scope; hợp đồng này chỉ enforce permission
 * đã duyệt và ghi BLOCKED cho phần scope (BE-CUS-IMP-06.md §G).</p>
 *
 * <p>Erro semantics: thiếu permission → {@code 403 AUTH_FORBIDDEN} + audit
 * `SECURITY_ACCESS_DENIED` C3 (REJECTED); {@code customerId} không hợp lệ →
 * {@code 400 VALIDATION_ERROR} TRƯỚC khi chạm persistence (không lộ tồn tại); row không tồn tại
 * → {@code 404 CUSTOMER_NOT_FOUND} (không audit — 404 không phải security denial, design/01 §5.7).
 * Audit thành công không có action riêng (design/01 §5.7 — technical access log).</p>
 */
@Service
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
public class StaffCustomerDetailService {

    /** Permission đã duyệt của `API-ADM-01` (07 §3.4 seed: OPERATIONS/ADMIN; §8.2.4). */
    static final String REQUIRED_PERMISSION = "CUSTOMER_READ";

    /** Marker role CUSTOMER: không bao giờ được dùng tra cứu staff (DAR-001, design/04 §5.1). */
    static final String CUSTOMER_ROLE_AUTHORITY = "ROLE_CUSTOMER";

    private final CustomerPiiService piiService;
    private final CustomerAuditPort audit;

    public StaffCustomerDetailService(CustomerPiiService piiService, CustomerAuditPort audit) {
        this.piiService = piiService;
        this.audit = audit;
    }

    /**
     * Tra cứu hồ sơ customer đã mask cho staff. `actorUserId`/`actorAuthorities` là principal
     * tin cậy do controller truyền từ snapshot phiên; KHÔNG nhận từ tham số client.
     *
     * @param customerId        ID từ path (đã validate định dạng ở controller)
     * @param actorUserId       user ID tin cậy của staff
     * @param actorAuthorities  authority snapshot tin cậy của phiên (permission + ROLE_*)
     * @param actorRoleCodes    mã role (đã strip `ROLE_`) dùng cho metadata audit
     * @param correlationId     correlation ID của request (từ filter dùng chung, không null) cho audit
     */
    @Transactional(readOnly = true)
    public StaffCustomerPiiView getCustomerDetail(long customerId, long actorUserId,
            Set<String> actorAuthorities, String actorRoleCodes, String correlationId) {
        if (customerId <= 0 || actorUserId <= 0) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        if (actorAuthorities == null || !actorAuthorities.contains(REQUIRED_PERMISSION)
                || actorAuthorities.contains(CUSTOMER_ROLE_AUTHORITY)) {
            // 403 trước lookup: không tiết lộ sự tồn tại của customer cho caller thiếu quyền. Một
            // snapshot mang cả role CUSTOMER và permission staff là identity context không nhất quán
            // (design/04 §5.1: user_type tách biệt cứng) nên bị từ chối thay vì coi là staff.
            audit.recordStandalone(CustomerAuditPort.CustomerAuditEvent.accessDenied(
                    actorUserId, actorRoleCodes, customerId), correlationId);
            throw new BankingBusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        return piiService.getForStaff(customerId);
    }
}
