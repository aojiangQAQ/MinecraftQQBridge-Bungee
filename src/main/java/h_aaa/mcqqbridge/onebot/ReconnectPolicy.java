package h_aaa.mcqqbridge.onebot;

import java.util.Random;

final class ReconnectPolicy {
    private final long minMillis;
    private final long maxMillis;
    private final Random random;
    private int failures;

    ReconnectPolicy(long minMillis, long maxMillis, Random random) {
        if (minMillis <= 0L || maxMillis < minMillis) {
            throw new IllegalArgumentException("Invalid reconnect bounds");
        }
        this.minMillis = minMillis;
        this.maxMillis = maxMillis;
        this.random = random;
    }

    synchronized long nextDelayMillis() {
        int exponent = Math.min(failures, 20);
        long base = minMillis;
        for (int i = 0; i < exponent && base < maxMillis; i++) {
            base = Math.min(maxMillis, base > maxMillis / 2L ? maxMillis : base * 2L);
        }
        failures++;

        long jitter = Math.max(1L, base / 5L);
        long offset = (long) (random.nextDouble() * (jitter * 2.0d + 1.0d)) - jitter;
        return Math.max(minMillis, Math.min(maxMillis, base + offset));
    }

    synchronized void reset() {
        failures = 0;
    }

    synchronized int getFailures() {
        return failures;
    }
}
