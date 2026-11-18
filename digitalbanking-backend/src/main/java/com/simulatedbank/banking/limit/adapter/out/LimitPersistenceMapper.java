package com.simulatedbank.banking.limit.adapter.out;

import com.simulatedbank.banking.limit.api.LimitConfiguration;
import com.simulatedbank.banking.limit.api.LimitUsage;
import org.mapstruct.Mapper;

@Mapper
interface LimitPersistenceMapper {
    com.simulatedbank.banking.limit.application.port.LimitReadPort.Row toReadRow(LimitReadRepository.Row row);
    LimitConfigEntity toEntity(LimitConfiguration value);
    LimitConfiguration toSnapshot(LimitConfigEntity value);
    LimitUsageEntity toEntity(LimitUsage value);
    LimitUsage toSnapshot(LimitUsageEntity value);
}
