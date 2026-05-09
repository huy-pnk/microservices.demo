package hp.microservice.demo.transaction_service.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ValidationContext(List<String> errors) {

    public ValidationContext() {
        this(new ArrayList<>());
    }

    public void addError(String message) {
        errors.add(message);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public List<String> getErrors() {
        return Collections.unmodifiableList(errors);
    }
}
