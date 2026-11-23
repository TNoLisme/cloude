package com.simulatedbank.banking.limit.adapter.in;

import com.simulatedbank.banking.limit.application.LimitsResult;
import com.simulatedbank.banking.limit.application.port.LimitReadPort.Row;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

@Mapper
interface LimitViewMapper {
    @Mapping(target = "effectivePerTransactionLimit", source = "effectivePerTransactionLimit", qualifiedByName = "money")
    @Mapping(target = "effectiveRemainingDaily", source = "effectiveRemainingDaily", qualifiedByName = "money")
    LimitsResponse toResponse(LimitsResult result);

    @Mapping(target = "scope", expression = "java(row.accountId() == null ? \"CUSTOMER\" : \"ACCOUNT\")")
    @Mapping(target = "perTransactionLimit", source = "perTransactionLimit", qualifiedByName = "money")
    @Mapping(target = "dailyLimit", source = "dailyLimit", qualifiedByName = "money")
    @Mapping(target = "usedToday", source = "usedToday", qualifiedByName = "money")
    @Mapping(target = "remainingDaily", expression = "java(money(row.dailyLimit().subtract(row.usedToday()).max(java.math.BigDecimal.ZERO)))")
    LimitsResponse.LimitView toView(Row row);

    @Named("money")
    default String money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY).toPlainString();
    }
}
