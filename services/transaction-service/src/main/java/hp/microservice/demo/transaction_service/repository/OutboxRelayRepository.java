package hp.microservice.demo.transaction_service.repository;

import hp.microservice.demo.transaction_service.domain.TransactionOutbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRelayRepository {

    List<TransactionOutbox> findPendingBatch(int limit);

    void markSent(UUID id, Instant sentAt);

    void incrementRetry(UUID id);

    void markFailed(UUID id);
}
