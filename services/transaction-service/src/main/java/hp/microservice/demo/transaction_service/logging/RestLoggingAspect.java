package hp.microservice.demo.transaction_service.logging;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class RestLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(RestLoggingAspect.class);

    private final LogRedactor redactor;
    private final boolean payloadEnabled;

    public RestLoggingAspect(LogRedactor redactor,
                             @Value("${logging.payload.enabled:false}") boolean payloadEnabled) {
        this.redactor = redactor;
        this.payloadEnabled = payloadEnabled;
    }

    @Around("within(@org.springframework.web.bind.annotation.RestController *) && within(hp.microservice.demo.transaction_service.web..*)")
    public Object logRestCall(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        String method = pjp.getSignature().toShortString();

        if (payloadEnabled) {
            log.debug("REST entry: {} args={}", method, pjp.getArgs());
        } else {
            log.info("REST entry: {}", method);
        }

        try {
            Object result = pjp.proceed();
            long latencyMs = System.currentTimeMillis() - start;
            if (payloadEnabled) {
                log.debug("REST exit: {} latencyMs={} response={}", method, latencyMs, redactor.sanitise(result));
            } else {
                log.info("REST exit: {} latencyMs={}", method, latencyMs);
            }
            return result;
        } catch (Throwable ex) {
            long latencyMs = System.currentTimeMillis() - start;
            log.warn("REST error: {} latencyMs={} error={} message={}",
                    method, latencyMs, ex.getClass().getSimpleName(), ex.getMessage());
            throw ex;
        }
    }
}
