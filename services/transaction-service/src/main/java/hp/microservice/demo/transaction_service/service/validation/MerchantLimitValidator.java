package hp.microservice.demo.transaction_service.service.validation;

import hp.microservice.demo.transaction_service.domain.ValidationContext;
import hp.microservice.demo.transaction_service.web.dto.PaymentRequest;
import org.springframework.stereotype.Component;

@Component
public class MerchantLimitValidator implements PaymentValidationStep {

    @Override
    public void validate(PaymentRequest request, ValidationContext ctx) {
        // Merchant limit enforcement deferred — always passes for now
    }
}
