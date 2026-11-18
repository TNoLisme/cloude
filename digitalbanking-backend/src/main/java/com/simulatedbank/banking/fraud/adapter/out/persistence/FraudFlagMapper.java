package com.simulatedbank.banking.fraud.adapter.out.persistence;

import com.simulatedbank.banking.fraud.domain.FraudFlagItem;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface FraudFlagMapper {

    FraudFlagItem toDomain(FraudFlagEntity entity);

    FraudFlagEntity toEntity(FraudFlagItem domain);
}
