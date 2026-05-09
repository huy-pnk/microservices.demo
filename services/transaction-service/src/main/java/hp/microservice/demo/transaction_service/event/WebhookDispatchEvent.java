package hp.microservice.demo.transaction_service.event;

import java.time.Instant;
import java.util.UUID;

public record WebhookDispatchEvent(
        UUID transactionId,
        String callbackUrl,
        Object payload,
        Instant occurredAt
) {}
