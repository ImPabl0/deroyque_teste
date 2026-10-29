package br.teste.deroyque.services;

import br.teste.deroyque.models.ActivationRequest;
import br.teste.deroyque.models.enums.ActivationStatus;
import br.teste.deroyque.repositories.ActivationRequestRepository;
import br.teste.deroyque.services.ProviderGateway.ActivationCall;
import br.teste.deroyque.services.ProviderResult.PermanentFailure;
import br.teste.deroyque.services.ProviderResult.Success;
import br.teste.deroyque.services.ProviderResult.TransientFailure;
import br.teste.deroyque.utils.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Processa uma solicitação em três passos, cada um curto e independente:
 * <ol>
 *     <li>transação: reivindica a solicitação (PENDING → PROCESSING), protegida por lock otimista;</li>
 *     <li>sem transação: chama o provedor;</li>
 *     <li>transação: grava o resultado (COMPLETED, FAILED ou PENDING com nova tentativa).</li>
 * </ol>
 * Assim nenhuma conexão de banco fica presa enquanto o provedor responde.
 */
@Component
public class ActivationProcessor {

    private static final Logger log = LoggerFactory.getLogger(ActivationProcessor.class);

    private final ActivationRequestRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final ProviderGateway provider;
    private final RetryPolicy retryPolicy;
    private final Clock clock;

    public ActivationProcessor(ActivationRequestRepository repository,
                               TransactionTemplate transactionTemplate,
                               ProviderGateway provider,
                               RetryPolicy retryPolicy,
                               Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.provider = provider;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

    public void process(UUID id) {
        Optional<ActivationRequest> claimed = claim(id);
        if (claimed.isEmpty()) {
            return;
        }
        ActivationRequest request = claimed.get();
        try (var ignored = MDC.putCloseable(CorrelationId.MDC_KEY, request.getCorrelationId())) {
            log.info("Calling provider id={} attempt={}", id, request.getAttempts());
            ProviderResult result = provider.activate(new ActivationCall(
                    request.getId(), request.getCustomerId(), request.getProductCode(), request.getCorrelationId()));
            update(id, entity -> apply(entity, result));
        }
    }

    /** Solicitação ficou em PROCESSING além do esperado (instância caiu durante a chamada): reenvia. */
    public void recoverStuck(UUID id) {
        update(id, entity -> {
            if (entity.getStatus() == ActivationStatus.PROCESSING) {
                log.warn("Recovering stuck request id={}", id);
                handleTransient(entity, new TransientFailure("Processing interrupted", null));
            }
        });
    }

    private Optional<ActivationRequest> claim(UUID id) {
        try {
            return Optional.ofNullable(transactionTemplate.execute(status -> repository.findById(id)
                    .filter(request -> request.getStatus() == ActivationStatus.PENDING)
                    .map(request -> {
                        request.startProcessing(clock.instant());
                        return repository.saveAndFlush(request);
                    })
                    .orElse(null)));
        } catch (ObjectOptimisticLockingFailureException e) {
            log.debug("Request {} already claimed by another worker", id);
            return Optional.empty();
        }
    }

    private void apply(ActivationRequest request, ProviderResult result) {
        Instant now = clock.instant();
        switch (result) {
            case Success success -> {
                request.complete(now);
                log.info("Activation completed id={}", request.getId());
            }
            case PermanentFailure failure -> {
                request.fail(failure.reason(), now);
                log.warn("Activation rejected by provider id={} reason={}", request.getId(), failure.reason());
            }
            case TransientFailure failure -> handleTransient(request, failure);
        }
    }

    private void handleTransient(ActivationRequest request, TransientFailure failure) {
        Instant now = clock.instant();
        if (!retryPolicy.canRetry(request.getAttempts())) {
            request.fail("Max attempts reached. Last error: " + failure.reason(), now);
            log.warn("Activation failed after {} attempts id={} reason={}",
                    request.getAttempts(), request.getId(), failure.reason());
            return;
        }
        Instant next = now.plus(retryPolicy.delayAfter(request.getAttempts(), failure.retryAfter()));
        request.scheduleRetry(failure.reason(), next, now);
        log.warn("Activation will be retried id={} at={} reason={}", request.getId(), next, failure.reason());
    }

    private void update(UUID id, Consumer<ActivationRequest> change) {
        try {
            transactionTemplate.executeWithoutResult(status -> repository.findById(id).ifPresent(change));
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Concurrent update on request {}; keeping the other worker's result", id);
        }
    }

}
