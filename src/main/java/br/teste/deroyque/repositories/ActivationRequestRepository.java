package br.teste.deroyque.repositories;

import br.teste.deroyque.models.ActivationRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ActivationRequestRepository
        extends JpaRepository<ActivationRequest, UUID>, JpaSpecificationExecutor<ActivationRequest> {

    Optional<ActivationRequest> findByExternalId(String externalId);

    Optional<ActivationRequest> findByIdAndClientId(UUID id, String clientId);

    @Query("""
            select r.id from ActivationRequest r
            where r.status = br.teste.deroyque.models.enums.ActivationStatus.PENDING
              and r.nextAttemptAt <= :now
            order by r.nextAttemptAt""")
    List<UUID> findDueIds(@Param("now") Instant now, Pageable pageable);

    @Query("""
            select r.id from ActivationRequest r
            where r.status = br.teste.deroyque.models.enums.ActivationStatus.PROCESSING
              and r.updatedAt < :threshold""")
    List<UUID> findStuckIds(@Param("threshold") Instant threshold, Pageable pageable);
}
