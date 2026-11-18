package com.simulatedbank.banking.transfer.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.function.Supplier;

/** Gauges read a cached primary snapshot. Scraping never queries the database. */
public final class TransferPendingMetrics {
    private final Supplier<Snapshot> readPrimary;
    private final io.micrometer.core.instrument.Clock clock;
    private volatile Sample sample;
    private volatile boolean available;

    public TransferPendingMetrics(MeterRegistry registry, Supplier<Snapshot> readPrimary) {
        this.readPrimary = Objects.requireNonNull(readPrimary);
        this.clock = registry.config().clock();
        registry.gauge("transfer.pending.count", this, TransferPendingMetrics::count);
        registry.gauge("transfer.pending.age.seconds", this, TransferPendingMetrics::oldestAge);
        registry.gauge("transfer.pending.snapshot.age.seconds", this, TransferPendingMetrics::snapshotAge);
        registry.gauge("transfer.pending.snapshot.available", this, metrics -> metrics.available ? 1 : 0);
    }

    public synchronized void refresh() {
        try {
            sample = new Sample(Objects.requireNonNull(readPrimary.get()), clock.monotonicTime());
            available = true;
        } catch (RuntimeException failure) {
            // Keep last successful sample and its timestamp. Missing data remains unknown.
            available = false;
        }
    }

    private double count() {
        var last = sample;
        return last == null ? Double.NaN : last.snapshot().count();
    }

    private double oldestAge() {
        var last = sample;
        return last == null ? Double.NaN : last.snapshot().oldestAgeSeconds();
    }

    private double snapshotAge() {
        var last = sample;
        return last == null ? Double.NaN : (clock.monotonicTime() - last.sampledAt()) / 1_000_000_000.0;
    }

    private record Sample(Snapshot snapshot, long sampledAt) { }

    public record Snapshot(long count, double oldestAgeSeconds) {
        public Snapshot {
            if (count < 0 || !Double.isFinite(oldestAgeSeconds) || oldestAgeSeconds < 0
                    || (count == 0 && oldestAgeSeconds != 0)) {
                throw new IllegalArgumentException("Invalid pending snapshot");
            }
        }
    }
}
