package hp.microservice.demo.transaction_service.service.statehandler;

import hp.microservice.demo.transaction_service.domain.FxRateRequest;
import hp.microservice.demo.transaction_service.domain.SagaContext;
import hp.microservice.demo.transaction_service.domain.Transaction;
import hp.microservice.demo.transaction_service.domain.TransactionStatus;
import hp.microservice.demo.transaction_service.event.PaymentSubmittedEvent;
import hp.microservice.demo.transaction_service.event.TransactionStatusChangedEvent;
import hp.microservice.demo.transaction_service.exception.FxServiceUnavailableException;
import hp.microservice.demo.transaction_service.factory.TransactionOutboxFactory;
import hp.microservice.demo.transaction_service.service.FxGateway;
import hp.microservice.demo.transaction_service.service.OutboxWriter;
import hp.microservice.demo.transaction_service.service.RoutingService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class FxLockingHandler implements TransactionStateHandler {

    private final FxGateway fxGateway;
    private final RoutingService routingService;
    private final TransactionOutboxFactory outboxFactory;
    private final OutboxWriter outboxWriter;
    private final ApplicationEventPublisher eventPublisher;

    public FxLockingHandler(FxGateway fxGateway, RoutingService routingService,
                             TransactionOutboxFactory outboxFactory, OutboxWriter outboxWriter,
                             ApplicationEventPublisher eventPublisher) {
        this.fxGateway = fxGateway;
        this.routingService = routingService;
        this.outboxFactory = outboxFactory;
        this.outboxWriter = outboxWriter;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public TransactionStatus handles() {
        return TransactionStatus.FRAUD_APPROVED;
    }

    @Override
    public Transaction advance(Transaction tx, SagaContext ctx) {
        try {
            if (!tx.getFromCurrency().equals(tx.getToCurrency())) {
                var result = fxGateway.lockRate(FxRateRequest.from(tx));
                tx.setLockedRate(result.lockedRate());
            }

            TransactionStatus oldStatus = tx.getStatus();
            tx.withStatus(TransactionStatus.FX_LOCKED);
            eventPublisher.publishEvent(new TransactionStatusChangedEvent(
                    this, tx.getId(), oldStatus, tx.getStatus(), ctx.actorId()));

            String routingTarget = routingService.resolveConnector(
                    tx.getCardNetwork(), tx.getCountry(), tx.getFromCurrency());

            var submittedEvent = new PaymentSubmittedEvent(
                    tx.getId(),
                    tx.getMerchantId(),
                    tx.getAmount(),
                    tx.getFromCurrency(),
                    tx.getToCurrency(),
                    tx.getLockedRate(),
                    tx.getCardNetwork(),
                    tx.getCountry(),
                    routingTarget,
                    Instant.now()
            );
            outboxWriter.write(outboxFactory.paymentSubmitted(submittedEvent));

            TransactionStatus fxLockedStatus = tx.getStatus();
            tx.withStatus(TransactionStatus.SUBMITTED_TO_BANK);
            eventPublisher.publishEvent(new TransactionStatusChangedEvent(
                    this, tx.getId(), fxLockedStatus, tx.getStatus(), ctx.actorId()));

            return tx;

        } catch (FxServiceUnavailableException ex) {
            TransactionStatus oldStatus = tx.getStatus();
            tx.withStatus(TransactionStatus.FAILED);
            eventPublisher.publishEvent(new TransactionStatusChangedEvent(
                    this, tx.getId(), oldStatus, tx.getStatus(), ctx.actorId()));
            return tx;
        }
    }
}
