package com.simulatedbank.banking.support;

import java.sql.Connection;
import java.sql.SQLException;

/** Test-only parent rows required by the integrated Limit foreign keys. */
public final class IntegratedLimitReferences {
    private IntegratedLimitReferences() { }

    public static void seed(Connection connection, boolean approvals) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("INSERT INTO banking.app_user(user_id,username,password_hash,user_type) "
                    + "OVERRIDING SYSTEM VALUE SELECT id, 'limit-fixture-'||id, 'test-only', "
                    + "CASE WHEN id IN (11,12) THEN 'CUSTOMER' ELSE 'STAFF' END "
                    + "FROM (VALUES (1),(2),(3),(11),(12)) AS ids(id) ON CONFLICT (user_id) DO NOTHING");
            statement.execute("INSERT INTO banking.customer(customer_id,user_id,full_name_enc) "
                    + "OVERRIDING SYSTEM VALUE VALUES (11,11,decode('00','hex')),(12,12,decode('00','hex')) "
                    + "ON CONFLICT (customer_id) DO NOTHING");
            statement.execute("INSERT INTO banking.account(account_id,customer_id,account_no_enc,account_no_hash,account_no_last4) "
                    + "OVERRIDING SYSTEM VALUE VALUES "
                    + "(21,11,decode('00','hex'),lpad('15',64,'0'),'0021'),"
                    + "(22,12,decode('00','hex'),lpad('16',64,'0'),'0022') "
                    + "ON CONFLICT (account_id) DO NOTHING");
            if (approvals) {
                statement.execute("INSERT INTO banking.approval_request"
                        + "(approval_id,request_type,target_type,target_id,payload,maker_id,correlation_id) "
                        + "OVERRIDING SYSTEM VALUE SELECT id,'LIMIT_CHANGE','CUSTOMER','11','{}'::jsonb,1,"
                        + "'limit-fixture-'||id FROM generate_series(1,1000) AS ids(id) "
                        + "ON CONFLICT (approval_id) DO NOTHING");
            }
        }
    }
}
