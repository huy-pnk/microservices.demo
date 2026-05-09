package hp.microservice.demo.api_gateway.model;

import java.time.Instant;

public record IdempotencyRecord(
    Status status,
    int httpStatus,
    String body,
    String bodyHash,
    Instant createdAt
) {
    public enum Status {
        IN_FLIGHT,
        COMPLETED
    }

    public static IdempotencyRecord inFlight(String bodyHash) {
        return new IdempotencyRecord(Status.IN_FLIGHT, 0, null, bodyHash, Instant.now());
    }

    public IdempotencyRecord complete(int httpStatus, String body) {
        return new IdempotencyRecord(Status.COMPLETED, httpStatus, body, this.bodyHash, this.createdAt);
    }
}
