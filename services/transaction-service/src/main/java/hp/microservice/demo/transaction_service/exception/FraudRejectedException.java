package hp.microservice.demo.transaction_service.exception;

public class FraudRejectedException extends RuntimeException {

    private final String fraudReason;

    public FraudRejectedException(String fraudReason) {
        super("Transaction rejected by fraud check: " + fraudReason);
        this.fraudReason = fraudReason;
    }

    public String getFraudReason() {
        return fraudReason;
    }
}
