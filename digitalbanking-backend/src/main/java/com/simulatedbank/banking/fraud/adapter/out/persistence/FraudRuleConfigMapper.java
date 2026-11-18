package com.simulatedbank.banking.fraud.adapter.out.persistence;

import com.simulatedbank.banking.fraud.domain.FraudRuleConfig;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface FraudRuleConfigMapper {

    FraudRuleConfig toDomain(FraudRuleConfigEntity entity);

    @Mapping(target = "isNew", ignore = true)
    FraudRuleConfigEntity toEntity(FraudRuleConfig domain);
}
