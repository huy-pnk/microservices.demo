package hp.microservice.demo.transaction_service.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentRequest(
        UUID transactionId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String fromCurrency,
        @NotBlank @Size(min = 3, max = 3) String toCurrency,
        @NotBlank String cardNetwork,
        @NotBlank @Size(min = 2, max = 2) String country
) {}
