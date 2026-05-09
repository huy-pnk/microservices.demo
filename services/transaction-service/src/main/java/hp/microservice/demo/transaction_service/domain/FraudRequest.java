package hp.microservice.demo.transaction_service.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record FraudRequest(
        UUID transactionId,
        String merchantId,
        BigDecimal amount,
        String currency,
        String country,
        String cardNetwork
) {
    public static FraudRequest from(Transaction tx) {
        return new FraudRequest(
                tx.getId(),
                tx.getMerchantId(),
                tx.getAmount(),
                tx.getFromCurrency(),
                tx.getCountry(),
                tx.getCardNetwork()
        );
    }
}
