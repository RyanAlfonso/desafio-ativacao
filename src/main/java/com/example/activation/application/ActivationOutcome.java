package com.example.activation.application;

import java.time.Duration;

/** Resultado de uma chamada ao provedor, já classificado pelo que fazer em seguida. */
public sealed interface ActivationOutcome {

    /** O provedor confirmou a ativação. */
    record Activated() implements ActivationOutcome {
    }

    /** O provedor recusou a solicitação; repetir não muda o resultado. */
    record Rejected(String reason) implements ActivationOutcome {
    }

    /**
     * Falha que pode se resolver sozinha (5xx, 429, timeout, erro de rede).
     *
     * @param retryAfter prazo pedido pelo provedor (header Retry-After), ou {@code null}
     */
    record TemporaryFailure(String reason, Duration retryAfter) implements ActivationOutcome {
    }
}
