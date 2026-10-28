package com.example.activation.application;

import com.example.activation.domain.ActivationRequest;

/**
 * Resultado da criação: {@code created = false} indica que o externalId já existia com o
 * mesmo conteúdo (retry do sistema de origem) e o registro existente foi devolvido.
 */
public record CreationResult(ActivationRequest request, boolean created) {

    static CreationResult created(ActivationRequest request) {
        return new CreationResult(request, true);
    }

    static CreationResult replayed(ActivationRequest request) {
        return new CreationResult(request, false);
    }
}
