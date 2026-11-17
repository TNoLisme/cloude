package com.simulatedbank.transfercandidate.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

/** GET receipt: TransferResponse flattened plus fraudFlags omitted unless staff is authorized. */
public record TransferDetail(@JsonUnwrapped TransferResponse transfer,
                             @JsonInclude(JsonInclude.Include.NON_NULL) List<FraudFlagSummary> fraudFlags) {
}