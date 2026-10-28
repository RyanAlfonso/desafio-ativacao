package com.example.activation.application;

import java.util.UUID;

/** Porta para o serviço externo de ativação. */
public interface ActivationProvider {

    /**
     * Solicita a ativação. Nunca lança exceção por erro do provedor: todo retorno é
     * traduzido em um {@link ActivationOutcome}.
     *
     * @param requestId usado também como chave de idempotência no provedor
     */
    ActivationOutcome activate(UUID requestId, String customerId, String productCode, String correlationId);
}
