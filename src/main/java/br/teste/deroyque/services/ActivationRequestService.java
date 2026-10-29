package br.teste.deroyque.services;

import br.teste.deroyque.dtos.CreateActivationCommand;
import br.teste.deroyque.dtos.CreationResult;
import br.teste.deroyque.exceptions.ActivationRequestNotFoundException;
import br.teste.deroyque.exceptions.DuplicateExternalIdException;
import br.teste.deroyque.models.ActivationRequest;
import br.teste.deroyque.models.enums.ActivationStatus;
import br.teste.deroyque.repositories.ActivationRequestRepository;
import br.teste.deroyque.services.scheduler.ActivationDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

@Service
public class ActivationRequestService {

    private static final Logger log = LoggerFactory.getLogger(ActivationRequestService.class);

    private final ActivationRequestRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public ActivationRequestService(ActivationRequestRepository repository,
                                    TransactionTemplate transactionTemplate,
                                    Clock clock) {
        this.repository = repository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /**
     * Registra a solicitação como PENDING; o envio ao provedor é feito depois pelo {@link ActivationDispatcher}.
     * <p>
     * A consulta prévia por externalId só atende o caso comum (retry sequencial) sem gerar erro no banco.
     * Quem garante a unicidade é a constraint UNIQUE: se duas requisições passarem juntas pela consulta,
     * apenas um INSERT é aceito e a outra cai no {@code catch}, onde é tratada como repetição.
     * Por isso este método não é transacional: a transação do INSERT precisa terminar (com rollback)
     * antes de ler o registro vencedor.
     */
    public CreationResult create(CreateActivationCommand command) {
        var existing = repository.findByExternalId(command.externalId());
        if (existing.isPresent()) {
            return asRepetition(existing.get(), command);
        }
        try {
            ActivationRequest saved = transactionTemplate.execute(status -> repository.saveAndFlush(
                    ActivationRequest.create(command.externalId(), command.customerId(), command.productCode(),
                            command.clientId(), command.correlationId(), clock.instant())));
            log.info("Activation request created id={} externalId={}", saved.getId(), saved.getExternalId());
            return CreationResult.created(saved);
        } catch (DataIntegrityViolationException e) {
            ActivationRequest winner = repository.findByExternalId(command.externalId()).orElseThrow(() -> e);
            log.info("Concurrent creation detected for externalId={}", command.externalId());
            return asRepetition(winner, command);
        }
    }

    @Transactional(readOnly = true)
    public ActivationRequest get(UUID id, String clientId) {
        return repository.findByIdAndClientId(id, clientId)
                .orElseThrow(() -> new ActivationRequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<ActivationRequest> search(String clientId, ActivationStatus status, String customerId, Pageable pageable) {
        Specification<ActivationRequest> spec = (root, query, cb) -> cb.equal(root.get("clientId"), clientId);
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (customerId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        return repository.findAll(spec, pageable);
    }

    private CreationResult asRepetition(ActivationRequest existing, CreateActivationCommand command) {
        if (!existing.isSameRequest(command.clientId(), command.customerId(), command.productCode())) {
            throw new DuplicateExternalIdException(command.externalId());
        }
        return CreationResult.existing(existing);
    }
}
