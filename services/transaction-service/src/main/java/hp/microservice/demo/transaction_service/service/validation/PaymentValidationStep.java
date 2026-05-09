package hp.microservice.demo.transaction_service.service.validation;

import hp.microservice.demo.transaction_service.domain.ValidationContext;
import hp.microservice.demo.transaction_service.exception.PaymentValidationException;
import hp.microservice.demo.transaction_service.web.dto.PaymentRequest;

public interface PaymentValidationStep {

    void validate(PaymentRequest request, ValidationContext ctx) throws PaymentValidationException;
}
