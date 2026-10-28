package com.example.activation.domain;

import java.util.Set;

/**
 * Ciclo de vida de uma solicitação de ativação.
 *
 * <pre>
 * PENDING ──► PROCESSING ──► COMPLETED
 *                │  ▲   └──► FAILED
 *                └──┘ (nova tentativa após falha temporária)
 * </pre>
 *
 * Uma falha temporária mantém a solicitação em PROCESSING, com o contador de tentativas
 * incrementado e a data da próxima tentativa agendada. COMPLETED e FAILED são finais.
 */
public enum RequestStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean canTransitionTo(RequestStatus target) {
        return allowedTargets().contains(target);
    }

    public boolean isFinal() {
        return allowedTargets().isEmpty();
    }

    private Set<RequestStatus> allowedTargets() {
        return switch (this) {
            case PENDING -> Set.of(PROCESSING);
            case PROCESSING -> Set.of(PROCESSING, COMPLETED, FAILED);
            case COMPLETED, FAILED -> Set.of();
        };
    }
}
