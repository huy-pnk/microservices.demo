package hp.microservice.demo.transaction_service.service.statehandler;

import hp.microservice.demo.transaction_service.domain.SagaContext;
import hp.microservice.demo.transaction_service.domain.Transaction;
import hp.microservice.demo.transaction_service.domain.TransactionStatus;

public interface TransactionStateHandler {

    TransactionStatus handles();

    Transaction advance(Transaction tx, SagaContext ctx);
}
