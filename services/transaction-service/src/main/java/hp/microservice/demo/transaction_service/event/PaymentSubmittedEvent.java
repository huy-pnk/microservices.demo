package hp.microservice.demo.transaction_service.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentSubmittedEvent(
        UUID transactionId,
        String merchantId,
        BigDecimal amount,
        String fromCurrency,
        String toCurrency,
        BigDecimal lockedRate,
        String cardNetwork,
        String country,
        String routingTarget,
        Instant occurredAt
) {}
