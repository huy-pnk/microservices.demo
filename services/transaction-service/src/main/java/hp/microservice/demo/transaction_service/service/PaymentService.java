package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.SagaContext;
import hp.microservice.demo.transaction_service.domain.Transaction;
import hp.microservice.demo.transaction_service.domain.TransactionStatus;
import hp.microservice.demo.transaction_service.exception.TransactionNotFoundException;
import hp.microservice.demo.transaction_service.repository.TransactionRepository;
import hp.microservice.demo.transaction_service.web.dto.PaymentRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class PaymentService {

    private final SagaOrchestrator sagaOrchestrator;
    private final TransactionRepository transactions;

    public PaymentService(SagaOrchestrator sagaOrchestrator, TransactionRepository transactions) {
        this.sagaOrchestrator = sagaOrchestrator;
        this.transactions = transactions;
    }

    public Transaction submit(PaymentRequest request, String merchantId, String idempotencyKey) {
        var tx = buildTransaction(request, merchantId, idempotencyKey);
        transactions.save(tx);
        return sagaOrchestrator.advance(tx, SagaContext.initial());
    }

    private Transaction buildTransaction(PaymentRequest request, String merchantId, String idempotencyKey) {
        UUID id = (request.transactionId() != null) ? request.transactionId() : UUID.randomUUID();
        Instant now = Instant.now();
        return Transaction.builder()
                .id(id)
                .idempotencyKey(idempotencyKey)
                .merchantId(merchantId)
                .amount(request.amount())
                .fromCurrency(request.fromCurrency())
                .toCurrency(request.toCurrency())
                .cardNetwork(request.cardNetwork())
                .country(request.country())
                .status(TransactionStatus.RECEIVED)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Transactional(readOnly = true)
    public Transaction findByIdForMerchant(UUID id, String merchantId) {
        return transactions.findById(id)
                .filter(tx -> tx.getMerchantId().equals(merchantId))
                .orElseThrow(() -> new TransactionNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Page<Transaction> findByMerchant(String merchantId, Pageable pageable) {
        return transactions.findByMerchantId(merchantId, pageable);
    }
}
