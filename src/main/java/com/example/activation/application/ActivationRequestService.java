package com.example.activation.application;

import com.example.activation.domain.ActivationRequest;
import com.example.activation.domain.RequestStatus;
import com.example.activation.persistence.ActivationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static com.example.activation.persistence.ActivationRequestSpecifications.hasCustomer;
import static com.example.activation.persistence.ActivationRequestSpecifications.hasStatus;
import static com.example.activation.persistence.ActivationRequestSpecifications.ownedBy;

@Service
public class ActivationRequestService {

    private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

    private final ActivationRequestRepository repository;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Registra a solicitação. A unicidade de {@code externalId} é garantida pela constraint
     * única do banco: o "check-then-insert" sozinho teria uma janela entre o SELECT e o
     * INSERT em que duas requisições concorrentes passariam pela verificação.
     *
     * <p>Sem {@code @Transactional} de propósito: o INSERT roda na sua própria transação e,
     * se violar a constraint, a releitura do registro vencedor acontece numa transação nova
     * (no PostgreSQL, uma transação que sofreu erro não aceita mais comandos).
     */
    public CreationResult create(NewActivationRequest command, String clientId, String correlationId) {
        var existing = repository.findByExternalId(command.externalId());
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), command, clientId);
        }

        var request = ActivationRequest.create(command.externalId(), clientId, command.customerId(),
                command.productCode(), correlationId, now());
        try {
            repository.saveAndFlush(request);
            log.info("Solicitação criada id={} externalId={}", request.getId(), request.getExternalId());
            return CreationResult.created(request);
        } catch (DataIntegrityViolationException e) {
            // Outra requisição com o mesmo externalId venceu a corrida entre o SELECT e o INSERT.
            var winner = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
            return replayOrConflict(winner, command, clientId);
        }
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id, String clientId) {
        // Filtrar pelo dono evita que um consumidor leia solicitações de outro (BOLA).
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new RequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> search(RequestStatus status, String customerId, String clientId, Pageable pageable) {
        Specification<ActivationRequest> spec = Specification.where(ownedBy(clientId))
                .and(hasStatus(status))
                .and(hasCustomer(customerId));
        return repository.findAll(spec, pageable);
    }

    private CreationResult replayOrConflict(ActivationRequest existing, NewActivationRequest command, String clientId) {
        if (existing.hasSamePayload(clientId, command.customerId(), command.productCode())) {
            return CreationResult.replayed(existing);
        }
        log.warn("externalId={} reutilizado com dados diferentes (id existente={})",
                command.externalId(), existing.getId());
        throw new ExternalIdConflictException(command.externalId());
    }

    private Instant now() {
        // PostgreSQL guarda microssegundos; truncar mantém a resposta do POST igual à do GET.
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
