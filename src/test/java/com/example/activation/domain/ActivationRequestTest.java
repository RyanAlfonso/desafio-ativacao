package com.example.activation.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivationRequestTest {

    private static final Instant NOW = Instant.parse("2026-10-26T14:03:11Z");
    private static final Duration LEASE = Duration.ofSeconds(30);

    @Test
    void newRequestIsPendingAndDueImmediately() {
        var request = newRequest();

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PENDING);
        assertThat(request.getAttempts()).isZero();
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(request.getCreatedAt()).isEqualTo(request.getUpdatedAt());
    }

    @Test
    void startAttemptMovesToProcessingAndReservesForTheLease() {
        var request = newRequest();

        request.startAttempt(NOW.plusSeconds(1), LEASE);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(31));
        assertThat(request.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void temporaryFailureKeepsProcessingAndSchedulesNextAttempt() {
        var request = newRequest();
        request.startAttempt(NOW, LEASE);

        request.scheduleRetry(NOW.plusSeconds(10), "HTTP 503", NOW);
        request.startAttempt(NOW.plusSeconds(10), LEASE);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(2);
    }

    @Test
    void completeClearsScheduleAndError() {
        var request = newRequest();
        request.startAttempt(NOW, LEASE);
        request.scheduleRetry(NOW, "HTTP 503", NOW);

        request.complete(NOW);

        assertThat(request.getStatus()).isEqualTo(RequestStatus.COMPLETED);
        assertThat(request.getNextAttemptAt()).isNull();
        assertThat(request.getLastError()).isNull();
    }

    @Test
    void cannotCompleteWithoutBeingSentToTheProvider() {
        var request = newRequest();

        assertThatThrownBy(() -> request.complete(NOW))
                .isInstanceOf(InvalidStatusTransitionException.class)
                .hasMessageContaining("PENDING -> COMPLETED");
    }

    @Test
    void completedRequestCannotGoBackToProcessing() {
        var request = newRequest();
        request.startAttempt(NOW, LEASE);
        request.complete(NOW);

        assertThatThrownBy(() -> request.startAttempt(NOW, LEASE))
                .isInstanceOf(InvalidStatusTransitionException.class);
        assertThatThrownBy(() -> request.fail("x", NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @Test
    void failedRequestIsFinal() {
        var request = newRequest();
        request.startAttempt(NOW, LEASE);
        request.fail("HTTP 400", NOW);

        assertThat(request.getLastError()).isEqualTo("HTTP 400");
        assertThatThrownBy(() -> request.scheduleRetry(NOW, "x", NOW))
                .isInstanceOf(InvalidStatusTransitionException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING, PROCESSING, true",
            "PENDING, COMPLETED, false",
            "PENDING, FAILED, false",
            "PROCESSING, PROCESSING, true",
            "PROCESSING, COMPLETED, true",
            "PROCESSING, FAILED, true",
            "PROCESSING, PENDING, false",
            "COMPLETED, PROCESSING, false",
            "COMPLETED, FAILED, false",
            "FAILED, PROCESSING, false",
            "FAILED, COMPLETED, false"
    })
    void transitionTable(RequestStatus from, RequestStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @Test
    void samePayloadComparesOwnerCustomerAndProduct() {
        var request = newRequest();

        assertThat(request.hasSamePayload("erp", "CUST-1", "INTERNET-500")).isTrue();
        assertThat(request.hasSamePayload("erp", "CUST-1", "TV-100")).isFalse();
        assertThat(request.hasSamePayload("crm", "CUST-1", "INTERNET-500")).isFalse();
    }

    private static ActivationRequest newRequest() {
        return ActivationRequest.create("EXT-1", "erp", "CUST-1", "INTERNET-500", "trace-1", NOW);
    }
}
