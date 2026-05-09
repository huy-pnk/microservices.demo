package hp.microservice.demo.transaction_service.repository;

import hp.microservice.demo.transaction_service.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface TransactionReadRepository {

    Optional<Transaction> findByIdempotencyKey(String key);

    Page<Transaction> findByMerchantId(String merchantId, Pageable pageable);
}
