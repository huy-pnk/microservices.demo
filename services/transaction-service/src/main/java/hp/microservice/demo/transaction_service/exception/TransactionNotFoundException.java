package hp.microservice.demo.transaction_service.exception;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {

    public TransactionNotFoundException(UUID id) {
        super("Transaction not found: " + id);
    }

    public TransactionNotFoundException(String message) {
        super(message);
    }
}
