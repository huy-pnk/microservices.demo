package hp.microservice.demo.api_gateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import hp.microservice.demo.api_gateway.aspect.GatewayLoggingAspect;
import hp.microservice.demo.api_gateway.filter.LoggingGlobalFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayLoggingAspectTest {

    private Logger aspectLogger;
    private ListAppender<ILoggingEvent> listAppender;

    private GatewayLoggingAspect aspect;
    private GlobalFilter proxiedFilter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        aspectLogger = (Logger) LoggerFactory.getLogger(GatewayLoggingAspect.class);

        listAppender = new ListAppender<>();
        listAppender.start();
        aspectLogger.addAppender(listAppender);

        aspect = new GatewayLoggingAspect();

        AspectJProxyFactory factory = new AspectJProxyFactory(new LoggingGlobalFilter());
        factory.addAspect(aspect);
        proxiedFilter = factory.getProxy();

        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @AfterEach
    void tearDown() {
        aspectLogger.detachAppender(listAppender);
        aspectLogger.setLevel(null);
    }

    @Test
    void debugEnabled_normalRequest_logsRequestAndResponse() {
        aspectLogger.setLevel(Level.DEBUG);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/payments/123")
                .header("X-Request-Id", "req-001")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        exchange.getResponse().setStatusCode(HttpStatus.OK);

        StepVerifier.create(proxiedFilter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, times(1)).filter(any());

        assertThat(listAppender.list).hasSize(2);

        ILoggingEvent requestEvent = listAppender.list.get(0);
        assertThat(requestEvent.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(requestEvent.getFormattedMessage()).contains("gateway request");
        assertThat(requestEvent.getFormattedMessage()).contains("GET");
        assertThat(requestEvent.getFormattedMessage()).contains("/api/payments/123");

        ILoggingEvent responseEvent = listAppender.list.get(1);
        assertThat(responseEvent.getLevel()).isEqualTo(Level.DEBUG);
        assertThat(responseEvent.getFormattedMessage()).contains("gateway response");
        assertThat(responseEvent.getFormattedMessage()).contains("200");
        assertThat(responseEvent.getFormattedMessage()).contains("durationMs");
    }

    @Test
    void debugEnabled_authorizationHeaderPresent_redactedInLog() {
        aspectLogger.setLevel(Level.DEBUG);

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/payments")
                .header("Authorization", "Bearer secret-token")
                .header("X-Request-Id", "req-002")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(proxiedFilter.filter(exchange, chain))
                .verifyComplete();

        assertThat(listAppender.list).isNotEmpty();
        ILoggingEvent requestEvent = listAppender.list.get(0);
        assertThat(requestEvent.getFormattedMessage()).doesNotContain("Bearer secret-token");
        assertThat(requestEvent.getFormattedMessage()).contains("***");
    }

    @Test
    void debugDisabled_noLogsEmitted_chainStillInvoked() {
        aspectLogger.setLevel(Level.INFO);

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/payments/456")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(proxiedFilter.filter(exchange, chain))
                .verifyComplete();

        verify(chain, times(1)).filter(any());
        assertThat(listAppender.list).isEmpty();
    }

    @Test
    void debugEnabled_downstreamError_aspectLogsErrorAndPropagates() {
        aspectLogger.setLevel(Level.DEBUG);

        RuntimeException downstreamError = new RuntimeException("connection refused");
        when(chain.filter(any())).thenReturn(Mono.error(downstreamError));

        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/payments/789")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        StepVerifier.create(proxiedFilter.filter(exchange, chain))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isSameAs(downstreamError);
                })
                .verify();

        assertThat(listAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("gateway error");
            assertThat(event.getFormattedMessage()).contains("connection refused");
        });
    }
}
