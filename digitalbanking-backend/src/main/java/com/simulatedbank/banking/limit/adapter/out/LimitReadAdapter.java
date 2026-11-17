package com.simulatedbank.banking.limit.adapter.out;

import com.simulatedbank.banking.limit.application.port.LimitReadPort;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class LimitReadAdapter implements LimitReadPort {
    private final LimitReadRepository repository;
    private final LimitPersistenceMapper mapper;

    @Override
    public List<Row> findSnapshot(long customerId, Long accountId, LocalDate usageDate, Instant asOf) {
        return repository.findSnapshot(customerId, accountId, usageDate, asOf).stream().map(mapper::toReadRow).toList();
    }
}
