package com.simulatedbank.banking.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.simulatedbank.banking.identity.adapter.out.Argon2PasswordHasher;
import com.simulatedbank.banking.identity.application.PasswordHashFormatException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** T06/T07 + dummy hash (design/04 §4.1): tham số Argon2id đã duyệt, verify constant-time. */
class Argon2PasswordHasherTest {

    private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();

    // T06: hash hợp lệ → đúng format PHC, salt 16 byte, hash 32 byte, tham số m/t/p đã duyệt.
    @Test
    void t06_validHashMatchesFormatAndParameters() {
        String hash = hasher.hash("P@ssw0rd!".toCharArray());
        assertThat(hash).startsWith("$argon2id$v=19$m=65536,t=3,p=4$");
        String[] parts = hash.split("\\$");
        assertThat(parts).hasSize(6);
        assertThat(Base64.getDecoder().decode(pad(parts[4]))).hasSize(16); // salt
        assertThat(Base64.getDecoder().decode(pad(parts[5]))).hasSize(32); // digest
    }

    // T07: verify đúng mật khẩu → true; sai → false.
    @Test
    void t07_verificationAcceptsOnlyCorrectPassword() {
        String hash = hasher.hash("P@ssw0rd!".toCharArray());
        assertThat(hasher.matches(hash, "P@ssw0rd!".toCharArray())).isTrue();
        assertThat(hasher.matches(hash, "wrong".toCharArray())).isFalse();
    }

    // T08: hash sai định dạng / thuật toán khác / tham số sai → PasswordHashFormatException (safe).
    @Test
    void t08_malformedHashesRejected() {
        assertThatThrownBy(() -> hasher.matches("plaintext", "x".toCharArray()))
                .isInstanceOf(PasswordHashFormatException.class);
        assertThatThrownBy(() -> hasher.matches(
                "$argon2i$v=19$m=65536,t=3,p=4$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "x".toCharArray()))
                .isInstanceOf(PasswordHashFormatException.class); // argon2i không phải argon2id
        assertThatThrownBy(() -> hasher.matches(
                "$argon2id$v=16$m=65536,t=3,p=4$AAAAAAAAAAAAAAAAAAAAAA$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "x".toCharArray()))
                .isInstanceOf(PasswordHashFormatException.class); // version không hỗ trợ
        assertThatThrownBy(() -> hasher.matches(
                "$argon2id$v=19$m=65536,t=3,p=4$bad-base64!!$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "x".toCharArray()))
                .isInstanceOf(PasswordHashFormatException.class);
    }

    // T03 support: dummy hash là Argon2id hợp lệ, verify được (chi phí đầy đủ), và ổn định giữa các lần gọi.
    @Test
    void dummyHashIsValidArgon2idAndStable() {
        String first = hasher.dummyHash();
        String second = hasher.dummyHash();
        assertThat(first).startsWith("$argon2id$v=19$m=65536,t=3,p=4$").isEqualTo(second);
        // Không phải hash của password thật nào cả; verify với password bất kỳ trả false, không ném.
        assertThat(hasher.matches(first, "anything".toCharArray())).isFalse();
    }

    private static String pad(String base64NoPadding) {
        int rem = base64NoPadding.length() % 4;
        return rem == 0 ? base64NoPadding : base64NoPadding + "=".repeat(4 - rem);
    }
}
