package hp.microservice.demo.transaction_service.repository;

import hp.microservice.demo.transaction_service.domain.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>,
        TransactionReadRepository, TransactionWriteRepository {
}
