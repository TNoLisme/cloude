package com.simulatedbank.banking.common.error;

import java.sql.SQLException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;

/** Formats failures after the owner has completed rollback/retry; never infers business context from SQL. */
final class DatabaseErrorMapper {

    private DatabaseErrorMapper() {}

    static ErrorCode code(Throwable exception) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                String state = sql.getSQLState();
                if (Set.of("55P03", "40P01", "40001", "57014").contains(state) || state.startsWith("08")) {
                    return ErrorCode.SERVICE_UNAVAILABLE;
                }
                return ErrorCode.INTERNAL_ERROR;
            }
        }
        return exception instanceof DataAccessResourceFailureException
                ? ErrorCode.SERVICE_UNAVAILABLE : ErrorCode.INTERNAL_ERROR;
    }
}
