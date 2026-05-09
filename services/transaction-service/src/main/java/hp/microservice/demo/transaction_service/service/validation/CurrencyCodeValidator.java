package hp.microservice.demo.transaction_service.service.validation;

import hp.microservice.demo.transaction_service.domain.ValidationContext;
import hp.microservice.demo.transaction_service.web.dto.PaymentRequest;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class CurrencyCodeValidator implements PaymentValidationStep {

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of(
            "USD", "EUR", "GBP", "JPY", "SGD", "AUD", "CAD", "CHF", "CNY", "HKD",
            "NZD", "SEK", "NOK", "DKK", "MXN", "INR", "BRL", "ZAR", "RUB", "KRW",
            "THB", "MYR", "IDR", "PHP", "VND"
    );

    @Override
    public void validate(PaymentRequest request, ValidationContext ctx) {
        if (request.fromCurrency() != null && !SUPPORTED_CURRENCIES.contains(request.fromCurrency().toUpperCase())) {
            ctx.addError("Unsupported fromCurrency: " + request.fromCurrency());
        }
        if (request.toCurrency() != null && !SUPPORTED_CURRENCIES.contains(request.toCurrency().toUpperCase())) {
            ctx.addError("Unsupported toCurrency: " + request.toCurrency());
        }
    }
}
