package com.simulatedbank.banking.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private final ProblemWriter writer;

    public GlobalExceptionHandler(ProblemWriter writer) {
        this.writer = writer;
    }

    @ExceptionHandler({BankingBusinessException.class, DataAccessException.class,
            AuthenticationException.class, AccessDeniedException.class, Exception.class})
    public ResponseEntity<ProblemResponse> handleFailure(Exception exception, HttpServletRequest request) {
        // An owner-classified failure takes precedence over its JDBC/framework cause, even when wrapped.
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = exception; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof BankingBusinessException business) {
                return writer.response(writer.create(business.code(), business.safeDetail(), request,
                        List.of(), business.extensions()));
            }
        }
        ErrorCode code;
        if (exception instanceof AuthenticationException) {
            code = ErrorCode.AUTH_SESSION_EXPIRED;
        } else if (exception instanceof AccessDeniedException) {
            code = ErrorCode.AUTH_FORBIDDEN;
        } else if (exception instanceof DataAccessException) {
            code = DatabaseErrorMapper.code(exception);
        } else {
            code = ErrorCode.INTERNAL_ERROR;
        }
        return writer.response(writer.create(code, code.detail(), request, List.of(), Map.of()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemResponse> handleConstraintValidation(ConstraintViolationException exception,
            HttpServletRequest request) {
        boolean returnValue = exception.getConstraintViolations().stream().anyMatch(violation -> {
            for (var node : violation.getPropertyPath()) {
                if (node.getKind() == ElementKind.RETURN_VALUE) { return true; }
            }
            return false;
        });
        ErrorCode code = returnValue ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_ERROR;
        List<ProblemResponse.FieldError> errors = returnValue ? List.of()
                : exception.getConstraintViolations().stream()
                        .map(violation -> fieldError(violation.getPropertyPath().toString())).toList();
        return writer.response(writer.create(code, code.detail(), request, errors, Map.of()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest webRequest) {
        HttpServletRequest request = ((ServletWebRequest) webRequest).getRequest();
        ErrorCode code;
        List<ProblemResponse.FieldError> errors = new ArrayList<>();
        if (exception instanceof MethodArgumentNotValidException validation) {
            code = ErrorCode.VALIDATION_ERROR;
            validation.getBindingResult().getFieldErrors().forEach(error -> errors.add(fieldError(error.getField())));
            validation.getBindingResult().getGlobalErrors().forEach(error -> errors.add(fieldError("request")));
        } else if (exception instanceof HandlerMethodValidationException validation) {
            code = validation.isForReturnValue() ? ErrorCode.INTERNAL_ERROR : ErrorCode.VALIDATION_ERROR;
            if (!validation.isForReturnValue()) {
                validation.getParameterValidationResults().forEach(result -> {
                    if (result instanceof org.springframework.validation.Errors binding) {
                        binding.getFieldErrors().forEach(error -> errors.add(fieldError(error.getField())));
                    } else {
                        errors.add(fieldError(result.getMethodParameter().getParameterName()));
                    }
                });
            }
        } else if (exception instanceof HttpMessageNotReadableException) {
            code = ErrorCode.MALFORMED_REQUEST;
        } else if (exception instanceof HttpMediaTypeNotSupportedException) {
            code = ErrorCode.UNSUPPORTED_MEDIA_TYPE;
        } else if (exception instanceof HttpRequestMethodNotSupportedException) {
            code = ErrorCode.METHOD_NOT_ALLOWED;
        } else if (exception instanceof NoResourceFoundException || exception instanceof NoHandlerFoundException) {
            code = ErrorCode.NOT_FOUND;
        } else if (status.value() == 400) {
            code = ErrorCode.VALIDATION_ERROR;
            errors.add(fieldError("request"));
        } else {
            code = ErrorCode.INTERNAL_ERROR;
        }
        ResponseEntity<ProblemResponse> response = writer.response(
                writer.create(code, code.detail(), request, errors, Map.of()));
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(response.getHeaders());
        if (code == ErrorCode.METHOD_NOT_ALLOWED) {
            responseHeaders.setAllow(headers.getAllow());
        }
        return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());
    }

    private ProblemResponse.FieldError fieldError(String field) {
        String safeField = field != null && field.length() <= 128
                && field.matches("[A-Za-z_$][A-Za-z0-9_.$]*(\\[[0-9]{1,4}\\])?(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")
                ? field : "request";
        return new ProblemResponse.FieldError(safeField, "INVALID", "Giá trị không hợp lệ.");
    }
}
