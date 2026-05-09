package hp.microservice.demo.transaction_service.repository;

import hp.microservice.demo.transaction_service.domain.Transaction;

public interface TransactionWriteRepository {

    Transaction saveAndFlush(Transaction tx);
}
