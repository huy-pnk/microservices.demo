package hp.microservice.demo.transaction_service.domain;

public record FraudResult(
        FraudVerdict verdict,
        String reason
) {}
