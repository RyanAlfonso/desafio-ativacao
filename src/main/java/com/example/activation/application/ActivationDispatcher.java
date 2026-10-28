package com.example.activation.application;

import com.example.activation.application.ActivationOutcome.Activated;
import com.example.activation.application.ActivationOutcome.Rejected;
import com.example.activation.application.ActivationOutcome.TemporaryFailure;
import com.example.activation.domain.ActivationRequest;
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
import java.util.List;
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
        return transaction.execute(status -> {
            Instant now = now();
            return repository.lockDueForDispatch(now, properties.batchSize()).stream()
                    .map(request -> {
                        request.startAttempt(now, properties.lease());
                        return Attempt.of(request);
                    })
                    .toList();
        });
    }

    private void process(Attempt attempt) {
        MDC.put("traceId", attempt.correlationId());
        try {
            ActivationOutcome outcome = callProvider(attempt);
            applyOutcome(attempt, outcome);
        } catch (ObjectOptimisticLockingFailureException e) {
            log.info("Solicitação {} alterada concorrentemente; resultado da tentativa {} descartado",
                    attempt.requestId(), attempt.number());
        } catch (RuntimeException e) {
            // A solicitação continua reservada; quando o lease expirar será tentada de novo.
            log.error("Erro inesperado processando a solicitação {}", attempt.requestId(), e);
        } finally {
            MDC.remove("traceId");
        }
    }

    private ActivationOutcome callProvider(Attempt attempt) {
        Timer.Sample sample = Timer.start(meterRegistry);
        ActivationOutcome outcome = provider.activate(
                attempt.requestId(), attempt.customerId(), attempt.productCode(), attempt.correlationId());
        sample.stop(meterRegistry.timer("activation.provider.calls",
                "outcome", outcome.getClass().getSimpleName()));
        return outcome;
    }

    private void applyOutcome(Attempt attempt, ActivationOutcome outcome) {
        transaction.executeWithoutResult(status -> {
            ActivationRequest request = repository.findById(attempt.requestId()).orElseThrow();
            if (request.getStatus().isFinal() || request.getAttempts() != attempt.number()) {
                log.info("Tentativa {} da solicitação {} não é mais a vigente; resultado descartado",
                        attempt.number(), request.getId());
                return;
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
            meterRegistry.counter("activation.requests.transitions", "status", request.getStatus().name())
                    .increment();
        });
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
