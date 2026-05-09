package hp.microservice.demo.transaction_service.domain;

import java.util.UUID;

public record FxRateRequest(
        UUID transactionId,
        String fromCurrency,
        String toCurrency
) {
    public static FxRateRequest from(Transaction tx) {
        return new FxRateRequest(
                tx.getId(),
                tx.getFromCurrency(),
                tx.getToCurrency()
        );
    }
}
