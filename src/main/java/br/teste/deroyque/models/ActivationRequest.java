package br.teste.deroyque.models;

import br.teste.deroyque.exceptions.InvalidStatusTransitionException;
import br.teste.deroyque.models.enums.ActivationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Solicitação de ativação de um produto. O status só muda pelos métodos de domínio
 * ({@link #startProcessing}, {@link #complete}, {@link #fail}, {@link #scheduleRetry}),
 * que validam a transição; não há setters.
 */
@Entity
@Table(name = "activation_request")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA
public class ActivationRequest {

    private static final int MAX_ERROR_LENGTH = 255;

    @Id
    private UUID id;

    @Column(name = "external_id", nullable = false, updatable = false)
    private String externalId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private String customerId;

    @Column(name = "product_code", nullable = false, updatable = false)
    private String productCode;

    /** Sistema consumidor (claim do JWT) dono da solicitação. */
    @Column(name = "client_id", nullable = false, updatable = false)
    private String clientId;

    @Column(name = "correlation_id", nullable = false, updatable = false)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActivationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Getter(AccessLevel.NONE)
    private Long version;

    private ActivationRequest(String externalId, String customerId, String productCode,
                              String clientId, String correlationId, Instant now) {
        this.id = UUID.randomUUID();
        this.externalId = Objects.requireNonNull(externalId);
        this.customerId = Objects.requireNonNull(customerId);
        this.productCode = Objects.requireNonNull(productCode);
        this.clientId = Objects.requireNonNull(clientId);
        this.correlationId = Objects.requireNonNull(correlationId);
        this.status = ActivationStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static ActivationRequest create(String externalId, String customerId, String productCode,
                                           String clientId, String correlationId, Instant now) {
        return new ActivationRequest(externalId, customerId, productCode, clientId, correlationId, now);
    }

    /** Uma chamada ao provedor vai começar; conta como tentativa. */
    public void startProcessing(Instant now) {
        transitionTo(ActivationStatus.PROCESSING, now);
        attempts++;
        nextAttemptAt = null;
    }

    public void complete(Instant now) {
        transitionTo(ActivationStatus.COMPLETED, now);
        lastError = null;
    }

    public void fail(String reason, Instant now) {
        transitionTo(ActivationStatus.FAILED, now);
        lastError = truncate(reason);
    }

    /** Falha temporária: volta para a fila com nova tentativa agendada. */
    public void scheduleRetry(String reason, Instant nextAttemptAt, Instant now) {
        transitionTo(ActivationStatus.PENDING, now);
        this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt);
        lastError = truncate(reason);
    }

    /** Retry legítimo do sistema de origem: mesmo dono e mesmo conteúdo. */
    public boolean isSameRequest(String clientId, String customerId, String productCode) {
        return this.clientId.equals(clientId)
                && this.customerId.equals(customerId)
                && this.productCode.equals(productCode);
    }

    private void transitionTo(ActivationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidStatusTransitionException(status, target);
        }
        status = target;
        updatedAt = now;
    }

    private static String truncate(String reason) {
        if (reason == null || reason.length() <= MAX_ERROR_LENGTH) {
            return reason;
        }
        return reason.substring(0, MAX_ERROR_LENGTH);
    }
}
