package br.teste.deroyque.services.scheduler;

import br.teste.deroyque.config.ActivationProperties;
import br.teste.deroyque.repositories.ActivationRequestRepository;
import br.teste.deroyque.services.ActivationProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Envia ao provedor as solicitações gravadas como PENDING. A própria tabela funciona como outbox:
 * se a aplicação cair entre o commit e a chamada, a solicitação continua PENDING e é enviada
 * no próximo ciclo (por esta ou outra instância).
 */
@Component
@ConditionalOnProperty(name = "activation.dispatcher.enabled", havingValue = "true", matchIfMissing = true)
public class ActivationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ActivationDispatcher.class);

    private final ActivationRequestRepository repository;
    private final ActivationProcessor processor;
    private final ActivationProperties properties;
    private final Clock clock;

    public ActivationDispatcher(ActivationRequestRepository repository,
                                ActivationProcessor processor,
                                ActivationProperties properties,
                                Clock clock) {
        this.repository = repository;
        this.processor = processor;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${activation.dispatcher.poll-interval:1s}")
    public void dispatchDue() {
        List<UUID> due = repository.findDueIds(clock.instant(), PageRequest.of(0, properties.batchSize()));
        // Chamadas em paralelo (I/O): um provedor lento não serializa o lote inteiro.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            due.forEach(id -> executor.submit(() -> processSafely(id)));
        }
    }

    @Scheduled(fixedDelayString = "${activation.dispatcher.stuck-check-interval:30s}")
    public void recoverStuck() {
        repository.findStuckIds(clock.instant().minus(properties.stuckAfter()), PageRequest.of(0, properties.batchSize()))
                .forEach(processor::recoverStuck);
    }

    private void processSafely(UUID id) {
        try {
            processor.process(id);
        } catch (RuntimeException e) {
            // A solicitação continua PENDING ou PROCESSING e será retomada; só não pode derrubar o lote.
            log.error("Unexpected error processing request {}", id, e);
        }
    }
}
