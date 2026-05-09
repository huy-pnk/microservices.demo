package hp.microservice.demo.transaction_service.domain;

public enum OutboxStatus {
    PENDING,
    SENT,
    FAILED
}
