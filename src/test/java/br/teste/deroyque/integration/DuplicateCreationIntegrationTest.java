package br.teste.deroyque.integration;

import br.teste.deroyque.dtos.CreateActivationCommand;
import br.teste.deroyque.dtos.CreationResult;
import br.teste.deroyque.exceptions.DuplicateExternalIdException;
import br.teste.deroyque.repositories.ActivationRequestRepository;
import br.teste.deroyque.services.ActivationRequestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Duplicidade contra um banco de verdade (H2 em modo PostgreSQL), com a constraint criada pelo Flyway.
 * A mesma verificação concorrente roda contra PostgreSQL em {@link PostgresIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("test")
class DuplicateCreationIntegrationTest {

    @Autowired
    private ActivationRequestService service;
    @Autowired
    private ActivationRequestRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAllInBatch();
    }

    @Test
    void identicalRepetitionReturnsExistingRecord() {
        var command = command("EXT-1", "INTERNET-500");

        CreationResult first = service.create(command);
        CreationResult second = service.create(command);

        assertThat(first.created()).isTrue();
        assertThat(second.created()).isFalse();
        assertThat(second.request().getId()).isEqualTo(first.request().getId());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdWithDifferentContentIsRejected() {
        service.create(command("EXT-2", "INTERNET-500"));

        assertThatThrownBy(() -> service.create(command("EXT-2", "TV-100")))
                .isInstanceOf(DuplicateExternalIdException.class);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void sameExternalIdFromAnotherClientIsRejected() {
        service.create(command("EXT-3", "INTERNET-500"));

        var otherClient = new CreateActivationCommand("EXT-3", "CUST-1", "INTERNET-500", "other-system", "corr-87654321");
        assertThatThrownBy(() -> service.create(otherClient)).isInstanceOf(DuplicateExternalIdException.class);
    }

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneRecord() throws Exception {
        assertConcurrentCreationIsSafe(service, repository);
    }

    static void assertConcurrentCreationIsSafe(ActivationRequestService service, ActivationRequestRepository repository)
            throws Exception {
        int threads = 16;
        var command = command("EXT-CONCURRENT-" + UUID.randomUUID(), "INTERNET-500");
        var ready = new CountDownLatch(threads);
        var start = new CountDownLatch(1);

        List<CreationResult> results;
        try (ExecutorService executor = Executors.newFixedThreadPool(threads)) {
            List<Callable<CreationResult>> tasks = IntStream.range(0, threads)
                    .<Callable<CreationResult>>mapToObj(i -> () -> {
                        ready.countDown();
                        start.await();
                        return service.create(command);
                    })
                    .toList();
            List<Future<CreationResult>> futures = tasks.stream().map(executor::submit).toList();
            ready.await();
            start.countDown();
            results = futures.stream().map(DuplicateCreationIntegrationTest::join).toList();
        }

        assertThat(results).filteredOn(CreationResult::created).hasSize(1);
        assertThat(results).extracting(result -> result.request().getId()).containsOnly(results.getFirst().request().getId());
        assertThat(repository.findAll()).filteredOn(r -> r.getExternalId().equals(command.externalId())).hasSize(1);
    }

    private static CreationResult join(Future<CreationResult> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError("Concurrent creation failed", e);
        }
    }

    private static CreateActivationCommand command(String externalId, String productCode) {
        return new CreateActivationCommand(externalId, "CUST-1", productCode, "erp", "corr-12345678");
    }
}
