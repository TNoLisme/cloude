package com.simulatedbank.banking.identity.application;

/**
 * Port sinh mã OTP (BE-ID-IMP-05 §5 — "cryptographically secure OTP generator").
 * Nguồn: `07. API contracts.md` §3.7 (OTP length 6 chữ số, cấu hình 6–8; đề xuất BRL-004);
 * `04. Database design & analysis.md` §4.2.5 (OTP single-use, TTL 5 phút, tối đa 5 lần thử).
 *
 * <p>Hợp đồng: trả `char[]` digits; caller chịu trách nhiệm hash ngay rồi wipe
 * (không plaintext persistence, không log, không trả client ngoài kênh delivery
 * BE-ID-IMP-06). Implement phải dùng CSPRNG.</p>
 */
public interface OtpCodeGenerator {

    /** Sinh OTP cho một lần dùng; mỗi lời gọi là một OTP mới. */
    char[] generate();
}
