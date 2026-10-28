package com.example.activation.application;

import com.example.activation.domain.ActivationRequest;
import com.example.activation.persistence.ActivationRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ActivationRequestServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");
    private static final NewActivationRequest COMMAND = new NewActivationRequest("EXT-1", "CUST-1", "INTERNET-500");

    @Mock
    private ActivationRequestRepository repository;

    @Spy
    private Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @InjectMocks
    private ActivationRequestService service;

    @Test
    void createsPendingRequestWhenExternalIdIsNew() {
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.empty());

        var result = service.create(COMMAND, "erp", "trace-1");

        assertThat(result.created()).isTrue();
        assertThat(result.request().getCreatedAt()).isEqualTo(NOW);
        assertThat(result.request().getClientId()).isEqualTo("erp");
        verify(repository).saveAndFlush(result.request());
    }

    @Test
    void sameExternalIdAndSamePayloadReturnsExistingRequest() {
        var existing = existingRequest("erp", "INTERNET-500");
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.of(existing));

        var result = service.create(COMMAND, "erp", "trace-2");

        assertThat(result.created()).isFalse();
        assertThat(result.request()).isSameAs(existing);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void sameExternalIdWithDifferentProductIsAConflict() {
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.of(existingRequest("erp", "TV-100")));

        assertThatThrownBy(() -> service.create(COMMAND, "erp", "trace-2"))
                .isInstanceOf(ExternalIdConflictException.class);
    }

    @Test
    void sameExternalIdFromAnotherClientIsAConflict() {
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.of(existingRequest("crm", "INTERNET-500")));

        assertThatThrownBy(() -> service.create(COMMAND, "erp", "trace-2"))
                .isInstanceOf(ExternalIdConflictException.class);
    }

    @Test
    void losingTheInsertRaceReturnsTheWinner() {
        // SELECT não encontra nada, mas outra requisição insere antes do nosso INSERT.
        var winner = existingRequest("erp", "INTERNET-500");
        when(repository.findByExternalId("EXT-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_activation_request_external_id"));

        var result = service.create(COMMAND, "erp", "trace-2");

        assertThat(result.created()).isFalse();
        assertThat(result.request()).isSameAs(winner);
    }

    @Test
    void integrityErrorUnrelatedToDuplicityIsPropagated() {
        when(repository.findByExternalId("EXT-1")).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("outra constraint"));

        assertThatThrownBy(() -> service.create(COMMAND, "erp", "trace-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static ActivationRequest existingRequest(String clientId, String productCode) {
        return ActivationRequest.create("EXT-1", clientId, "CUST-1", productCode, "trace-1", NOW.minusSeconds(5));
    }
}
