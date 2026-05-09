package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.AuditLog;
import hp.microservice.demo.transaction_service.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public List<AuditLog> findByTransaction(UUID transactionId) {
        return auditLogRepository.findByTransactionIdOrderByOccurredAtAsc(transactionId);
    }
}
