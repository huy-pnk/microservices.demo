package hp.microservice.demo.api_gateway.aspect;

import lombok.extern.slf4j.Slf4j;
import net.logstash.logback.argument.StructuredArguments;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Aspect
@Component
@Slf4j
public class GatewayLoggingAspect {

    private static final Set<String> REDACTED_HEADERS = Set.of(
            "authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key"
    );

    @Pointcut("execution(* hp.microservice.demo.api_gateway.filter.LoggingGlobalFilter.filter(..))")
    public void loggingFilterMethod() {}

    @Around("loggingFilterMethod()")
    public Object logRequestResponse(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!log.isDebugEnabled()) {
            return joinPoint.proceed();
        }

        ServerWebExchange exchange = extractExchange(joinPoint);
        if (exchange == null) {
            return joinPoint.proceed();
        }

        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();
        String query = exchange.getRequest().getURI().getQuery();
        Map<String, String> requestHeaders = redactedHeaders(exchange.getRequest().getHeaders().toSingleValueMap());

        // Body logging omitted: mutating the exchange body inside an aspect is unsafe generically;
        // use a dedicated GlobalFilter with DataBufferUtils.join if body capture is needed later.
        log.debug("gateway request {} {} {} {}",
                StructuredArguments.kv("method", method),
                StructuredArguments.kv("path", path),
                StructuredArguments.kv("query", query),
                StructuredArguments.kv("headers", requestHeaders));

        long start = System.nanoTime();

        @SuppressWarnings("unchecked")
        Mono<Void> downstream = (Mono<Void>) joinPoint.proceed();

        return downstream
                .doOnSuccess(v -> {
                    long durationMs = (System.nanoTime() - start) / 1_000_000;
                    int status = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 0;
                    Map<String, String> responseHeaders = redactedHeaders(
                            exchange.getResponse().getHeaders().toSingleValueMap());
                    log.debug("gateway response {} {} {} {} {}",
                            StructuredArguments.kv("method", method),
                            StructuredArguments.kv("path", path),
                            StructuredArguments.kv("status", status),
                            StructuredArguments.kv("durationMs", durationMs),
                            StructuredArguments.kv("headers", responseHeaders));
                })
                .doOnError(err -> {
                    long durationMs = (System.nanoTime() - start) / 1_000_000;
                    log.debug("gateway error {} {} {} {}",
                            StructuredArguments.kv("method", method),
                            StructuredArguments.kv("path", path),
                            StructuredArguments.kv("durationMs", durationMs),
                            StructuredArguments.kv("error", err.getMessage()));
                });
    }

    private static ServerWebExchange extractExchange(ProceedingJoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if (arg instanceof ServerWebExchange exchange) {
                return exchange;
            }
        }
        return null;
    }

    private static Map<String, String> redactedHeaders(Map<String, String> raw) {
        Map<String, String> result = new LinkedHashMap<>(raw.size());
        raw.forEach((name, value) ->
                result.put(name, REDACTED_HEADERS.contains(name.toLowerCase()) ? "***" : value));
        return result;
    }
}
