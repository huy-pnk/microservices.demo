package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.Transaction;
import hp.microservice.demo.transaction_service.domain.TransactionStatus;
import hp.microservice.demo.transaction_service.event.PaymentResultEvent;
import hp.microservice.demo.transaction_service.event.TransactionStatusChangedEvent;
import hp.microservice.demo.transaction_service.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

@Component
public class PaymentResultHandler {

    private static final Logger log = LoggerFactory.getLogger(PaymentResultHandler.class);

    private final TransactionRepository transactions;
    private final ApplicationEventPublisher eventPublisher;

    public PaymentResultHandler(TransactionRepository transactions, ApplicationEventPublisher eventPublisher) {
        this.transactions = transactions;
        this.eventPublisher = eventPublisher;
    }

    @KafkaListener(topics = "payment.result", groupId = "transaction-group")
    @Transactional
    public void handle(PaymentResultEvent event, Acknowledgment acknowledgment) {
        Optional<Transaction> optional = transactions.findById(event.transactionId());
        if (optional.isEmpty()) {
            log.warn("Received result for unknown transaction {}, acking idempotently", event.transactionId());
            acknowledgment.acknowledge();
            return;
        }

        Transaction tx = optional.get();
        TransactionStatus targetStatus = TransactionStatus.valueOf(event.status());

        if (tx.getStatus() == targetStatus) {
            log.debug("Transaction {} already in status {}, acking idempotently", tx.getId(), targetStatus);
            acknowledgment.acknowledge();
            return;
        }

        TransactionStatus oldStatus = tx.getStatus();
        tx.setBankReference(event.bankReference());
        tx.withStatus(targetStatus);

        eventPublisher.publishEvent(new TransactionStatusChangedEvent(
                this, tx.getId(), oldStatus, tx.getStatus(), "bank-connector"));

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                acknowledgment.acknowledge();
            }
        });
    }
}
