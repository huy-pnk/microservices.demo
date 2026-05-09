package hp.microservice.demo.transaction_service.event;

import java.time.Instant;
import java.util.UUID;

public record PaymentResultEvent(
        UUID transactionId,
        String status,
        String bankReference,
        String failureReason,
        Instant occurredAt
) {}
