package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.TransactionOutbox;
import hp.microservice.demo.transaction_service.repository.OutboxRelayRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class OutboxRelayService extends AbstractOutboxRelayService<TransactionOutbox> {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int MAX_RETRY = 5;

    private final OutboxRelayRepository outbox;
    private final EventPublisher publisher;

    public OutboxRelayService(OutboxRelayRepository outbox, EventPublisher publisher) {
        this.outbox = outbox;
        this.publisher = publisher;
    }

    @Override
    protected List<TransactionOutbox> fetchPendingBatch() {
        return outbox.findPendingBatch(50);
    }

    @Override
    protected void dispatch(TransactionOutbox row) throws Exception {
        publisher.publish("payment.submitted", row.getPartitionKey(), row.getPayload());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markSent(TransactionOutbox row) {
        outbox.markSent(row.getId(), Instant.now());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markFailed(TransactionOutbox row, Exception cause) {
        outbox.incrementRetry(row.getId());
        if (row.getRetryCount() + 1 >= MAX_RETRY) {
            outbox.markFailed(row.getId());
            log.error("Outbox row {} permanently failed after {} retries", row.getId(), MAX_RETRY, cause);
        } else {
            log.warn("Outbox row {} failed dispatch, retryCount={}", row.getId(), row.getRetryCount() + 1, cause);
        }
    }
}
