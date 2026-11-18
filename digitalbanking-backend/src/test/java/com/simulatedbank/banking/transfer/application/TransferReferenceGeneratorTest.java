package com.simulatedbank.banking.transfer.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Proposed OQ-02 format only; the API owner has not frozen the reference shape. */
class TransferReferenceGeneratorTest {
    private static final String PROPOSED_FORMAT = "TXN[0-9]{8}[A-Z0-9]{8,20}";

    private final TransferReferenceGenerator generator = new TransferReferenceGenerator();

    @Test
    void generatedReferenceMatchesTheProposedFormatAndIdentityLengthBound() {
        for (int i = 0; i < 200; i++) {
            String reference = generator.generate();
            assertThat(reference).matches(PROPOSED_FORMAT);
            assertThat(reference.codePointCount(0, reference.length())).isBetween(1, 32);
        }
    }

    @Test
    void referencesAreDistinctAcrossGenerations() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) {
            assertThat(seen.add(generator.generate())).isTrue();
        }
    }

    @Test
    void injectedRandomSourceIsUsedSoGenerationIsReproducible() throws Exception {
        String first = new TransferReferenceGenerator(seededRandom()).generate();
        String second = new TransferReferenceGenerator(seededRandom()).generate();
        assertThat(first).isEqualTo(second).matches(PROPOSED_FORMAT);
    }

    private static SecureRandom seededRandom() throws Exception {
        SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
        random.setSeed(new byte[]{1, 2, 3});
        return random;
    }
}