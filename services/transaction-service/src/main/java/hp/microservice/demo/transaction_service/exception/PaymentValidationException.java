package hp.microservice.demo.transaction_service.exception;

import java.util.List;

public class PaymentValidationException extends RuntimeException {

    private final List<String> errors;

    public PaymentValidationException(List<String> errors) {
        super(String.join(", ", errors));
        this.errors = errors;
    }

    public PaymentValidationException(String message) {
        super(message);
        this.errors = List.of(message);
    }

    public List<String> getErrors() {
        return errors;
    }
}
