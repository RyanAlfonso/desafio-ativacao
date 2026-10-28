package com.example.activation.persistence;

import com.example.activation.domain.ActivationRequest;
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

    /**
     * Bloqueia as solicitações vencidas para envio. {@code SKIP LOCKED} faz com que várias
     * instâncias do serviço peguem lotes disjuntos sem esperar umas pelas outras.
     * Deve ser chamado dentro de uma transação.
     */
    @Query(nativeQuery = true, value = """
            SELECT * FROM activation_request
             WHERE status IN ('PENDING', 'PROCESSING')
               AND next_attempt_at <= :now
             ORDER BY next_attempt_at
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """)
    List<ActivationRequest> lockDueForDispatch(@Param("now") Instant now, @Param("limit") int limit);
}
