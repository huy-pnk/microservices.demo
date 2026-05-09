package hp.microservice.demo.transaction_service.exception;

public class FxServiceUnavailableException extends RuntimeException {

    public FxServiceUnavailableException(String message) {
        super(message);
    }

    public FxServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
