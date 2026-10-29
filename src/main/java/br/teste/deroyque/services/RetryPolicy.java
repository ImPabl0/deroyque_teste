package br.teste.deroyque.services;

import br.teste.deroyque.config.ActivationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/** Backoff exponencial com jitter ("equal jitter") e limite de tentativas. */
@Component
public class RetryPolicy {

    private final ActivationProperties properties;

    public RetryPolicy(ActivationProperties properties) {
        this.properties = properties;
    }

    public boolean canRetry(int attemptsSoFar) {
        return attemptsSoFar < properties.maxAttempts();
    }

    /**
     * Metade da espera é fixa e a outra metade é aleatória, para que solicitações que falharam juntas
     * (ex.: provedor fora do ar) não voltem todas no mesmo instante.
     */
    public Duration delayAfter(int attemptsSoFar, Duration retryAfter) {
        long base = properties.initialBackoff().toMillis();
        long exponential = base << Math.min(Math.max(attemptsSoFar - 1, 0), 20);
        long capped = Math.min(exponential, properties.maxBackoff().toMillis());
        long half = capped / 2;
        long delay = half + ThreadLocalRandom.current().nextLong(half + 1);
        Duration computed = Duration.ofMillis(delay);
        if (retryAfter != null && retryAfter.compareTo(computed) > 0) {
            return retryAfter;
        }
        return computed;
    }
}
