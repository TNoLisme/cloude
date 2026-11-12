package com.simulatedbank.banking.common.error;

import java.net.URI;
import java.util.Locale;

/** Public wire catalog from API contracts §4.3; internal event codes do not belong here. */
public enum ErrorCode {
    AUTH_INVALID_CREDENTIALS(401, false, "Auth invalid credentials"),
    AUTH_ACCOUNT_LOCKED(423, false, "Auth account locked"),
    AUTH_SESSION_EXPIRED(401, true, "Auth session expired"),
    AUTH_FORBIDDEN(403, false, "Auth forbidden"),
    AUTH_DEVICE_NOT_TRUSTED(403, false, "Auth device not trusted"),
    AUTH_CHALLENGE_NOT_FOUND(404, false, "Auth challenge not found"),
    AUTH_CHALLENGE_EXPIRED(410, true, "Auth challenge expired"),
    AUTH_CHALLENGE_LOCKED(423, false, "Auth challenge locked"),
    AUTH_OTP_INVALID(401, true, "Auth otp invalid"),
    AUTH_OTP_EXPIRED(410, true, "Auth otp expired"),
    STEP_UP_REQUIRED(428, true, "Step up required"),
    STEP_UP_CHALLENGE_MISMATCH(409, false, "Step up challenge mismatch"),
    OTP_DISPATCH_FAILED(503, true, "Otp dispatch failed"),
    VALIDATION_ERROR(400, false, "Validation failed"),
    MALFORMED_REQUEST(400, false, "Malformed request"),
    UNSUPPORTED_MEDIA_TYPE(415, false, "Unsupported media type"),
    REQUEST_TOO_LARGE(413, false, "Request too large"),
    METHOD_NOT_ALLOWED(405, false, "Method not allowed"),
    NOT_FOUND(404, false, "Not found"),
    RATE_LIMITED(429, true, "Rate limited"),
    ACCOUNT_NOT_FOUND(404, false, "Account not found"),
    ACCOUNT_INACTIVE(409, false, "Account inactive"),
    INSUFFICIENT_BALANCE(422, false, "Insufficient balance"),
    LIMIT_EXCEEDED(422, false, "Limit exceeded"),
    LIMIT_OUT_OF_POLICY(422, false, "Limit out of policy"),
    TRANSFER_NOT_FOUND(404, false, "Transfer not found"),
    TRANSFER_FAILED(422, false, "Transfer failed"),
    FRAUD_BLOCKED(422, false, "Fraud blocked"),
    FRAUD_FLAG_NOT_FOUND(404, false, "Fraud flag not found"),
    FRAUD_FLAG_ALREADY_CLOSED(409, false, "Fraud flag already closed"),
    INVALID_STATE_TRANSITION(409, false, "Invalid state transition"),
    CUSTOMER_NOT_FOUND(404, false, "Customer not found"),
    AUDIT_NOT_FOUND(404, false, "Audit not found"),
    EXPORT_TOO_LARGE(413, false, "Export too large"),
    IDEMPOTENCY_CONFLICT(409, false, "Idempotency conflict"),
    IDEMPOTENCY_IN_PROGRESS(409, true, "Idempotency in progress"),
    APPROVAL_NOT_FOUND(404, false, "Approval not found"),
    APPROVAL_ALREADY_DECIDED(409, false, "Approval already decided"),
    ROLE_CONFLICT(409, false, "Role conflict"),
    ROLE_NOT_FOUND(404, false, "Role not found"),
    USER_NOT_FOUND(404, false, "User not found"),
    LEGAL_HOLD_ACTIVE(409, false, "Legal hold active"),
    AUDIT_WRITE_FAILED(500, true, "Audit write failed"),
    INTERNAL_ERROR(500, true, "Internal error"),
    SERVICE_UNAVAILABLE(503, true, "Service unavailable"),
    GATEWAY_TIMEOUT(504, true, "Gateway timeout");

    private final int httpStatus;
    private final boolean retryable;
    private final String title;

    ErrorCode(int httpStatus, boolean retryable, String title) {
        this.httpStatus = httpStatus;
        this.retryable = retryable;
        this.title = title;
    }

    public int httpStatus() { return httpStatus; }
    public boolean retryable() { return retryable; }
    public String title() { return title; }
    public URI type() {
        return URI.create("https://api.demo.local/problems/" + name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    public String detail() {
        return switch (this) {
            case VALIDATION_ERROR -> "Dữ liệu yêu cầu không hợp lệ.";
            case MALFORMED_REQUEST -> "Không đọc được nội dung yêu cầu JSON.";
            case UNSUPPORTED_MEDIA_TYPE -> "Kiểu nội dung yêu cầu không được hỗ trợ.";
            case METHOD_NOT_ALLOWED -> "Phương thức yêu cầu không được hỗ trợ.";
            case NOT_FOUND -> "Không tìm thấy tài nguyên yêu cầu.";
            case AUTH_SESSION_EXPIRED -> "Phiên đăng nhập không hợp lệ hoặc đã hết hạn.";
            case AUTH_FORBIDDEN -> "Bạn không có quyền thực hiện yêu cầu này.";
            case INTERNAL_ERROR -> "Không thể xử lý yêu cầu. Vui lòng thử lại với thao tác an toàn.";
            case SERVICE_UNAVAILABLE -> "Dịch vụ tạm thời không khả dụng. Vui lòng thử lại sau.";
            default -> "Không thể thực hiện yêu cầu. Vui lòng kiểm tra mã lỗi và điều kiện thử lại.";
        };
    }
}
