package hp.microservice.demo.transaction_service.service.statehandler;

import hp.microservice.demo.transaction_service.domain.FraudRequest;
import hp.microservice.demo.transaction_service.domain.FraudVerdict;
import hp.microservice.demo.transaction_service.domain.SagaContext;
import hp.microservice.demo.transaction_service.domain.Transaction;
import hp.microservice.demo.transaction_service.domain.TransactionStatus;
import hp.microservice.demo.transaction_service.event.TransactionStatusChangedEvent;
import hp.microservice.demo.transaction_service.exception.FraudRejectedException;
import hp.microservice.demo.transaction_service.exception.FraudServiceUnavailableException;
import hp.microservice.demo.transaction_service.service.FraudGateway;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class FraudCheckingHandler implements TransactionStateHandler {

    private final FraudGateway fraudGateway;
    private final ApplicationEventPublisher eventPublisher;

    public FraudCheckingHandler(FraudGateway fraudGateway, ApplicationEventPublisher eventPublisher) {
        this.fraudGateway = fraudGateway;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public TransactionStatus handles() {
        return TransactionStatus.FRAUD_CHECKING;
    }

    @Override
    public Transaction advance(Transaction tx, SagaContext ctx) {
        try {
            var result = fraudGateway.evaluate(FraudRequest.from(tx));
            TransactionStatus oldStatus = tx.getStatus();

            if (result.verdict() == FraudVerdict.REJECTED) {
                tx.setFraudVerdict(FraudVerdict.REJECTED);
                tx.setFraudReason(result.reason());
                tx.withStatus(TransactionStatus.FRAUD_REJECTED);
                eventPublisher.publishEvent(new TransactionStatusChangedEvent(
                        this, tx.getId(), oldStatus, tx.getStatus(), ctx.actorId()));
                throw new FraudRejectedException(result.reason());
            }

            tx.setFraudVerdict(result.verdict());
            tx.setFraudReason(result.reason());
            tx.withStatus(TransactionStatus.FRAUD_APPROVED);
            eventPublisher.publishEvent(new TransactionStatusChangedEvent(
                    this, tx.getId(), oldStatus, tx.getStatus(), ctx.actorId()));
            return tx;

        } catch (FraudServiceUnavailableException ex) {
            TransactionStatus oldStatus = tx.getStatus();
            tx.setFraudReason("Fraud service unavailable: " + ex.getMessage());
            tx.withStatus(TransactionStatus.FAILED);
            eventPublisher.publishEvent(new TransactionStatusChangedEvent(
                    this, tx.getId(), oldStatus, tx.getStatus(), ctx.actorId()));
            return tx;
        }
    }
}
