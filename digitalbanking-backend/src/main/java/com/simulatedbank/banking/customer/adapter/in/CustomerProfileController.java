package com.simulatedbank.banking.customer.adapter.in;

import com.simulatedbank.banking.common.config.SessionAuthenticator;
import com.simulatedbank.banking.common.error.BankingBusinessException;
import com.simulatedbank.banking.common.error.ErrorCode;
import com.simulatedbank.banking.customer.application.CustomerProfileService;
import com.simulatedbank.banking.customer.application.OwnerCustomerPiiView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * `API-CUS-01` `GET /api/v1/customers/me` (07 §8.2.1, BE-CUS-IMP-05).
 *
 * <p>Bảo mật: authentication (401 khi thiếu/invalid/expired) và coarse authorization
 * (`ACCOUNT_READ_SELF`, 403 khi thiếu quyền) do security chain dùng chung thực thi trước handler
 * ({@code common.config.SecurityFilterChainConfig}); controller fail-closed khi snapshot phiên
 * vắng mặt — không suy actor từ parameter/header, không decode token. Correlation ID và
 * `Cache-Control: no-store` được trung gian dùng chung bảo đảm (CorrelationAndRequestIdFilter,
 * ApiSecurityHeadersFilter, ProblemWriter) — controller không nhân bản filter.</p>
 *
 * <p>Chủ thể: chỉ tra cứu theo principal tin cậy; endpoint không có path/body/query nhận danh
 * tính → IDOR không thể xảy ra ở cấu trúc hợp đồng (anti-enumeration: 404 không tiết lộ sự tồn
 * tại của customer khác).</p>
 */
@RestController
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/v1/customers")
public class CustomerProfileController {

    private final CustomerProfileService profileService;

    public CustomerProfileController(CustomerProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/me")
    public ResponseEntity<CustomerProfileResponse> me(HttpServletRequest servlet) {
        long actorUserId = Long.parseLong(actorUserId(servlet));
        OwnerCustomerPiiView view = profileService.getMyProfile(actorUserId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(view));
    }

    private static CustomerProfileResponse toResponse(OwnerCustomerPiiView view) {
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

    /** Principal từ `SessionAuthenticationFilter` (attribute session snapshot, như AuthController). */
    private static String actorUserId(HttpServletRequest request) {
        Object session = request.getAttribute(
                "com.simulatedbank.banking.common.config.SessionAuthenticationFilter.session");
        if (session instanceof SessionAuthenticator.Session snapshot) {
            return snapshot.userId();
        }
        throw new BankingBusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
    }
}
