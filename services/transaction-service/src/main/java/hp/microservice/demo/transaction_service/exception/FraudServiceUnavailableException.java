package hp.microservice.demo.transaction_service.exception;

public class FraudServiceUnavailableException extends RuntimeException {

    public FraudServiceUnavailableException(String message) {
        super(message);
    }

    public FraudServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
