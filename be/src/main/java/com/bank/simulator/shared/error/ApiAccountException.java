package com.bank.simulator.shared.error;

import org.springframework.http.HttpStatus;

public class ApiAccountException extends ApiException {
    public ApiAccountException(HttpStatus status, String code, String detail) {
        super(status, code, detail);
    }
}
