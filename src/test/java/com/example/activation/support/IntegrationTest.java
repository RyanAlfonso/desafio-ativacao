package com.example.activation.support;

import com.example.activation.persistence.ActivationRequestRepository;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Base dos testes de integração: PostgreSQL real (Testcontainers) e provedor simulado
 * (WireMock). O dispatcher agendado fica desligado; cada teste chama
 * {@code dispatcher.dispatchDue()} quando quer que o envio aconteça.
 *
 * <p>O container é um bean do contexto Spring (vive enquanto o contexto estiver em cache) e o
 * WireMock é único para a JVM; assim um contexto reaproveitado entre classes de teste nunca
 * aponta para um banco ou porta que já foi derrubado.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(IntegrationTest.Containers.class)
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTest {

    protected static final WireMockServer PROVIDER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        PROVIDER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PROVIDER::stop));
    }

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.provider.base-url", PROVIDER::baseUrl);
    }

    @Autowired
    protected ActivationRequestRepository repository;

    @BeforeEach
    void resetState() {
        repository.deleteAllInBatch();
        PROVIDER.resetAll();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Containers {

        @Bean
        @ServiceConnection
        PostgreSQLContainer<?> postgres() {
            return new PostgreSQLContainer<>("postgres:16-alpine");
        }
    }
}
