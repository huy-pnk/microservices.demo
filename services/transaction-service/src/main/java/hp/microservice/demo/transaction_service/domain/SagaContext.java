package hp.microservice.demo.transaction_service.domain;

public record SagaContext(
        String actorId
) {
    public static SagaContext initial() {
        return new SagaContext("system");
    }
}
