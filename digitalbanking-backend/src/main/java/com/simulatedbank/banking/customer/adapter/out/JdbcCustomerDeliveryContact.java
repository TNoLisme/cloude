package com.simulatedbank.banking.customer.adapter.out;

import com.simulatedbank.banking.customer.api.CustomerDeliveryContact;
import com.simulatedbank.banking.customer.application.CustomerPiiProtection;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(name = "app.customer.enabled", havingValue = "true", matchIfMissing = true)
public class JdbcCustomerDeliveryContact implements CustomerDeliveryContact {
    private final JdbcTemplate jdbc;
    private final CustomerPiiProtection protection;

    public JdbcCustomerDeliveryContact(JdbcTemplate jdbc, CustomerPiiProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @Override
    @Transactional(readOnly = true)
    public Result forOtpUser(long userId) {
        return find("user_id", userId, false);
    }

    @Override
    @Transactional(readOnly = true)
    public Result forTransferOutcome(long customerId) {
        return find("customer_id", customerId, true);
    }

    private Result find(String idColumn, long id, boolean requiresConsent) {
        return DataAccessUtils.optionalResult(jdbc.query("""
                SELECT status, phone_enc, transfer_sms_allowed, updated_at
                  FROM banking.customer WHERE %s = ?
                """.formatted(idColumn), (rs, row) -> {
            if (!"ACTIVE".equals(rs.getString("status"))) {
                return Unavailable.INACTIVE;
            }
            if (requiresConsent && !rs.getBoolean("transfer_sms_allowed")) {
                return Unavailable.NO_CONSENT;
            }
            byte[] encrypted = rs.getBytes("phone_enc");
            if (encrypted == null) {
                return Unavailable.NO_CONTACT;
            }
            String phone = protection.decrypt(encrypted);
            protection.maskPhone(phone); // Reuse the Customer phone format validation.
            return new Available(phone, rs.getTimestamp("updated_at").toInstant());
        }, id)).orElse(Unavailable.NOT_FOUND);
    }
}
