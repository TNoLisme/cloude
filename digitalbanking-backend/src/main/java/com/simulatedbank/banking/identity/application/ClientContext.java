package com.simulatedbank.banking.identity.application;

/**
 * Ngữ cảnh client đã mask từ adapter (IP qua PiiMaskingUtils, UA cắt 128 ký tự).
 * Không chứa device fingerprint raw hay credential nào.
 */
public record ClientContext(String correlationId, String maskedIp, String maskedUserAgent, String clientInfoJson) {
}
