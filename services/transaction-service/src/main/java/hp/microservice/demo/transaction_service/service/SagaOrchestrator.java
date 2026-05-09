package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.SagaContext;
import hp.microservice.demo.transaction_service.domain.Transaction;
import hp.microservice.demo.transaction_service.domain.TransactionStatus;
import hp.microservice.demo.transaction_service.service.statehandler.TransactionStateHandler;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class SagaOrchestrator {

    private final Map<TransactionStatus, TransactionStateHandler> handlers;

    public SagaOrchestrator(List<TransactionStateHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        TransactionStateHandler::handles, Function.identity()));
    }

    public Transaction advance(Transaction tx, SagaContext ctx) {
        TransactionStateHandler handler = handlers.get(tx.getStatus());
        if (handler == null) {
            return tx;
        }
        Transaction next = handler.advance(tx, ctx);
        return advance(next, ctx);
    }
}
