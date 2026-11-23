package com.simulatedbank.banking.common.crypto;

public final class PiiMaskingUtils {
    private PiiMaskingUtils() {
    }

    public static String maskAccountLast4(String last4) {
        return maskLast4(last4, "**** **** ");
    }

    public static String maskNationalIdLast4(String last4) {
        return maskLast4(last4, "********");
    }

    private static String maskLast4(String last4, String prefix) {
        if (last4 == null) {
            return null;
        }
        if (!last4.matches("[0-9]{4}")) {
            throw invalid();
        }
        return prefix + last4;
    }

    public static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        if (!phone.matches("\\+?[0-9]{4,15}")) {
            throw invalid();
        }
        return "********" + phone.substring(phone.length() - 3);
    }

    public static String maskEmail(String email) {
        if (email == null) {
            return null;
        }
        // Conservative display boundary, not an email-address/business validator.
        if (!email.matches("[A-Za-z0-9][A-Za-z0-9._%+\\-]*@[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?"
                + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]*[A-Za-z0-9])?)+")) {
            throw invalid();
        }
        return email.substring(0, 1) + "***" + email.substring(email.indexOf('@'));
    }

    /**
     * Staff-facing {@code fullName} masking (BE-CUS-IMP-06; DEC-CUS-005 — oracle CUS-OI-02 vẫn
     * chờ owner sign-off, đây là quy tắc bảo thủ tạm thời): mỗi token (phân tách bằng khoảng
     * trắng, đã chuẩn hóa) chỉ giữ ký tự đầu nếu token dài từ 2 ký tự, các ký tự còn lại thay
     * bằng `***`; token một ký tự bị che toàn bộ. Ví dụ `"Nguyễn Văn An"` → `"N*** V*** A***"`.
     * Không bao giờ trả nguyên văn hay một phần tên dài hơn quy tắc trên.
     */
    public static String maskFullName(String fullName) {
        if (fullName == null) {
            return null;
        }
        String[] tokens = fullName.trim().split("\\s+");
        if (tokens.length == 1 && tokens[0].isEmpty()) {
            throw invalid();
        }
        StringBuilder masked = new StringBuilder();
        for (String token : tokens) {
            if (masked.length() > 0) {
                masked.append(' ');
            }
            masked.append(token.length() >= 2 ? token.charAt(0) + "***" : "***");
        }
        return masked.toString();
    }

    public static String maskIp(String ip) {
        if (ip == null) {
            return null;
        }
        // No IPv6 display policy is baselined; omit it rather than disclose it.
        if (ip.contains(":")) {
            return null;
        }
        String[] octets = ip.split("\\.", -1);
        if (octets.length != 4) {
            throw invalid();
        }
        for (String octet : octets) {
            if (!octet.matches("0|[1-9][0-9]{0,2}") || Integer.parseInt(octet) > 255) {
                throw invalid();
            }
        }
        return octets[0] + "." + octets[1] + "." + octets[2] + ".***";
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid masking input");
    }
}
