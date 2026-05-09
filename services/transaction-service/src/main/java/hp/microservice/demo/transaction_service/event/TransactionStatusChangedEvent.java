package hp.microservice.demo.transaction_service.event;

import hp.microservice.demo.transaction_service.domain.TransactionStatus;
import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class TransactionStatusChangedEvent extends ApplicationEvent {

    private final UUID transactionId;
    private final TransactionStatus oldStatus;
    private final TransactionStatus newStatus;
    private final String actorId;

    public TransactionStatusChangedEvent(Object source, UUID transactionId,
                                         TransactionStatus oldStatus, TransactionStatus newStatus,
                                         String actorId) {
        super(source);
        this.transactionId = transactionId;
        this.oldStatus = oldStatus;
        this.newStatus = newStatus;
        this.actorId = actorId;
    }

    public UUID transactionId() {
        return transactionId;
    }

    public TransactionStatus oldStatus() {
        return oldStatus;
    }

    public TransactionStatus newStatus() {
        return newStatus;
    }

    public String actorId() {
        return actorId;
    }
}
