package com.simulatedbank.banking.account.domain;

import java.util.regex.Pattern;

/**
 * Số tài khoản đã chuẩn hóa trong phạm vi module Account (BE-ACC-03).
 *
 * <p>Định dạng được duyệt là {@code ^[0-9]{6,20}$} (OpenAPI {@code DestinationAccountNumber},
 * BE-ACC-01 §C). Quy tắc normalize là <b>strict</b>, không suy diễn thêm:</p>
 * <ul>
 *   <li>Không trim khoảng trắng: input có space/prefix/suffix bị từ chối thay vì tự sửa, để
 *       không tạo hai biểu diễn khác nhau của cùng một số tài khoản.</li>
 *   <li>Không bỏ số 0 ở đầu: biểu diễn đúng là chuỗi chữ số client gửi và là input của
 *       HMAC-SHA256 blind index; đổi biểu diễn sẽ làm lookup trượt hoặc trùng sai.</li>
 *   <li>Chỉ chữ số ASCII; các ký tự Unicode digit khác bị từ chối.</li>
 * </ul>
 *
 * <p>Đây là value object nội bộ ({@code account.domain} không được module khác import —
 * ArchUnit). Không log/`toString` plaintext; chỉ {@code last4()} được phép ra khỏi biên module
 * (qua masked projection).</p>
 */
public final class AccountNumber {

    private static final Pattern APPROVED_FORMAT = Pattern.compile("^[0-9]{6,20}$");
    private static final int LAST4_LENGTH = 4;

    private final String digits;

    private AccountNumber(String digits) {
        this.digits = digits;
    }

    /**
     * Chuẩn hóa input theo đúng format đã duyệt.
     *
     * @throws IllegalArgumentException khi {@code raw} null hoặc không khớp {@code ^[0-9]{6,20}$};
     *         message không chứa input để không rò số tài khoản vào log/exception
     */
    public static AccountNumber parse(String raw) {
        if (raw == null || !APPROVED_FORMAT.matcher(raw).matches()) {
            throw new IllegalArgumentException("account number must match ^[0-9]{6,20}$");
        }
        return new AccountNumber(raw);
    }

    /** Chuỗi chữ số đã duyệt — chỉ dùng trong module (HMAC blind index, encryption boundary). */
    public String digits() {
        return digits;
    }

    /** Bốn ký tự cuối dùng cho `**** **** <last4>` (04 §4.3.1). */
    public String last4() {
        return digits.substring(digits.length() - LAST4_LENGTH);
    }

    @Override
    public String toString() {
        return "AccountNumber[redacted]";
    }
}
