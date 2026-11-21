package com.simulatedbank.banking.admin.adapter.in;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/** API-LIM-02 request body (07 §8.5.2); ID and Money are strings on the wire. */
public record LimitChangeHttpRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) String customerId,
        @JsonDeserialize(using = StrictStringDeserializer.class) String accountId,
        @JsonDeserialize(using = StrictStringDeserializer.class) String perTransactionLimit,
        @JsonDeserialize(using = StrictStringDeserializer.class) String dailyLimit,
        @JsonDeserialize(using = StrictStringDeserializer.class) String currency,
        @JsonDeserialize(using = StrictStringDeserializer.class) String effectiveFrom,
        @JsonDeserialize(using = StrictStringDeserializer.class) String reason) {
}
