package br.teste.deroyque.integration;

import br.teste.deroyque.repositories.ActivationRequestRepository;
import br.teste.deroyque.services.ActivationRequestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Roda contra o PostgreSQL real (mesma versão do docker-compose): valida as migrações Flyway com
 * {@code ddl-auto=validate} e a unicidade sob concorrência. Pulado quando não há Docker disponível.
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class PostgresIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private ActivationRequestService service;
    @Autowired
    private ActivationRequestRepository repository;

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneRecord() throws Exception {
        DuplicateCreationIntegrationTest.assertConcurrentCreationIsSafe(service, repository);
    }
}
