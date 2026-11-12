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
