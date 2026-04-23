package hp.microservice.demo.api_gateway;

import hp.microservice.demo.api_gateway.config.IdempotencyProperties;
import hp.microservice.demo.api_gateway.filter.IdempotencyGuardGatewayFilterFactory;
import hp.microservice.demo.api_gateway.model.IdempotencyRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyGuardFilterTest {

    private ReactiveRedisTemplate<String, IdempotencyRecord> redisTemplate;
    private ReactiveValueOperations<String, IdempotencyRecord> valueOps;
    private IdempotencyGuardGatewayFilterFactory factory;
    private GatewayFilterChain chain;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(ReactiveRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        IdempotencyProperties props = new IdempotencyProperties();
        props.setTtl(Duration.ofHours(24));
        props.setKeyPrefix("idempotency");

        factory = new IdempotencyGuardGatewayFilterFactory(redisTemplate, props);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void nonPostRequestPassesThrough() {
        MockServerHttpRequest request = MockServerHttpRequest
            .get("/api/payments/123")
            .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilter filter = factory.apply(new IdempotencyGuardGatewayFilterFactory.Config());
        StepVerifier.create(filter.filter(exchange, chain))
            .verifyComplete();

        verify(chain).filter(exchange);
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void missingIdempotencyKeyReturnsBadRequest() {
        MockServerHttpRequest request = MockServerHttpRequest
            .post("/api/payments")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body("{\"amount\":100}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilter filter = factory.apply(new IdempotencyGuardGatewayFilterFactory.Config());
        Mono<Void> result = filter.filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(merchantJwt("acme")));

        StepVerifier.create(result).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(chain, never()).filter(any());
    }

    @Test
    void firstRequestSetsInFlightAndForwards() {
        when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(Mono.just(true));
        when(valueOps.set(any(), any(), any())).thenReturn(Mono.just(true));

        MockServerHttpRequest request = MockServerHttpRequest
            .post("/api/payments")
            .header("Idempotency-Key", "key-001")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body("{\"amount\":100}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilter filter = factory.apply(new IdempotencyGuardGatewayFilterFactory.Config());
        Mono<Void> result = filter.filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(merchantJwt("acme")));

        StepVerifier.create(result).verifyComplete();

        verify(valueOps).setIfAbsent(eq("idempotency:acme:key-001"), any(), any());
        verify(chain).filter(any());
    }

    @Test
    void duplicateInFlightReturns409() {
        IdempotencyRecord inFlight = IdempotencyRecord.inFlight("somehash");
        when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(Mono.just(false));
        when(valueOps.get(any())).thenReturn(Mono.just(inFlight));

        MockServerHttpRequest request = MockServerHttpRequest
            .post("/api/payments")
            .header("Idempotency-Key", "key-002")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body("{\"amount\":100}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilter filter = factory.apply(new IdempotencyGuardGatewayFilterFactory.Config());
        Mono<Void> result = filter.filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(merchantJwt("acme")));

        StepVerifier.create(result).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(chain, never()).filter(any());
    }

    @Test
    void duplicateCompletedSameBodyReturnsCachedResponse() {
        String bodyJson = "{\"amount\":100}";
        String bodyHash = sha256Hex(bodyJson.getBytes());

        IdempotencyRecord completed = IdempotencyRecord.inFlight(bodyHash)
            .complete(200, "{\"transactionId\":\"tx-123\"}");

        when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(Mono.just(false));
        when(valueOps.get(any())).thenReturn(Mono.just(completed));

        MockServerHttpRequest request = MockServerHttpRequest
            .post("/api/payments")
            .header("Idempotency-Key", "key-003")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body(bodyJson);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilter filter = factory.apply(new IdempotencyGuardGatewayFilterFactory.Config());
        Mono<Void> result = filter.filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(merchantJwt("acme")));

        StepVerifier.create(result).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(chain, never()).filter(any());
    }

    @Test
    void duplicateCompletedDifferentBodyReturns422() {
        String originalHash = sha256Hex("{\"amount\":100}".getBytes());
        IdempotencyRecord completed = IdempotencyRecord.inFlight(originalHash)
            .complete(200, "{\"transactionId\":\"tx-123\"}");

        when(valueOps.setIfAbsent(any(), any(), any())).thenReturn(Mono.just(false));
        when(valueOps.get(any())).thenReturn(Mono.just(completed));

        MockServerHttpRequest request = MockServerHttpRequest
            .post("/api/payments")
            .header("Idempotency-Key", "key-003")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .body("{\"amount\":999}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilter filter = factory.apply(new IdempotencyGuardGatewayFilterFactory.Config());
        Mono<Void> result = filter.filter(exchange, chain)
            .contextWrite(ReactiveSecurityContextHolder.withAuthentication(merchantJwt("acme")));

        StepVerifier.create(result).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verify(chain, never()).filter(any());
    }

    private static JwtAuthenticationToken merchantJwt(String preferredUsername) {
        Jwt jwt = Jwt.withTokenValue("mock-token")
            .header("alg", "RS256")
            .subject(preferredUsername + "-id")
            .claim("preferred_username", preferredUsername)
            .claim("realm_access", Map.of("roles", List.of("MERCHANT")))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build();
        return new JwtAuthenticationToken(jwt,
            List.of(new SimpleGrantedAuthority("ROLE_MERCHANT")));
    }

    private static String sha256Hex(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
