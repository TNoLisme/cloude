package com.simulatedbank.banking.identity.application;

/** Stored hash không parse được hoặc dùng thuật toán/tham số không hỗ trợ. Lỗi nội bộ, không lộ ra ngoài. */
public class PasswordHashFormatException extends RuntimeException {
    public PasswordHashFormatException(String safeMessage) {
        super(safeMessage);
    }
}
