package hp.microservice.demo.api_gateway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:65535/jwks",
})
class GatewaySecurityIntegrationTest {

    private static final RedisServer EMBEDDED_REDIS;

    static {
        try {
            EMBEDDED_REDIS = new RedisServer(16381);
            EMBEDDED_REDIS.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { EMBEDDED_REDIS.stop(); } catch (IOException ignored) { }
            }));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> 16381);
        registry.add("spring.data.redis.host", () -> "localhost");
    }

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void unauthenticatedRequestReturns401() {
        webTestClient.post()
            .uri("/api/payments")
            .exchange()
            .expectStatus().isUnauthorized();
    }

    @Test
    void authenticatedWithoutMerchantRoleReturns403() {
        webTestClient
            .mutateWith(SecurityMockServerConfigurers.mockJwt()
                .authorities(new SimpleGrantedAuthority("ROLE_VIEWER")))
            .post()
            .uri("/api/payments")
            .exchange()
            .expectStatus().isForbidden();
    }

    @Test
    void merchantRoleWithoutIdempotencyKeyReturns400() {
        webTestClient
            .mutateWith(SecurityMockServerConfigurers.mockJwt()
                .jwt(builder -> builder
                    .subject("merchant-id")
                    .claim("preferred_username", "acme-merchant")
                    .claim("realm_access", Map.of("roles", List.of("MERCHANT"))))
                .authorities(new SimpleGrantedAuthority("ROLE_MERCHANT")))
            .post()
            .uri("/api/payments")
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"amount\":100}")
            .exchange()
            .expectStatus().isEqualTo(400);
    }
}
