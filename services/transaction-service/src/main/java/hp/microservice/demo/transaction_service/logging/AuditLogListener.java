package hp.microservice.demo.transaction_service.logging;

import hp.microservice.demo.transaction_service.domain.AuditLog;
import hp.microservice.demo.transaction_service.event.TransactionStatusChangedEvent;
import hp.microservice.demo.transaction_service.repository.AuditLogRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class AuditLogListener {

    private final AuditLogRepository auditLogs;

    public AuditLogListener(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TransactionStatusChangedEvent event) {
        auditLogs.save(AuditLog.builder()
                .transactionId(event.transactionId())
                .event("STATUS_CHANGED")
                .oldValue(event.oldStatus().name())
                .newValue(event.newStatus().name())
                .actor(event.actorId())
                .build());
    }
}
