package com.example.activation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitação de ativação de um produto para um cliente.
 *
 * <p>O status só muda pelos métodos de domínio ({@link #startAttempt}, {@link #complete},
 * {@link #fail}, {@link #scheduleRetry}); não há setters. A tabela também funciona como
 * "outbox": {@code nextAttemptAt} indica quando a solicitação deve ser (re)enviada ao provedor.
 */
@Entity
@Table(name = "activation_request")
public class ActivationRequest {

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    /** Sistema consumidor (claim do JWT) dono da solicitação. */
    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RequestStatus status;

    @Column(nullable = false)
    private int attempts;

    /** Quando a solicitação volta a ser elegível para envio. Nulo em status finais. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "correlation_id", updatable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected ActivationRequest() {
        // JPA
    }

    public static ActivationRequest create(String externalId, String clientId, String customerId,
                                           String productCode, String correlationId, Instant now) {
        var request = new ActivationRequest();
        request.id = UUID.randomUUID();
        request.externalId = Objects.requireNonNull(externalId);
        request.clientId = Objects.requireNonNull(clientId);
        request.customerId = Objects.requireNonNull(customerId);
        request.productCode = Objects.requireNonNull(productCode);
        request.correlationId = correlationId;
        request.status = RequestStatus.PENDING;
        request.attempts = 0;
        request.nextAttemptAt = now;
        request.createdAt = now;
        request.updatedAt = now;
        return request;
    }

    /**
     * Reserva a solicitação para uma tentativa de envio. {@code lease} é o tempo que o
     * worker tem para concluir a chamada; se ele cair, a solicitação volta a ficar elegível
     * quando o lease expirar.
     */
    public void startAttempt(Instant now, Duration lease) {
        transitionTo(RequestStatus.PROCESSING, now);
        attempts++;
        nextAttemptAt = now.plus(lease);
    }

    public void complete(Instant now) {
        transitionTo(RequestStatus.COMPLETED, now);
        nextAttemptAt = null;
        lastError = null;
    }

    public void fail(String reason, Instant now) {
        transitionTo(RequestStatus.FAILED, now);
        nextAttemptAt = null;
        lastError = reason;
    }

    /** Falha temporária: permanece em PROCESSING e volta a ser elegível em {@code retryAt}. */
    public void scheduleRetry(Instant retryAt, String reason, Instant now) {
        transitionTo(RequestStatus.PROCESSING, now);
        nextAttemptAt = retryAt;
        lastError = reason;
    }

    /** Mesmo pedido de negócio (usado para distinguir retry legítimo de conflito). */
    public boolean hasSamePayload(String clientId, String customerId, String productCode) {
        return this.clientId.equals(clientId)
                && this.customerId.equals(customerId)
                && this.productCode.equals(productCode);
    }

    private void transitionTo(RequestStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        status = target;
        updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public RequestStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
