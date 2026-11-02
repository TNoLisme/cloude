package com.bank.simulator.shared.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

public record Problem(
        URI type,
        String title,
        int status,
        String detail,
        String instance,
        String code,
        UUID correlationId,
        List<FieldError> fieldErrors
) {
}
