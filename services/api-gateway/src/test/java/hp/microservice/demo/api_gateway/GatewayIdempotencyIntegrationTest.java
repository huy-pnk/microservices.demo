package hp.microservice.demo.api_gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayIdempotencyIntegrationTest {

    private static final RedisServer EMBEDDED_REDIS;
    private static final WireMockServer WIRE_MOCK;
    private static final RSAKey RSA_KEY;
    private static final String ISSUER;

    static {
        try {
            EMBEDDED_REDIS = new RedisServer(16383);
            EMBEDDED_REDIS.start();

            RSA_KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();

            WIRE_MOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            WIRE_MOCK.start();
            ISSUER = "http://localhost:" + WIRE_MOCK.port() + "/realms/payments";

            String jwksJson = "{\"keys\":[" + RSA_KEY.toPublicJWK().toJSONString() + "]}";
            WIRE_MOCK.stubFor(get(urlEqualTo("/realms/payments/protocol/openid-connect/certs"))
                .willReturn(aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(jwksJson)));

            WIRE_MOCK.stubFor(post(urlPathEqualTo("/api/payments"))
                .willReturn(aResponse()
                    .withStatus(202)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"transactionId\":\"tx-test-001\"}")));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { EMBEDDED_REDIS.stop(); } catch (IOException ignored) { }
                WIRE_MOCK.stop();
            }));
        } catch (IOException | JOSEException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> 16383);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("TRANSACTION_SERVICE_URL", () -> "http://localhost:" + WIRE_MOCK.port());
        registry.add("KEYCLOAK_ISSUER_URI", () -> ISSUER);
    }

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + port)
            .responseTimeout(java.time.Duration.ofSeconds(10))
            .build();
    }

    private String merchantToken() throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(ISSUER)
            .subject("merchant-uuid")
            .claim("preferred_username", "acme-merchant")
            .claim("realm_access", Map.of("roles", List.of("MERCHANT")))
            .issueTime(Date.from(Instant.now()))
            .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
            .build();
        SignedJWT jwt = new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(),
            claims);
        jwt.sign(new RSASSASigner(RSA_KEY));
        return jwt.serialize();
    }

    @Test
    void merchantRoleWithIdempotencyKeyForwards() throws JOSEException {
        client().post()
            .uri("/api/payments")
            .header("Authorization", "Bearer " + merchantToken())
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"amount\":100}")
            .exchange()
            .expectStatus().isEqualTo(202);

        WIRE_MOCK.verify(postRequestedFor(urlPathEqualTo("/api/payments")));
    }

    @Test
    void duplicateKeyWithSameBodyReturnsCachedResponse() throws JOSEException {
        String token = merchantToken();
        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"amount\":200}";

        client().post()
            .uri("/api/payments")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isEqualTo(202);

        WIRE_MOCK.resetRequests();

        client().post()
            .uri("/api/payments")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus().isEqualTo(202);

        WIRE_MOCK.verify(0, postRequestedFor(urlPathEqualTo("/api/payments")));
    }

    @Test
    void duplicateKeyWithDifferentBodyReturns422() throws JOSEException {
        String token = merchantToken();
        String idempotencyKey = UUID.randomUUID().toString();

        client().post()
            .uri("/api/payments")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"amount\":300}")
            .exchange()
            .expectStatus().isEqualTo(202);

        client().post()
            .uri("/api/payments")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue("{\"amount\":999}")
            .exchange()
            .expectStatus().isEqualTo(422);
    }
}
