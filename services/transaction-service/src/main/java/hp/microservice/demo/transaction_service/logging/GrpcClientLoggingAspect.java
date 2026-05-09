package hp.microservice.demo.transaction_service.logging;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.grpc.StatusRuntimeException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class GrpcClientLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(GrpcClientLoggingAspect.class);

    @Around("execution(public * hp.microservice.demo.transaction_service.service.adapter.GrpcFraudGateway.*(..)) || execution(public * hp.microservice.demo.transaction_service.service.adapter.GrpcFxGateway.*(..))")
    public Object logGrpcCall(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String method = pjp.getSignature().toShortString();
        log.info("gRPC call: {}", method);

        try {
            Object result = pjp.proceed();
            long latencyMs = System.currentTimeMillis() - start;
            log.info("gRPC success: {} latencyMs={}", method, latencyMs);
            return result;
        } catch (CallNotPermittedException ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("gRPC circuit open: {} latencyMs={}", method, latencyMs);
            throw ex;
        } catch (StatusRuntimeException ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("gRPC error: {} status={} latencyMs={}", method, ex.getStatus(), latencyMs);
            throw ex;
        } catch (Throwable ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("gRPC unexpected error: {} latencyMs={} error={}", method, latencyMs, ex.getMessage());
            throw ex;
        }
    }
}
