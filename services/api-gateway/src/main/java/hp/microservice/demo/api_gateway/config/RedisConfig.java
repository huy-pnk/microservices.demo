package hp.microservice.demo.api_gateway.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import hp.microservice.demo.api_gateway.filter.IdempotencyGuardGatewayFilterFactory;
import hp.microservice.demo.api_gateway.model.IdempotencyRecord;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

@Configuration
public class RedisConfig {

    @Bean
    public ObjectMapper redisObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    @Bean
    public ReactiveRedisTemplate<String, IdempotencyRecord> idempotencyRedisTemplate(
            ReactiveRedisConnectionFactory factory,
            ObjectMapper redisObjectMapper) {

        StringRedisSerializer keySerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<IdempotencyRecord> valueSerializer =
            new Jackson2JsonRedisSerializer<>(redisObjectMapper, IdempotencyRecord.class);

        RedisSerializationContext<String, IdempotencyRecord> context =
            RedisSerializationContext.<String, IdempotencyRecord>newSerializationContext(keySerializer)
                .value(valueSerializer)
                .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    @Bean
    public KeyResolver merchantKeyResolver() {
        return exchange -> ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(auth -> auth instanceof JwtAuthenticationToken)
            .cast(JwtAuthenticationToken.class)
            .map(token -> {
                Jwt jwt = token.getToken();
                String preferredUsername = jwt.getClaimAsString("preferred_username");
                return preferredUsername != null ? preferredUsername : jwt.getSubject();
            })
            .defaultIfEmpty("anonymous");
    }

    @Bean
    public RedisRateLimiter redisRateLimiter() {
        return new RedisRateLimiter(50, 100);
    }

    @Bean
    public IdempotencyGuardGatewayFilterFactory idempotencyGuardFilterFactory(
            ReactiveRedisTemplate<String, IdempotencyRecord> idempotencyRedisTemplate,
            IdempotencyProperties idempotencyProperties) {
        return new IdempotencyGuardGatewayFilterFactory(idempotencyRedisTemplate, idempotencyProperties);
    }
}
