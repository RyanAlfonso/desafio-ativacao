package com.example.activation.application;

import com.example.activation.application.ActivationOutcome.Activated;
import com.example.activation.application.ActivationOutcome.Rejected;
import com.example.activation.application.ActivationOutcome.TemporaryFailure;
import com.example.activation.domain.ActivationRequest;
import com.example.activation.domain.RequestStatus;
import com.example.activation.persistence.ActivationRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Envia ao provedor as solicitações vencidas (novas, com retry agendado ou com lease expirado).
 *
 * <p>Cada envio tem três passos, com a chamada HTTP fora de qualquer transação:
 * <ol>
 *   <li>transação curta: bloqueia o lote ({@code SKIP LOCKED}), marca PROCESSING e reserva por um lease;</li>
 *   <li>chamada ao provedor, sem conexão de banco presa;</li>
 *   <li>transação curta: aplica o resultado, se a tentativa ainda for a vigente.</li>
 * </ol>
 * Se a aplicação cair entre 1 e 3, o lease expira e outra execução reenvia; a chave de
 * idempotência (id da solicitação) evita ativação duplicada no provedor.
 */
@Component
public class ActivationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ActivationDispatcher.class);

    private final ActivationRequestRepository repository;
    private final ActivationProvider provider;
    private final RetryPolicy retryPolicy;
    private final DispatchProperties properties;
    private final TransactionTemplate transaction;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    public ActivationDispatcher(ActivationRequestRepository repository, ActivationProvider provider,
                                RetryPolicy retryPolicy, DispatchProperties properties,
                                TransactionTemplate transaction, MeterRegistry meterRegistry, Clock clock) {
        this.repository = repository;
        this.provider = provider;
        this.retryPolicy = retryPolicy;
        this.properties = properties;
        this.transaction = transaction;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
    }

    /** @return quantas solicitações foram processadas neste ciclo */
    public int dispatchDue() {
        List<Attempt> attempts = claimDue();
        attempts.forEach(this::process);
        return attempts.size();
    }

    private List<Attempt> claimDue() {
        List<Attempt> claimed = new ArrayList<>();
        List<RequestStatus> transitions = new ArrayList<>();
        transaction.executeWithoutResult(status -> {
            Instant now = now();
            for (ActivationRequest request : repository.lockDueForDispatch(now, properties.batchSize())) {
                if (request.getStatus() == RequestStatus.PROCESSING && !retryPolicy.canRetry(request.getAttempts())) {
                    // Lease expirado depois da última tentativa permitida (ex.: a instância caiu durante a chamada).
                    request.fail("Limite de tentativas atingido: sem resposta da última tentativa", now);
                    log.error("Solicitação {} marcada como FAILED: lease expirado na tentativa {}",
                            request.getId(), request.getAttempts());
                    transitions.add(RequestStatus.FAILED);
                    continue;
                }
                request.startAttempt(now, properties.lease());
                claimed.add(Attempt.of(request));
                transitions.add(RequestStatus.PROCESSING);
            }
        });
        transitions.forEach(this::countTransition);
        return claimed;
    }

    private void process(Attempt attempt) {
        MDC.put("traceId", attempt.correlationId());
        try {
            ActivationOutcome outcome = callProvider(attempt);
            applyOutcome(attempt, outcome).ifPresent(this::countTransition);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("Solicitação {} alterada concorrentemente; resultado da tentativa {} descartado",
                    attempt.requestId(), attempt.number());
        } catch (RuntimeException e) {
            // Falha ao gravar o resultado: a solicitação continua reservada e volta quando o lease expirar.
            log.error("Erro inesperado gravando o resultado da solicitação {}", attempt.requestId(), e);
        } finally {
            MDC.remove("traceId");
        }
    }

    private ActivationOutcome callProvider(Attempt attempt) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ActivationOutcome outcome;
        try {
            outcome = provider.activate(
                    attempt.requestId(), attempt.customerId(), attempt.productCode(), attempt.correlationId());
        } catch (RuntimeException e) {
            // Bug ou configuração inválida no cliente: trata como falha temporária para seguir backoff e limite.
            log.error("Erro inesperado chamando o provedor para a solicitação {}", attempt.requestId(), e);
            outcome = new TemporaryFailure("Erro inesperado ao chamar o provedor", null);
        }
        sample.stop(meterRegistry.timer("activation.provider.calls",
                "outcome", outcome.getClass().getSimpleName()));
        return outcome;
    }

    /** @return o novo status, ou vazio se a tentativa não era mais a vigente */
    private Optional<RequestStatus> applyOutcome(Attempt attempt, ActivationOutcome outcome) {
        return transaction.execute(status -> {
            ActivationRequest request = repository.findById(attempt.requestId()).orElseThrow();
            if (request.getStatus().isFinal() || request.getAttempts() != attempt.number()) {
                log.info("Tentativa {} da solicitação {} não é mais a vigente; resultado descartado",
                        attempt.number(), request.getId());
                return Optional.empty();
            }
            Instant now = now();
            switch (outcome) {
                case Activated activated -> {
                    request.complete(now);
                    log.info("Solicitação {} ativada na tentativa {}", request.getId(), attempt.number());
                }
                case Rejected rejected -> {
                    request.fail(rejected.reason(), now);
                    log.warn("Solicitação {} recusada pelo provedor: {}", request.getId(), rejected.reason());
                }
                case TemporaryFailure failure when retryPolicy.canRetry(request.getAttempts()) -> {
                    Instant retryAt = now.plus(retryPolicy.delayBefore(request.getAttempts(), failure.retryAfter()));
                    request.scheduleRetry(retryAt, failure.reason(), now);
                    log.warn("Falha temporária na solicitação {} (tentativa {}): {}; nova tentativa em {}",
                            request.getId(), attempt.number(), failure.reason(), retryAt);
                }
                case TemporaryFailure failure -> {
                    request.fail("Limite de tentativas atingido: " + failure.reason(), now);
                    log.error("Solicitação {} marcada como FAILED após {} tentativas: {}",
                            request.getId(), request.getAttempts(), failure.reason());
                }
            }
            return Optional.of(request.getStatus());
        });
    }

    /** Contado só depois do commit, para a métrica não registrar transições desfeitas. */
    private void countTransition(RequestStatus status) {
        meterRegistry.counter("activation.requests.transitions", "status", status.name()).increment();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record Attempt(UUID requestId, int number, String customerId, String productCode, String correlationId) {

        static Attempt of(ActivationRequest request) {
            return new Attempt(request.getId(), request.getAttempts(), request.getCustomerId(),
                    request.getProductCode(), request.getCorrelationId());
        }
    }
}
