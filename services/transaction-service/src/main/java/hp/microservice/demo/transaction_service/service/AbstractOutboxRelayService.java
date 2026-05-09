package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.TransactionOutbox;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

public abstract class AbstractOutboxRelayService<T extends TransactionOutbox> {

    @Scheduled(fixedDelay = 500)
    public final void pollAndRelay() {
        List<T> batch = fetchPendingBatch();
        for (T row : batch) {
            try {
                dispatch(row);
                markSent(row);
            } catch (Exception ex) {
                markFailed(row, ex);
            }
        }
    }

    protected abstract List<T> fetchPendingBatch();

    protected abstract void dispatch(T row) throws Exception;

    protected abstract void markSent(T row);

    protected abstract void markFailed(T row, Exception cause);
}
