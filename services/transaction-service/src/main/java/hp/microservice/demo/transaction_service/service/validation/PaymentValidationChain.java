package hp.microservice.demo.transaction_service.service.validation;

import hp.microservice.demo.transaction_service.domain.ValidationContext;
import hp.microservice.demo.transaction_service.exception.PaymentValidationException;
import hp.microservice.demo.transaction_service.web.dto.PaymentRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PaymentValidationChain {

    private final List<PaymentValidationStep> steps;

    public PaymentValidationChain(List<PaymentValidationStep> steps) {
        this.steps = steps;
    }

    public void validate(PaymentRequest request) {
        ValidationContext ctx = new ValidationContext();
        for (PaymentValidationStep step : steps) {
            step.validate(request, ctx);
        }
        if (ctx.hasErrors()) {
            throw new PaymentValidationException(ctx.getErrors());
        }
    }
}
