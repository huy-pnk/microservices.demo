package hp.microservice.demo.transaction_service.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hp.microservice.demo.transaction_service.domain.TransactionOutbox;
import hp.microservice.demo.transaction_service.event.PaymentSubmittedEvent;
import hp.microservice.demo.transaction_service.event.WebhookDispatchEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class TransactionOutboxFactory {

    private final ObjectMapper mapper;

    public TransactionOutboxFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public TransactionOutbox paymentSubmitted(PaymentSubmittedEvent event) {
        return TransactionOutbox.builder()
                .id(UUID.randomUUID())
                .transactionId(event.transactionId())
                .eventType("PaymentSubmitted")
                .payload(serialise(event))
                .partitionKey(event.transactionId().toString())
                .createdAt(Instant.now())
                .build();
    }

    public TransactionOutbox webhookDispatch(WebhookDispatchEvent event) {
        return TransactionOutbox.builder()
                .id(UUID.randomUUID())
                .transactionId(event.transactionId())
                .eventType("WebhookDispatch")
                .payload(serialise(event))
                .partitionKey(event.transactionId().toString())
                .createdAt(Instant.now())
                .build();
    }

    private String serialise(Object obj) {
        try {
            return mapper.writeValueAsString(obj);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Failed to serialise outbox payload", ex);
        }
    }
}
