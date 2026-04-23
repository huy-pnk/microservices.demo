package hp.microservice.demo.api_gateway.filter;

import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

final class MerchantIdExtractor {

    private MerchantIdExtractor() {
    }

    static Mono<String> fromSecurityContext() {
        return ReactiveSecurityContextHolder.getContext()
            .map(ctx -> ctx.getAuthentication())
            .filter(auth -> auth instanceof JwtAuthenticationToken)
            .cast(JwtAuthenticationToken.class)
            .map(token -> {
                Jwt jwt = token.getToken();
                String preferredUsername = jwt.getClaimAsString("preferred_username");
                return preferredUsername != null ? preferredUsername : jwt.getSubject();
            });
    }
}
