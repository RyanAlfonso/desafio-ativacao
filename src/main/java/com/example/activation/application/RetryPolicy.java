package com.example.activation.application;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Backoff exponencial com jitter ("equal jitter"), limitado por {@code maxBackoff}. */
@Component
public class RetryPolicy {

    private final DispatchProperties properties;

    public RetryPolicy(DispatchProperties properties) {
        this.properties = properties;
    }

    public boolean canRetry(int attemptsSoFar) {
        return attemptsSoFar < properties.maxAttempts();
    }

    /**
     * @param attemptsSoFar tentativas já feitas (1 após a primeira falha)
     * @param retryAfter    prazo pedido pelo provedor; prevalece sobre o backoff calculado
     */
    public Duration delayBefore(int attemptsSoFar, Duration retryAfter) {
        Duration max = properties.maxBackoff();
        if (retryAfter != null) {
            return min(retryAfter, max);
        }
        long exponent = Math.min(attemptsSoFar - 1, 20);
        Duration exponential = min(properties.baseBackoff().multipliedBy(1L << exponent), max);
        long halfMillis = exponential.toMillis() / 2;
        long jitter = halfMillis == 0 ? 0 : ThreadLocalRandom.current().nextLong(halfMillis + 1);
        return Duration.ofMillis(halfMillis + jitter);
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
