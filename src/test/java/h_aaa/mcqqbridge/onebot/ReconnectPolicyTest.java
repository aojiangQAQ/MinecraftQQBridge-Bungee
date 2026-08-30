package h_aaa.mcqqbridge.onebot;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconnectPolicyTest {
    @Test
    void appliesExponentialCapAndJitterWithinBounds() {
        ReconnectPolicy policy = new ReconnectPolicy(100L, 800L, new Random(11L));
        for (int i = 1; i <= 12; i++) {
            long delay = policy.nextDelayMillis();
            assertTrue(delay >= 100L && delay <= 800L);
            assertEquals(i, policy.getFailures());
        }

        policy.reset();
        assertEquals(0, policy.getFailures());
        long first = policy.nextDelayMillis();
        assertTrue(first >= 100L && first <= 120L);
    }
}
