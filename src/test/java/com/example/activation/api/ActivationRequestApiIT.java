package com.example.activation.api;

import com.example.activation.application.ActivationDispatcher;
import com.example.activation.domain.RequestStatus;
import com.example.activation.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fluxo completo: API → banco real → dispatcher → provedor simulado → consulta do status. */
@AutoConfigureMockMvc
class ActivationRequestApiIT extends IntegrationTest {

    private static final String ACTIVATE = "/provider/v1/activate";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ActivationDispatcher dispatcher;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void requestLeftInProcessingByACrashedInstanceIsResentWhenLeaseExpires() throws Exception {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(ok()));
        String id = create("EXT-1", "erp", 201);
        simulateExpiredLease(id, 1);

        dispatcher.dispatchDue();

        assertStatus(id, RequestStatus.COMPLETED, 2);
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("Idempotency-Key", equalTo(id)));
    }

    @Test
    void expiredLeaseAfterLastAllowedAttemptFailsWithoutCallingTheProviderAgain() throws Exception {
        String id = create("EXT-1", "erp", 201);
        simulateExpiredLease(id, 3); // max-attempts = 3 no perfil de teste

        dispatcher.dispatchDue();

        assertStatus(id, RequestStatus.FAILED, 3);
        PROVIDER.verify(0, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void createdRequestIsPersistedAndReturnedByGet() throws Exception {
        String id = create("EXT-1", "erp", 201);

        mvc.perform(get("/api/v1/requests/{id}", id).with(client("erp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.externalId").value("EXT-1"));
        assertThat(repository.count()).isOne();
    }

    @Test
    void retryWithSameExternalIdDoesNotCreateSecondRecord() throws Exception {
        String first = create("EXT-1", "erp", 201);
        String second = create("EXT-1", "erp", 200);

        assertThat(second).isEqualTo(first);
        assertThat(repository.count()).isOne();
    }

    @Test
    void sameExternalIdWithDifferentProductReturns409() throws Exception {
        create("EXT-1", "erp", 201);

        mvc.perform(MockMvcRequestBuilders.post("/api/v1/requests")
                        .with(client("erp")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"EXT-1","customerId":"CUST-1","productCode":"TV-100"}"""))
                .andExpect(status().isConflict());
        assertThat(repository.count()).isOne();
    }

    @Test
    void successfulActivationCompletesTheRequest() throws Exception {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(ok()));
        String id = create("EXT-1", "erp", 201);

        dispatcher.dispatchDue();

        assertStatus(id, RequestStatus.COMPLETED, 1);
        PROVIDER.verify(postRequestedFor(urlEqualTo(ACTIVATE))
                .withHeader("Idempotency-Key", equalTo(id))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withHeader("X-Correlation-Id", equalTo("corr-12345678"))
                .withRequestBody(equalToJson("""
                        {"requestId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}""".formatted(id))));
    }

    @Test
    void providerRejectionFailsWithoutRetry() throws Exception {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(400)));
        String id = create("EXT-1", "erp", 201);

        dispatcher.dispatchDue();
        dispatcher.dispatchDue();

        assertStatus(id, RequestStatus.FAILED, 1);
        mvc.perform(get("/api/v1/requests/{id}", id).with(client("erp")))
                .andExpect(jsonPath("$.failureReason").value("Provedor recusou a solicitação (HTTP 400)"));
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void serverErrorIsRetriedUntilSuccess() throws Exception {
        PROVIDER.stubFor(post(ACTIVATE).inScenario("instável").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(500)).willSetStateTo("recuperado"));
        PROVIDER.stubFor(post(ACTIVATE).inScenario("instável").whenScenarioStateIs("recuperado")
                .willReturn(ok()));
        String id = create("EXT-1", "erp", 201);

        dispatcher.dispatchDue();
        assertStatus(id, RequestStatus.PROCESSING, 1);

        dispatcher.dispatchDue();
        assertStatus(id, RequestStatus.COMPLETED, 2);
        // A mesma chave de idempotência nas duas tentativas.
        PROVIDER.verify(2, postRequestedFor(urlEqualTo(ACTIVATE)).withHeader("Idempotency-Key", equalTo(id)));
    }

    @Test
    void persistentServerErrorFailsAfterMaxAttempts() throws Exception {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(503)));
        String id = create("EXT-1", "erp", 201);

        for (int i = 0; i < 5; i++) {
            dispatcher.dispatchDue();
        }

        assertStatus(id, RequestStatus.FAILED, 3); // max-attempts = 3 no perfil de teste
        assertThat(repository.findById(UUID.fromString(id)).orElseThrow().getLastError())
                .startsWith("Limite de tentativas atingido");
        PROVIDER.verify(3, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void retryAfterIsHonoured() throws Exception {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));
        String id = create("EXT-1", "erp", 201);

        dispatcher.dispatchDue();
        dispatcher.dispatchDue(); // ainda não venceu: não chama de novo

        var request = repository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(100));
        PROVIDER.verify(1, postRequestedFor(urlEqualTo(ACTIVATE)));
    }

    @Test
    void timeoutIsTreatedAsTemporaryFailure() throws Exception {
        // read-timeout = 500ms no perfil de teste
        PROVIDER.stubFor(post(ACTIVATE).willReturn(ok().withFixedDelay(1_500)));
        String id = create("EXT-1", "erp", 201);

        dispatcher.dispatchDue();

        var request = repository.findById(UUID.fromString(id)).orElseThrow();
        assertThat(request.getStatus()).isEqualTo(RequestStatus.PROCESSING);
        assertThat(request.getAttempts()).isEqualTo(1);
        assertThat(request.getLastError()).isEqualTo("Timeout ao chamar o provedor");
    }

    @Test
    void connectionFailureIsTreatedAsTemporaryFailure() throws Exception {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        String id = create("EXT-1", "erp", 201);

        dispatcher.dispatchDue();

        assertStatus(id, RequestStatus.PROCESSING, 1);
    }

    @Test
    void clientCannotSeeRequestsOfAnotherClient() throws Exception {
        String id = create("EXT-1", "erp", 201);

        mvc.perform(get("/api/v1/requests/{id}", id).with(client("crm")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/requests").with(client("crm")))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void listFiltersByStatusAndCustomerAndPaginates() throws Exception {
        PROVIDER.stubFor(post(ACTIVATE).willReturn(ok()));
        create("EXT-1", "erp", 201);
        dispatcher.dispatchDue();
        create("EXT-2", "erp", 201);
        create("EXT-3", "erp", 201);

        mvc.perform(get("/api/v1/requests").param("status", "PENDING").param("size", "1").with(client("erp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].externalId").value("EXT-3"));
        mvc.perform(get("/api/v1/requests").param("customerId", "OUTRO").with(client("erp")))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/requests").param("status", "INEXISTENTE").with(client("erp")))
                .andExpect(status().isBadRequest());
    }

    private String create(String externalId, String clientId, int expectedStatus) throws Exception {
        var response = mvc.perform(MockMvcRequestBuilders.post("/api/v1/requests")
                        .with(client(clientId))
                        .header("X-Correlation-Id", "corr-12345678")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"%s","customerId":"CUST-1","productCode":"INTERNET-500"}""".formatted(externalId)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(response);
        return json.get("id").asText();
    }

    /** Estado deixado por uma instância que reservou a solicitação e caiu antes de gravar o resultado. */
    private void simulateExpiredLease(String id, int attempts) {
        jdbc.update("""
                UPDATE activation_request
                   SET status = 'PROCESSING', attempts = ?, next_attempt_at = now() - interval '1 second'
                 WHERE id = ?""", attempts, UUID.fromString(id));
    }

    private void assertStatus(String id, RequestStatus status, int attempts) throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", id).with(client("erp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(status.name()))
                .andExpect(jsonPath("$.attempts").value(attempts));
    }

    private static RequestPostProcessor client(String clientId) {
        return jwt().jwt(token -> token.claim("client_id", clientId))
                .authorities(() -> "SCOPE_activation:write", () -> "SCOPE_activation:read");
    }
}
