package hp.microservice.demo.transaction_service.web.dto;

import hp.microservice.demo.transaction_service.domain.Transaction;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String merchantId,
        BigDecimal amount,
        String fromCurrency,
        String toCurrency,
        BigDecimal lockedRate,
        String status,
        String fraudVerdict,
        Instant createdAt,
        Instant updatedAt
) {
    public static PaymentResponse from(Transaction tx) {
        return new PaymentResponse(
                tx.getId(),
                tx.getMerchantId(),
                tx.getAmount(),
                tx.getFromCurrency(),
                tx.getToCurrency(),
                tx.getLockedRate(),
                tx.getStatus().name(),
                tx.getFraudVerdict() != null ? tx.getFraudVerdict().name() : null,
                tx.getCreatedAt(),
                tx.getUpdatedAt()
        );
    }
}
