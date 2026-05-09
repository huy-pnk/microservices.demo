package hp.microservice.demo.transaction_service.service.adapter;

import hp.microservice.demo.proto.fraud.EvaluateRequest;
import hp.microservice.demo.proto.fraud.EvaluateResponse;
import hp.microservice.demo.proto.fraud.FraudServiceGrpc;
import hp.microservice.demo.transaction_service.domain.FraudRequest;
import hp.microservice.demo.transaction_service.domain.FraudResult;
import hp.microservice.demo.transaction_service.domain.FraudVerdict;
import hp.microservice.demo.transaction_service.exception.FraudServiceUnavailableException;
import hp.microservice.demo.transaction_service.service.FraudGateway;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.grpc.StatusRuntimeException;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class GrpcFraudGateway implements FraudGateway {

    private final FraudServiceGrpc.FraudServiceBlockingStub stub;

    public GrpcFraudGateway(@GrpcClient("fraud-service") FraudServiceGrpc.FraudServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    @CircuitBreaker(name = "fraud-service", fallbackMethod = "fallback")
    @Retry(name = "fraud-service")
    public FraudResult evaluate(FraudRequest request) {
        EvaluateRequest grpcRequest = EvaluateRequest.newBuilder()
                .setTransactionId(request.transactionId().toString())
                .setMerchantId(request.merchantId())
                .setAmount(request.amount().doubleValue())
                .setCurrency(request.currency())
                .setCountry(request.country())
                .setCardNetwork(request.cardNetwork())
                .build();

        EvaluateResponse response;
        try {
            response = stub.withDeadlineAfter(3, TimeUnit.SECONDS).evaluate(grpcRequest);
        } catch (StatusRuntimeException ex) {
            throw new FraudServiceUnavailableException("Fraud service call failed: " + ex.getStatus(), ex);
        }

        FraudVerdict verdict = FraudVerdict.valueOf(response.getVerdict().name());
        return new FraudResult(verdict, response.getReason());
    }

    @SuppressWarnings("unused")
    public FraudResult fallback(FraudRequest request, Exception ex) {
        throw new FraudServiceUnavailableException("Circuit open for fraud-service", ex);
    }
}
