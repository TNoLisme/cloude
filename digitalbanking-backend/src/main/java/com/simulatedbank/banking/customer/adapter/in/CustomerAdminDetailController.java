package com.simulatedbank.banking.customer.adapter.in;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.adapter.in.CustomerProfileResponse;
import com.simulatedbank.banking.customer.application.StaffCustomerDetailService;
import com.simulatedbank.banking.customer.application.StaffCustomerPiiView;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * `API-ADM-01` `GET /api/v1/admin/customers/{customerId}` (07 §8.2.4, BE-CUS-IMP-06).
 *
 * <p>Bảo mật: authentication (401) và coarse authorization (`CUSTOMER_READ`, 403 cho CUSTOMER
 * và staff thiếu permission) do security chain dùng chung thực thi trước handler
 * ({@code common.config.SecurityFilterChainConfig}); service TÁI KIỂM permission từ principal
 * tin cậy — không tin header/query/body của client cho danh tính, role hay scope (design.md §7).
 * Response dùng đúng schema `CustomerProfile` (07 §7.2.9/openapi) với `fullName` masked
 * (DEC-CUS-005/CUS-OI-02) và phone/email masked; KHÔNG thêm field, KHÔNG trả entity.</p>
 *
 * <p>`customerId` là decimal string theo schema `ID` (`[1-9][0-9]{0,18}`): sai định dạng →
 * `400 VALIDATION_ERROR`; không tồn tại → `404 CUSTOMER_NOT_FOUND` (không tiết lộ thêm gì cho
 * caller thiếu quyền vì 403 xảy ra trước lookup). `X-Correlation-ID` và `Cache-Control: no-store`
 * do trung gian dùng chung bảo đảm, không nhân bản filter.</p>
 */
@RestController
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/admin/customers")
public class CustomerAdminDetailController {

    private final StaffCustomerDetailService detailService;

    public CustomerAdminDetailController(StaffCustomerDetailService detailService) {
        this.detailService = detailService;
    }

    @GetMapping("/{customerId}")
    public ResponseEntity<CustomerProfileResponse> detail(@PathVariable String customerId,
            HttpServletRequest servlet) {
        long customerKey = parseCustomerId(customerId);
        SessionAuthenticator.Session principal = principal(servlet);
        StaffCustomerPiiView view = detailService.getCustomerDetail(customerKey,
                Long.parseLong(principal.userId()), principal.authorities(), roleCodes(principal),
                correlationId(servlet));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(view));
    }

    private static CustomerProfileResponse toResponse(StaffCustomerPiiView view) {
        return new CustomerProfileResponse(
                Long.toString(view.getCustomerId()),
                Long.toString(view.getUserId()),
                view.getFullName(),
                view.getPhoneMasked(),
                view.getEmailMasked(),
                view.getStatus(),
                view.getCreatedAt(),
                view.getUpdatedAt());
    }

    /** Định dạng `ID` schema (07 §7.2.9): `[1-9][0-9]{0,18}` — sai → 400, không chạm persistence. */
    private static long parseCustomerId(String customerId) {
        if (customerId == null || !customerId.matches("[1-9][0-9]{0,18}")) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return Long.parseLong(customerId);
        } catch (NumberFormatException tooLarge) {
            throw new BankingBusinessException(ErrorCode.VALIDATION_ERROR);
        }
    }

    /** Principal từ `SessionAuthenticationFilter` (attribute session snapshot, như AuthController). */
    private static SessionAuthenticator.Session principal(HttpServletRequest request) {
        Object session = request.getAttribute(
                "com.simulatedbank.banking.common.config.SessionAuthenticationFilter.session");
        if (session instanceof SessionAuthenticator.Session snapshot) {
            return snapshot;
        }
        throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
    }

    private static String roleCodes(SessionAuthenticator.Session principal) {
        Set<String> roles = new LinkedHashSet<>();
        for (String authority : principal.authorities()) {
            if (authority.startsWith("ROLE_")) {
                roles.add(authority.substring("ROLE_".length()));
            }
        }
        return String.join(",", roles);
    }

    private static String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute("correlationId");
        return value instanceof String text && !text.isEmpty() ? text
                : UUID.randomUUID().toString();
    }
}
