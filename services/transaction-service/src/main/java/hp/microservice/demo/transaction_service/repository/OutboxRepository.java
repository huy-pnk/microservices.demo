package hp.microservice.demo.transaction_service.repository;

import hp.microservice.demo.transaction_service.domain.TransactionOutbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<TransactionOutbox, UUID>, OutboxRelayRepository {

    @Query("SELECT o FROM TransactionOutbox o WHERE o.status = hp.microservice.demo.transaction_service.domain.OutboxStatus.PENDING ORDER BY o.createdAt ASC")
    List<TransactionOutbox> findPendingBatch(Pageable pageable);

    default List<TransactionOutbox> findPendingBatch(int limit) {
        return findPendingBatch(Pageable.ofSize(limit));
    }

    @Modifying
    @Query("UPDATE TransactionOutbox o SET o.status = hp.microservice.demo.transaction_service.domain.OutboxStatus.SENT, o.sentAt = :sentAt WHERE o.id = :id")
    void markSent(@Param("id") UUID id, @Param("sentAt") Instant sentAt);

    @Modifying
    @Query("UPDATE TransactionOutbox o SET o.retryCount = o.retryCount + 1 WHERE o.id = :id")
    void incrementRetry(@Param("id") UUID id);

    @Modifying
    @Query("UPDATE TransactionOutbox o SET o.status = hp.microservice.demo.transaction_service.domain.OutboxStatus.FAILED WHERE o.id = :id")
    void markFailed(@Param("id") UUID id);
}
