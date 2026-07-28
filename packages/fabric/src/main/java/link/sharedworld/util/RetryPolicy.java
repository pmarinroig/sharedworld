package link.sharedworld.util;

/**
 * Bounded exponential backoff, pure and side-effect free (callers own the
 * sleeping and the retriable-error classification).
 */
public record RetryPolicy(int maxAttempts, long baseDelayMs, long maxDelayMs) {
    public RetryPolicy {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        if (baseDelayMs < 0 || maxDelayMs < baseDelayMs) {
            throw new IllegalArgumentException("delays must satisfy 0 <= base <= max");
        }
    }

    /** Whether another attempt is allowed after {@code attemptsMade} tries. */
    public boolean shouldRetry(int attemptsMade) {
        return attemptsMade < this.maxAttempts;
    }

    /** Delay before attempt number {@code nextAttempt} (2 = first retry). */
    public long delayBeforeAttemptMs(int nextAttempt) {
        if (nextAttempt <= 1) {
            return 0L;
        }
        long exponent = nextAttempt - 2L;
        if (exponent >= 62 || this.baseDelayMs == 0L) {
            return this.maxDelayMs;
        }
        long delay = this.baseDelayMs << Math.min(exponent, 32L);
        return Math.min(delay < 0 ? this.maxDelayMs : delay, this.maxDelayMs);
    }
}
