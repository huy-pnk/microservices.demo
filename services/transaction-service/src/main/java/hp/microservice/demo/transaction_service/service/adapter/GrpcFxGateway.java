package hp.microservice.demo.transaction_service.service.adapter;

import hp.microservice.demo.proto.fx.FxServiceGrpc;
import hp.microservice.demo.proto.fx.LockRateRequest;
import hp.microservice.demo.proto.fx.LockRateResponse;
import hp.microservice.demo.transaction_service.domain.FxRateRequest;
import hp.microservice.demo.transaction_service.domain.FxRateResult;
import hp.microservice.demo.transaction_service.exception.FxServiceUnavailableException;
import hp.microservice.demo.transaction_service.service.FxGateway;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Component
public class GrpcFxGateway implements FxGateway {

    private final FxServiceGrpc.FxServiceBlockingStub stub;

    public GrpcFxGateway(@GrpcClient("fx-service") FxServiceGrpc.FxServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    @CircuitBreaker(name = "fx-service", fallbackMethod = "fallback")
    @Retry(name = "fx-service")
    public FxRateResult lockRate(FxRateRequest request) {
        LockRateRequest grpcRequest = LockRateRequest.newBuilder()
                .setTransactionId(request.transactionId().toString())
                .setFromCurrency(request.fromCurrency())
                .setToCurrency(request.toCurrency())
                .build();

        LockRateResponse response;
        try {
            response = stub.withDeadlineAfter(3, TimeUnit.SECONDS).lockRate(grpcRequest);
        } catch (StatusRuntimeException ex) {
            throw new FxServiceUnavailableException("FX service call failed: " + ex.getStatus(), ex);
        }

        BigDecimal lockedRate = BigDecimal.valueOf(response.getLockedRate());
        Instant expiresAt = Instant.ofEpochSecond(response.getExpiresAt());
        return new FxRateResult(lockedRate, expiresAt);
    }

    @SuppressWarnings("unused")
    public FxRateResult fallback(FxRateRequest request, Exception ex) {
        throw new FxServiceUnavailableException("Circuit open for fx-service", ex);
    }
}
