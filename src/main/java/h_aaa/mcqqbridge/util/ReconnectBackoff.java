package h_aaa.mcqqbridge.util;

import java.util.Random;

public final class ReconnectBackoff {
    private final long minMillis;
    private final long maxMillis;
    private final Random random;
    private int failures;

    public ReconnectBackoff(long minMillis, long maxMillis) {
        this(minMillis, maxMillis, new Random());
    }

    ReconnectBackoff(long minMillis, long maxMillis, Random random) {
        this.minMillis = minMillis;
        this.maxMillis = maxMillis;
        this.random = random;
    }

    public synchronized long nextDelayMillis() {
        int exponent = Math.min(failures, 20);
        long base;
        if (exponent >= 62 || minMillis > (Long.MAX_VALUE >> exponent)) {
            base = maxMillis;
        } else {
            base = Math.min(maxMillis, minMillis << exponent);
        }
        failures++;
        long jitter = Math.max(1L, base / 5L);
        long offset = (long) (random.nextDouble() * (jitter * 2L + 1L)) - jitter;
        return Math.max(minMillis, Math.min(maxMillis, base + offset));
    }

    public synchronized void reset() {
        failures = 0;
    }

    public synchronized int getFailures() {
        return failures;
    }
}
