package com.example.activation.application;

import com.example.activation.application.ActivationOutcome.Activated;
import com.example.activation.domain.ActivationRequest;
import com.example.activation.domain.RequestStatus;
import com.example.activation.persistence.ActivationRequestRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/** Casos de borda do dispatcher que são difíceis de provocar com banco e HTTP reais. */
@ExtendWith(MockitoExtension.class)
class ActivationDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");

    @Mock
    private ActivationRequestRepository repository;

    @Mock
    private ActivationProvider provider;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private ActivationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        var properties = new DispatchProperties(false, Duration.ofSeconds(1), 10, Duration.ofSeconds(30), 3,
                Duration.ofSeconds(2), Duration.ofMinutes(5));
        dispatcher = new ActivationDispatcher(repository, provider, new RetryPolicy(properties), properties,
                new TransactionTemplate(transactionManager), meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unexpectedExceptionFromTheClientBecomesTemporaryFailureWithBackoff() {
        var request = pendingRequest();
        when(repository.lockDueForDispatch(any(), anyInt())).thenReturn(List.of(request));
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(provider.activate(any(), any(), any(), any())).thenThrow(new IllegalStateException("bug no cliente"));

        dispatcher.dispatchDue();

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getLastError()).isEqualTo("Erro inesperado ao chamar o provedor");
        assertThat(request.getNextAttemptAt()).isBetween(NOW.plusSeconds(1), NOW.plusSeconds(2));
    }

    @Test
    void resultOfAnAttemptThatIsNoLongerCurrentIsDiscarded() {
        var request = pendingRequest();
        when(repository.lockDueForDispatch(any(), anyInt())).thenReturn(List.of(request));
        when(provider.activate(any(), any(), any(), any())).thenAnswer(invocation -> {
            // Enquanto esta chamada demorava, o lease expirou e outra instância iniciou a tentativa 2.
            request.startAttempt(NOW, Duration.ofSeconds(30));
            return new Activated();
        });
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));

        dispatcher.dispatchDue();

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(2);
    }

    @Test
    void transitionsAreCountedInMetrics() {
        var request = pendingRequest();
        when(repository.lockDueForDispatch(any(), anyInt())).thenReturn(List.of(request));
        when(repository.findById(request.getId())).thenReturn(Optional.of(request));
        when(provider.activate(any(), any(), any(), any())).thenReturn(new Activated());

        dispatcher.dispatchDue();

        assertThat(meterRegistry.counter("activation.requests.transitions", "status", "PROCESSING").count()).isOne();
        assertThat(meterRegistry.counter("activation.requests.transitions", "status", "COMPLETED").count()).isOne();
        assertThat(meterRegistry.timer("activation.provider.calls", "outcome", "Activated").count()).isOne();
    }

    private static ActivationRequest pendingRequest() {
        return ActivationRequest.create("EXT-1", "erp", "CUST-1", "INTERNET-500", "trace", NOW);
    }
}
