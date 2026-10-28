package com.example.activation.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param lease        tempo de reserva de uma tentativa; precisa ser maior que connect + read timeout
 * @param maxAttempts  tentativas antes de marcar a solicitação como FAILED
 * @param baseBackoff  espera antes da 2ª tentativa; dobra a cada tentativa
 * @param maxBackoff   teto da espera (também limita o Retry-After do provedor)
 */
@ConfigurationProperties("app.dispatch")
public record DispatchProperties(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        Duration lease,
        int maxAttempts,
        Duration baseBackoff,
        Duration maxBackoff) {
}
