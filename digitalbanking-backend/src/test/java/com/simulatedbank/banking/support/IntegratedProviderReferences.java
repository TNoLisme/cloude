package com.simulatedbank.banking.support;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Test-only parent rows required by the integrated Fraud/Notification foreign keys (canonical V15/V16).
 * The module-local fixtures inserted flags/notifications with synthetic transfer/customer/user IDs; the integrated
 * schema enforces real FKs, so those parents must exist. A broad ID range covers every synthetic ID the tests assert
 * on. Rows are synthetic and never prove provider contracts.
 */
public final class IntegratedProviderReferences {
    private IntegratedProviderReferences() { }

    public static void seed(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("INSERT INTO banking.app_user(user_id,username,password_hash,user_type) "
                    + "OVERRIDING SYSTEM VALUE SELECT id, 'provider-fixture-'||id, 'test-only', 'CUSTOMER' "
                    + "FROM generate_series(1,10000) AS ids(id) ON CONFLICT (user_id) DO NOTHING");
            statement.execute("INSERT INTO banking.customer(customer_id,user_id,full_name_enc) "
                    + "OVERRIDING SYSTEM VALUE SELECT id, id, decode('00','hex') "
                    + "FROM generate_series(1,10000) AS ids(id) ON CONFLICT (customer_id) DO NOTHING");
            statement.execute("INSERT INTO banking.account(account_id,customer_id,account_no_enc,account_no_hash,account_no_last4) "
                    + "OVERRIDING SYSTEM VALUE VALUES "
                    + "(1,1,decode('00','hex'),repeat('a',64),'0001'),"
                    + "(2,1,decode('00','hex'),repeat('b',64),'0002') "
                    + "ON CONFLICT (account_id) DO NOTHING");
            statement.execute("INSERT INTO banking.transfer"
                    + "(transfer_id,reference_no,customer_id,initiated_by_user_id,source_account_id,destination_account_id,"
                    + "amount,currency,status,correlation_id) "
                    + "OVERRIDING SYSTEM VALUE SELECT id, 'FX'||id, 1, 1, 1, 2, 1000, 'VND', 'PENDING', 'provider-fixture' "
                    + "FROM generate_series(1,10000) AS ids(id) ON CONFLICT (transfer_id) DO NOTHING");
        }
    }
}