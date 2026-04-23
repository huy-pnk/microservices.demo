package hp.microservice.demo.api_gateway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@Getter
@Setter
@ConfigurationProperties(prefix = "idempotency")
public class IdempotencyProperties {

    @DurationUnit(ChronoUnit.HOURS)
    private Duration ttl = Duration.ofHours(24);

    private String keyPrefix = "idempotency";
}
