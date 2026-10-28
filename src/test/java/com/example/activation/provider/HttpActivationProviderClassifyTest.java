package com.example.activation.provider;

import com.example.activation.application.ActivationOutcome.Activated;
import com.example.activation.application.ActivationOutcome.Rejected;
import com.example.activation.application.ActivationOutcome.TemporaryFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

class HttpActivationProviderClassifyTest {

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204})
    void successStatusesActivate(int status) {
        assertThat(HttpActivationProvider.classify(HttpStatusCode.valueOf(status), new HttpHeaders()))
                .isInstanceOf(Activated.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 422})
    void clientErrorsAreRejected(int status) {
        assertThat(HttpActivationProvider.classify(HttpStatusCode.valueOf(status), new HttpHeaders()))
                .isInstanceOf(Rejected.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void transientErrorsAreRetried(int status) {
        assertThat(HttpActivationProvider.classify(HttpStatusCode.valueOf(status), new HttpHeaders()))
                .isInstanceOf(TemporaryFailure.class);
    }

    @Test
    void retryAfterInSeconds() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "120");

        var outcome = (TemporaryFailure) HttpActivationProvider.classify(HttpStatusCode.valueOf(503), headers);

        assertThat(outcome.retryAfter()).isEqualTo(Duration.ofSeconds(120));
    }

    @Test
    void retryAfterAsHttpDate() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER,
                DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusMinutes(2)));

        Duration retryAfter = HttpActivationProvider.parseRetryAfter(headers);

        assertThat(retryAfter).isBetween(Duration.ofSeconds(100), Duration.ofSeconds(120));
    }

    @Test
    void invalidRetryAfterIsIgnored() {
        var headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "amanhã");

        assertThat(HttpActivationProvider.parseRetryAfter(headers)).isNull();
    }
}
