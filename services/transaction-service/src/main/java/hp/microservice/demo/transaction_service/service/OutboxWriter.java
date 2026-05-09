package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.TransactionOutbox;
import hp.microservice.demo.transaction_service.repository.OutboxRepository;
import org.springframework.stereotype.Service;

@Service
public class OutboxWriter {

    private final OutboxRepository outboxRepository;

    public OutboxWriter(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    public void write(TransactionOutbox entry) {
        outboxRepository.save(entry);
    }
}
