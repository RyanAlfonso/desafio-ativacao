package com.example.activation.api;

import com.example.activation.application.ActivationRequestService;
import com.example.activation.application.CreationResult;
import com.example.activation.application.ExternalIdConflictException;
import com.example.activation.application.NewActivationRequest;
import com.example.activation.application.RequestNotFoundException;
import com.example.activation.config.SecurityConfig;
import com.example.activation.domain.ActivationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Camada web isolada: validação, mapeamento HTTP, formato de erro e regras de acesso. */
@WebMvcTest(ActivationRequestController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("test")
class ActivationRequestControllerTest {

    private static final String VALID_BODY = """
            {"externalId":"EXT-12345","customerId":"CUST-10001","productCode":"INTERNET-500"}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ActivationRequestService service;

    @Test
    void createReturns201WithLocation() throws Exception {
        var request = sampleRequest();
        when(service.create(eq(new NewActivationRequest("EXT-12345", "CUST-10001", "INTERNET-500")), eq("erp"), anyString()))
                .thenReturn(new CreationResult(request, true));

        mvc.perform(post("/api/v1/requests").with(writer("erp")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/requests/" + request.getId()))
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andExpect(jsonPath("$.id").value(request.getId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.createdAt").value("2026-10-26T14:03:11Z"))
                .andExpect(jsonPath("$.failureReason").doesNotExist());
    }

    @Test
    void retryWithSamePayloadReturns200WithExistingRequest() throws Exception {
        when(service.create(any(), eq("erp"), anyString())).thenReturn(new CreationResult(sampleRequest(), false));

        mvc.perform(post("/api/v1/requests").with(writer("erp")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(header().exists("Location"));
    }

    @Test
    void conflictingDuplicateReturns409ProblemDetail() throws Exception {
        when(service.create(any(), eq("erp"), anyString())).thenThrow(new ExternalIdConflictException("EXT-12345"));

        mvc.perform(post("/api/v1/requests").with(writer("erp")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://api.example.com/problems/duplicate-external-id"))
                .andExpect(jsonPath("$.traceId").exists());
    }

    @Test
    void invalidBodyReturns400WithFieldErrors() throws Exception {
        var body = """
                {"externalId":"EXT 1","productCode":"%s"}""".formatted("X".repeat(65));

        mvc.perform(post("/api/v1/requests").with(writer("erp")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Dados inválidos"))
                .andExpect(jsonPath("$.instance").value("/api/v1/requests"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[?(@.field == 'customerId')].message").value("é obrigatório"));
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonReturns400WithoutInternalDetails() throws Exception {
        mvc.perform(post("/api/v1/requests").with(writer("erp")).contentType(MediaType.APPLICATION_JSON).content("{\"externalId\":"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(content().string(not(containsString("jackson"))));
    }

    @Test
    void oversizedBodyIsRejectedBeforeParsing() throws Exception {
        var body = "{\"externalId\":\"" + "A".repeat(20_000) + "\"}";

        mvc.perform(post("/api/v1/requests").with(writer("erp")).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentType("application/problem+json;charset=UTF-8"))
                .andExpect(jsonPath("$.title").value("Corpo da requisição inválido"));
        verifyNoInteractions(service);
    }

    @Test
    void getReturnsRequestOfTheCaller() throws Exception {
        var request = sampleRequest();
        when(service.get(request.getId(), "erp")).thenReturn(request);

        mvc.perform(get("/api/v1/requests/{id}", request.getId()).with(reader("erp")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("EXT-12345"));
    }

    @Test
    void unknownIdReturns404ProblemDetail() throws Exception {
        var id = UUID.randomUUID();
        when(service.get(id, "erp")).thenThrow(new RequestNotFoundException(id));

        mvc.perform(get("/api/v1/requests/{id}", id).with(reader("erp")))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void invalidUuidReturns400() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", "nao-e-uuid").with(reader("erp")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pageSizeAboveLimitReturns400() throws Exception {
        mvc.perform(get("/api/v1/requests").param("size", "500").with(reader("erp")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"))
                .andExpect(jsonPath("$.errors[0].message").value("deve estar entre 1 e 100"));
    }

    @Test
    void unexpectedErrorReturns500WithoutStackTrace() throws Exception {
        var id = UUID.randomUUID();
        when(service.get(id, "erp")).thenThrow(new IllegalStateException("conexão recusada: db-interno:5432"));

        mvc.perform(get("/api/v1/requests/{id}", id).with(reader("erp")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Ocorreu um erro inesperado. Informe o traceId ao suporte."))
                .andExpect(content().string(not(containsString("db-interno"))));
    }

    @Test
    void requestWithoutTokenReturns401() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/requests").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void readOnlyClientCannotCreate() throws Exception {
        mvc.perform(post("/api/v1/requests").with(reader("portal")).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void writeOnlyClientCannotRead() throws Exception {
        mvc.perform(get("/api/v1/requests/{id}", UUID.randomUUID()).with(jwt().authorities(() -> "SCOPE_activation:write")))
                .andExpect(status().isForbidden());
    }

    private static RequestPostProcessor writer(String clientId) {
        return jwt().jwt(token -> token.claim("client_id", clientId))
                .authorities(() -> "SCOPE_activation:write", () -> "SCOPE_activation:read");
    }

    private static RequestPostProcessor reader(String clientId) {
        return jwt().jwt(token -> token.claim("client_id", clientId))
                .authorities(() -> "SCOPE_activation:read");
    }

    private static ActivationRequest sampleRequest() {
        return ActivationRequest.create("EXT-12345", "erp", "CUST-10001", "INTERNET-500", "trace",
                Instant.parse("2026-10-26T14:03:11Z"));
    }
}
