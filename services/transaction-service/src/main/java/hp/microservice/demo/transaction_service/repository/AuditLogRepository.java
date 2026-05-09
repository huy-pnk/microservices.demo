package hp.microservice.demo.transaction_service.repository;

import hp.microservice.demo.transaction_service.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByTransactionIdOrderByOccurredAtAsc(UUID transactionId);
}
