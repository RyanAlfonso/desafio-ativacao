package com.example.activation.provider;

import com.example.activation.application.ActivationOutcome;
import com.example.activation.application.ActivationOutcome.Activated;
import com.example.activation.application.ActivationOutcome.Rejected;
import com.example.activation.application.ActivationOutcome.TemporaryFailure;
import com.example.activation.application.ActivationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Cliente do {@code POST /provider/v1/activate}.
 *
 * <table>
 *   <tr><th>Retorno</th><th>Resultado</th></tr>
 *   <tr><td>2xx</td><td>{@link Activated}</td></tr>
 *   <tr><td>408, 429, 5xx</td><td>{@link TemporaryFailure} (respeitando Retry-After)</td></tr>
 *   <tr><td>demais 4xx</td><td>{@link Rejected}</td></tr>
 *   <tr><td>timeout / erro de rede</td><td>{@link TemporaryFailure}</td></tr>
 * </table>
 */
@Component
public class HttpActivationProvider implements ActivationProvider {

    static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private static final Logger log = LoggerFactory.getLogger(HttpActivationProvider.class);

    private final RestClient restClient;

    public HttpActivationProvider(RestClient.Builder builder, ProviderProperties properties) {
        var httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1) // sem tentativa de upgrade h2c em http://
                .connectTimeout(properties.connectTimeout())
                .build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());

        this.restClient = builder
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .defaultHeaders(headers -> {
                    if (StringUtils.hasText(properties.apiKey())) {
                        headers.setBearerAuth(properties.apiKey());
                    }
                })
                .build();
    }

    @Override
    public ActivationOutcome activate(UUID requestId, String customerId, String productCode, String correlationId) {
        try {
            return restClient.post()
                    .uri("/provider/v1/activate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(IDEMPOTENCY_KEY_HEADER, requestId.toString())
                    .headers(headers -> {
                        if (correlationId != null) {
                            headers.set(CORRELATION_ID_HEADER, correlationId);
                        }
                    })
                    .body(new ProviderActivationRequest(requestId, customerId, productCode))
                    .exchange((request, response) ->
                            classify(response.getStatusCode(), response.getHeaders()), true);
        } catch (ResourceAccessException e) {
            boolean timeout = isTimeout(e);
            log.warn("Falha de comunicação com o provedor (timeout={}): {}", timeout, e.getMessage());
            return new TemporaryFailure(timeout ? "Timeout ao chamar o provedor" : "Erro de comunicação com o provedor", null);
        }
    }

    static ActivationOutcome classify(HttpStatusCode status, HttpHeaders headers) {
        int code = status.value();
        if (status.is2xxSuccessful()) {
            return new Activated();
        }
        if (code == 408 || code == 429 || status.is5xxServerError()) {
            return new TemporaryFailure("Provedor respondeu HTTP " + code, parseRetryAfter(headers));
        }
        return new Rejected("Provedor recusou a solicitação (HTTP " + code + ")");
    }

    /** Retry-After pode vir em segundos ou como data HTTP (RFC 9110). */
    static Duration parseRetryAfter(HttpHeaders headers) {
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(value.trim())));
        } catch (NumberFormatException notSeconds) {
            try {
                var date = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
                Duration untilDate = Duration.between(ZonedDateTime.now(date.getZone()), date);
                return untilDate.isNegative() ? Duration.ZERO : untilDate;
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    record ProviderActivationRequest(UUID requestId, String customerId, String productCode) {
    }
}
