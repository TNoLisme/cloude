package com.simulatedbank.banking.limit.adapter.out;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.simulatedbank.banking.limit.application.port.LimitReadPort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(name = "app.limit.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
class LimitReadAdapter implements LimitReadPort {
    private final LimitReadRepository repository;
    private final LimitPersistenceMapper mapper;

    @Override
    public List<Row> findSnapshot(long customerId, Long accountId, LocalDate usageDate, Instant asOf) {
        var rows = repository.findSnapshot(customerId, accountId, usageDate, asOf);
        var scopes = new HashSet<Long>();
        if (!rows.stream().allMatch(row -> scopes.add(row.getAccountId()))) {
            throw new IllegalStateException("Overlapping Limit configurations");
        }
        return rows.stream().map(mapper::toReadRow).toList();
    }
}
