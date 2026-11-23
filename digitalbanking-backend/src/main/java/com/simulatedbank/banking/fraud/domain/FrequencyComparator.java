package com.simulatedbank.banking.fraud.domain;

/**
 * Parameterized comparator for high-frequency fraud evaluation.
 * Accommodates both SRS proposal (> 5) and SRS generic / Design-B (>= 3).
 */
public enum FrequencyComparator {
    GREATER_THAN {
        @Override
        public boolean matches(int observedCount, int thresholdCount) {
            return observedCount > thresholdCount;
        }
    },
    GREATER_OR_EQUAL {
        @Override
        public boolean matches(int observedCount, int thresholdCount) {
            return observedCount >= thresholdCount;
        }
    };

    public abstract boolean matches(int observedCount, int thresholdCount);
}
