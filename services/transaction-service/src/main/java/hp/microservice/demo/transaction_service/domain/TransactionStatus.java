package hp.microservice.demo.transaction_service.domain;

public enum TransactionStatus {
    RECEIVED,
    FRAUD_CHECKING,
    FRAUD_APPROVED,
    FRAUD_REJECTED,
    FX_LOCKED,
    SUBMITTED_TO_BANK,
    SUCCEEDED,
    FAILED,
    REVERSED
}
