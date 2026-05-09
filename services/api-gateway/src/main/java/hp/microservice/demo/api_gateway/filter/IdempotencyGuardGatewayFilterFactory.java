package hp.microservice.demo.api_gateway.filter;

import hp.microservice.demo.api_gateway.config.IdempotencyProperties;
import hp.microservice.demo.api_gateway.model.IdempotencyRecord;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public class IdempotencyGuardGatewayFilterFactory
        extends AbstractGatewayFilterFactory<IdempotencyGuardGatewayFilterFactory.Config> {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private final ReactiveRedisTemplate<String, IdempotencyRecord> redisTemplate;
    private final IdempotencyProperties properties;

    public IdempotencyGuardGatewayFilterFactory(
            ReactiveRedisTemplate<String, IdempotencyRecord> redisTemplate,
            IdempotencyProperties properties) {
        super(Config.class);
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            if (exchange.getRequest().getMethod() != HttpMethod.POST) {
                return chain.filter(exchange);
            }

            String idempotencyKey = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                return rejectMissingKey(exchange);
            }

            return DataBufferUtils.join(exchange.getRequest().getBody())
                .defaultIfEmpty(exchange.getResponse().bufferFactory().wrap(new byte[0]))
                .flatMap(bodyBuffer -> {
                    byte[] bodyBytes = new byte[bodyBuffer.readableByteCount()];
                    bodyBuffer.read(bodyBytes);
                    DataBufferUtils.release(bodyBuffer);
                    String bodyHash = sha256Hex(bodyBytes);

                    return MerchantIdExtractor.fromSecurityContext()
                        .flatMap(merchantId -> {
                            String redisKey = buildRedisKey(merchantId, idempotencyKey);
                            return processIdempotency(exchange, chain, redisKey, bodyBytes, bodyHash);
                        });
                });
        };
    }

    private Mono<Void> processIdempotency(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            String redisKey,
            byte[] bodyBytes,
            String bodyHash) {

        Duration ttl = properties.getTtl();
        IdempotencyRecord inFlightRecord = IdempotencyRecord.inFlight(bodyHash);

        return redisTemplate.opsForValue()
            .setIfAbsent(redisKey, inFlightRecord, ttl)
            .flatMap(wasSet -> {
                if (Boolean.TRUE.equals(wasSet)) {
                    return forwardAndCache(exchange, chain, redisKey, bodyBytes, bodyHash, ttl);
                }
                return redisTemplate.opsForValue().get(redisKey)
                    .flatMap(existing -> handleDuplicate(exchange, existing, bodyHash));
            });
    }

    private Mono<Void> forwardAndCache(
            ServerWebExchange exchange,
            GatewayFilterChain chain,
            String redisKey,
            byte[] bodyBytes,
            String bodyHash,
            Duration ttl) {

        DataBufferFactory bufferFactory = exchange.getResponse().bufferFactory();
        ServerHttpRequest mutatedRequest = new CachedBodyRequestDecorator(exchange.getRequest(), bodyBytes, bufferFactory);
        AtomicReference<byte[]> capturedBody = new AtomicReference<>(new byte[0]);
        ServerHttpResponse mutatedResponse = new CapturingResponseDecorator(exchange.getResponse(), capturedBody);

        ServerWebExchange mutatedExchange = exchange.mutate()
            .request(mutatedRequest)
            .response(mutatedResponse)
            .build();

        return chain.filter(mutatedExchange)
            .then(Mono.defer(() -> {
                int statusCode = mutatedResponse.getStatusCode() != null
                    ? mutatedResponse.getStatusCode().value()
                    : 200;
                String responseBody = new String(capturedBody.get(), StandardCharsets.UTF_8);
                IdempotencyRecord completedRecord = IdempotencyRecord.inFlight(bodyHash)
                    .complete(statusCode, responseBody);
                return redisTemplate.opsForValue()
                    .set(redisKey, completedRecord, ttl)
                    .then();
            }));
    }

    private Mono<Void> handleDuplicate(ServerWebExchange exchange, IdempotencyRecord existing, String bodyHash) {
        if (existing.status() == IdempotencyRecord.Status.IN_FLIGHT) {
            return writeErrorResponse(exchange, HttpStatus.CONFLICT,
                "Request is already being processed");
        }
        if (!existing.bodyHash().equals(bodyHash)) {
            return writeErrorResponse(exchange, HttpStatus.UNPROCESSABLE_ENTITY,
                "Idempotency key reused with different request body");
        }
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.resolve(existing.httpStatus()));
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes = existing.body() != null
            ? existing.body().getBytes(StandardCharsets.UTF_8)
            : new byte[0];
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private Mono<Void> rejectMissingKey(ServerWebExchange exchange) {
        return writeErrorResponse(exchange, HttpStatus.BAD_REQUEST,
            "Idempotency-Key header is required for POST requests");
    }

    private Mono<Void> writeErrorResponse(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body = """
            {"status":%d,"error":"%s","message":"%s"}""".formatted(
                status.value(), status.getReasonPhrase(), message);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    private String buildRedisKey(String merchantId, String idempotencyKey) {
        return properties.getKeyPrefix() + ":" + merchantId + ":" + idempotencyKey;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static class CachedBodyRequestDecorator extends ServerHttpRequestDecorator {

        private final byte[] bodyBytes;
        private final DataBufferFactory bufferFactory;

        CachedBodyRequestDecorator(ServerHttpRequest delegate, byte[] bodyBytes, DataBufferFactory bufferFactory) {
            super(delegate);
            this.bodyBytes = bodyBytes;
            this.bufferFactory = bufferFactory;
        }

        @Override
        public Flux<DataBuffer> getBody() {
            return Flux.just(bufferFactory.wrap(bodyBytes));
        }
    }

    private static class CapturingResponseDecorator extends ServerHttpResponseDecorator {

        private final AtomicReference<byte[]> capturedBody;

        CapturingResponseDecorator(ServerHttpResponse delegate, AtomicReference<byte[]> capturedBody) {
            super(delegate);
            this.capturedBody = capturedBody;
        }

        @Override
        public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
            return DataBufferUtils.join(Flux.from(body))
                .flatMap(buffer -> {
                    byte[] bytes = new byte[buffer.readableByteCount()];
                    buffer.read(bytes);
                    DataBufferUtils.release(buffer);
                    capturedBody.set(bytes);
                    return getDelegate().writeWith(Mono.just(bufferFactory().wrap(bytes)));
                });
        }
    }

    public static class Config {
    }
}
