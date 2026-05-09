package hp.microservice.demo.transaction_service.service.statehandler;

import hp.microservice.demo.transaction_service.domain.SagaContext;
import hp.microservice.demo.transaction_service.domain.Transaction;
import hp.microservice.demo.transaction_service.domain.TransactionStatus;
import hp.microservice.demo.transaction_service.event.TransactionStatusChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class ReceivedHandler implements TransactionStateHandler {

    private final ApplicationEventPublisher eventPublisher;

    public ReceivedHandler(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public TransactionStatus handles() {
        return TransactionStatus.RECEIVED;
    }

    @Override
    public Transaction advance(Transaction tx, SagaContext ctx) {
        TransactionStatus oldStatus = tx.getStatus();
        tx.withStatus(TransactionStatus.FRAUD_CHECKING);
        eventPublisher.publishEvent(new TransactionStatusChangedEvent(
                this, tx.getId(), oldStatus, tx.getStatus(), ctx.actorId()));
        return tx;
    }
}
