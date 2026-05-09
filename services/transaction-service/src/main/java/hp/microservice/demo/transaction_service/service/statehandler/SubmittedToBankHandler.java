package hp.microservice.demo.transaction_service.service.statehandler;

import hp.microservice.demo.transaction_service.domain.SagaContext;
import hp.microservice.demo.transaction_service.domain.Transaction;
import hp.microservice.demo.transaction_service.domain.TransactionStatus;
import org.springframework.stereotype.Component;

@Component
public class SubmittedToBankHandler implements TransactionStateHandler {

    @Override
    public TransactionStatus handles() {
        return TransactionStatus.SUBMITTED_TO_BANK;
    }

    @Override
    public Transaction advance(Transaction tx, SagaContext ctx) {
        return tx;
    }
}
