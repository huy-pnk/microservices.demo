package hp.microservice.demo.api_gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import redis.embedded.RedisServer;

import java.io.IOException;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "spring.security.oauth2.resourceserver.jwt.issuer-uri=",
    "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:65535/jwks",
})
class ApiGatewayApplicationTests {

    private static final RedisServer EMBEDDED_REDIS;

    static {
        try {
            EMBEDDED_REDIS = new RedisServer(16380);
            EMBEDDED_REDIS.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { EMBEDDED_REDIS.stop(); } catch (IOException ignored) { }
            }));
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", () -> 16380);
        registry.add("spring.data.redis.host", () -> "localhost");
    }

    @Test
    void contextLoads() {
    }
}
