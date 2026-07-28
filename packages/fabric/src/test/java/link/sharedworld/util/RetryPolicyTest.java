package link.sharedworld.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RetryPolicyTest {
    @Test
    void backoffDoublesFromBaseAndCapsAtMax() {
        RetryPolicy policy = new RetryPolicy(5, 500L, 4_000L);
        assertEquals(0L, policy.delayBeforeAttemptMs(1));
        assertEquals(500L, policy.delayBeforeAttemptMs(2));
        assertEquals(1_000L, policy.delayBeforeAttemptMs(3));
        assertEquals(2_000L, policy.delayBeforeAttemptMs(4));
        assertEquals(4_000L, policy.delayBeforeAttemptMs(5));
        assertEquals(4_000L, policy.delayBeforeAttemptMs(6));
    }

    @Test
    void shouldRetryStopsAtMaxAttempts() {
        RetryPolicy policy = new RetryPolicy(3, 1L, 10L);
        assertTrue(policy.shouldRetry(1));
        assertTrue(policy.shouldRetry(2));
        assertFalse(policy.shouldRetry(3));
    }

    @Test
    void hugeAttemptNumbersStayCapped() {
        RetryPolicy policy = new RetryPolicy(100, 500L, 60_000L);
        assertEquals(60_000L, policy.delayBeforeAttemptMs(80));
    }

    @Test
    void invalidConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, 1L, 10L));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, 10L, 5L));
    }
}
