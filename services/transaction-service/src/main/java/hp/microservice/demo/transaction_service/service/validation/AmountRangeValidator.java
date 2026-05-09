package hp.microservice.demo.transaction_service.service.validation;

import hp.microservice.demo.transaction_service.domain.ValidationContext;
import hp.microservice.demo.transaction_service.web.dto.PaymentRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class AmountRangeValidator implements PaymentValidationStep {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    @Override
    public void validate(PaymentRequest request, ValidationContext ctx) {
        if (request.amount() == null) {
            return;
        }
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            ctx.addError("Amount must be greater than zero");
        }
        if (request.amount().compareTo(MAX_AMOUNT) > 0) {
            ctx.addError("Amount exceeds maximum allowed value of 1,000,000");
        }
    }
}
