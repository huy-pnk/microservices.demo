package hp.microservice.demo.transaction_service.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record FxRateResult(
        BigDecimal lockedRate,
        Instant expiresAt
) {}
