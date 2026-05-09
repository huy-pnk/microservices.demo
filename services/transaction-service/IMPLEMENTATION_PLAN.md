# transaction-service — Detailed Implementation Plan

## Preliminary findings from codebase inspection

- **Multi-module build**: `microservices.demo/settings.gradle` already includes `services:transaction-service` and `proto`. The root `build.gradle` applies the snapshot repository to all subprojects. Transaction-service must always be built from the root `microservices.demo/` directory — the root `settings.gradle` already handles both the `proto` module and this service together.
- **Proto contracts exist and are compiled**: `fraud.proto` and `fx.proto` are authored. Generated stubs are in `hp.microservice.demo.proto.fraud` and `hp.microservice.demo.proto.fx`. The proto module uses grpc `1.68.0` and protobuf `3.25.5`.
- **api-gateway conventions to inherit**: Spring Boot `3.5.14-SNAPSHOT`, Spring Cloud `2025.0.0-SNAPSHOT`, `logstash-logback-encoder:8.0`, `micrometer-registry-prometheus`, Lombok, two-stage Dockerfile (`eclipse-temurin:21-jdk` → `eclipse-temurin:21-jre`), `logback-spring.xml` with `JSON_STDOUT` / `local` profile split, `realm_access.roles` → `ROLE_<role>` authority mapping.
- **No Redis env var in configmap**: the deployment and configmap have no `REDIS_*` keys. Idempotency deduplication in transaction-service must not require Redis — the gateway owns that. Transaction-service deduplication is DB-level (unique constraint on `idempotency_key` in the `transactions` table).
- **Keycloak issuer**: `http://keycloak.auth.svc.cluster.local:8080/realms/payments` — same in configmap.

---

## Phase 1 — Gradle build wiring

### 1.1 Build script changes

Replace the skeleton `build.gradle` with a full dependency set. Transaction-service must always be built from the root `microservices.demo/` directory — do not run `gradlew.bat` from inside the service directory. The root `settings.gradle` already includes both `proto` and `services:transaction-service`, so no changes to `settings.gradle` are needed.

**Plugins to add:**

| Plugin | Coordinate | Version |
|---|---|---|
| Spring Boot | `org.springframework.boot` | `3.5.14-SNAPSHOT` (match api-gateway) |
| Dependency Management | `io.spring.dependency-management` | `1.1.7` |

**Runtime dependencies:**

| Dependency | Notes |
|---|---|
| `spring-boot-starter-web` | Servlet-stack REST (not reactive — transaction-service is blocking I/O, virtual threads handle concurrency) |
| `spring-boot-starter-security` | Base security; oauth2-resource-server pulls it transitively but explicit is cleaner |
| `spring-boot-starter-oauth2-resource-server` | JWT validation via JWKS |
| `spring-boot-starter-data-jpa` | Hibernate + JPA |
| `spring-boot-starter-actuator` | `/actuator/health`, metrics |
| `spring-boot-starter-validation` | `@Valid` on request bodies |
| `spring-boot-starter-aop` | Required for `RestLoggingAspect` and `GrpcClientLoggingAspect` (Phase 12.1) |
| `org.postgresql:postgresql` | Driver; version managed by Spring BOM |
| `org.flywaydb:flyway-core` | Schema migrations; version managed by Spring BOM |
| `org.flywaydb:flyway-database-postgresql` | Flyway PostgreSQL dialect module (required since Flyway 10) |
| `org.springframework.kafka:spring-kafka` | `KafkaTemplate`, `@KafkaListener` |
| `io.grpc:grpc-netty-shaded:1.68.0` | gRPC transport (matches proto module grpcVersion) |
| `io.grpc:grpc-stub:1.68.0` | Re-exported by proto but needed at runtime |
| `io.grpc:grpc-protobuf:1.68.0` | Same |
| `net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE` | `@GrpcClient` injection, compatible with Spring Boot 3.5.x |
| `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` | Circuit breaker + retry; `2.2.0` is the latest GA on Boot 3 |
| `io.micrometer:micrometer-registry-prometheus` | Metrics scraping |
| `net.logstash.logback:logstash-logback-encoder:8.0` | Match api-gateway |
| `project(':proto')` | Generated stubs via multi-module dependency |
| Lombok (compile-only + annotation processor) | Match api-gateway |

**Compile-only dependencies:**

| Dependency | Notes |
|---|---|
| `javax.annotation:javax.annotation-api:1.3.2` | Required because the proto module declares it `compileOnly`; generated stubs reference `@javax.annotation.Generated` and it is not propagated to consumers |

**Test dependencies:**

| Dependency | Notes |
|---|---|
| `spring-boot-starter-test` | JUnit 5, Mockito, AssertJ |
| `spring-kafka-test` | `EmbeddedKafkaBroker` for unit-scope Kafka tests |
| `org.springframework.security:spring-security-test` | `@WithMockUser`, `MockMvc` JWT mocking |
| `org.testcontainers:postgresql` | Integration tests — real Postgres |
| `org.testcontainers:kafka` | Integration tests — real Kafka container |
| `org.testcontainers:junit-jupiter` | JUnit 5 Testcontainers lifecycle |
| `io.grpc:grpc-testing:1.68.0` | `GrpcCleanupRule`, in-process server for gRPC stub mocking |

**gRPC version conflict resolution:**

`net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE` ships with `grpc-core:1.65.x` internally. The proto module uses `grpcVersion = '1.68.0'`. Add the following resolution strategy to `build.gradle` to force all gRPC artifacts to `1.68.0`:

```groovy
configurations.all {
    resolutionStrategy.force(
        "io.grpc:grpc-core:1.68.0",
        "io.grpc:grpc-stub:1.68.0",
        "io.grpc:grpc-protobuf:1.68.0",
        "io.grpc:grpc-netty-shaded:1.68.0"
    )
}
```

**Minimal `application.yml` required before Phase 5:**

A stub `application.yml` with `spring.security.oauth2.resourceserver.jwt.issuer-uri` and `spring.datasource.*` placeholders must exist before running any Phase 5–6 tests — the Spring context will fail to load without them. Phase 12a expands this to the full version, but these minimum keys must be present from Phase 1 onward:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://keycloak.localhost:8090/realms/payments}
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/paymentdb}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:postgres}
```

### Phase 1 verification

Run from `microservices.demo/` (the root — do not run from the service subdirectory):

```powershell
.\gradlew.bat :services:transaction-service:dependencies --configuration runtimeClasspath
```

Confirm `grpc-netty-shaded`, `spring-kafka`, `flyway-core`, and the proto stub classes resolve with no version conflicts. Verify all `io.grpc:*` artifacts resolve to `1.68.0` (no `1.65.x` from the starter).

---

## Phase 2 — Package layout

Root package: `hp.microservice.demo.transaction_service`

```
config/
    SecurityConfig               # WebSecurityCustomizer, oauth2ResourceServer, JWT converter
    KafkaProducerConfig          # KafkaTemplate bean, producer factory, idempotent producer props
    KafkaConsumerConfig          # Consumer factory, DefaultErrorHandler, DLT recoverer
    GrpcClientConfig             # @GrpcClient injection only — no manual Resilience4j decoration
    OutboxSchedulerConfig        # @EnableScheduling, scheduler thread pool

web/
    PaymentController            # POST /api/v1/payments, GET /api/v1/payments/{id}, GET /api/v1/payments (list)
    PaymentControllerAdvice      # @RestControllerAdvice — maps domain exceptions to ProblemDetail (RFC 9457)

service/
    PaymentService               # Entry point only — persists transaction, delegates to SagaOrchestrator
    SagaOrchestrator             # Holds Map<TransactionStatus, TransactionStateHandler>; dispatches state transitions
    FraudGateway                 # interface — domain contract for fraud evaluation (DIP)
    FxGateway                    # interface — domain contract for FX rate locking (DIP)
    RoutingService               # Encapsulates RoutingRule lookup logic (extracted from PaymentService)
    OutboxRelayService           # extends AbstractOutboxRelayService<TransactionOutbox> — Kafka relay
    WebhookRelayService          # extends AbstractOutboxRelayService<TransactionOutbox> — HTTP POST relay (Phase 10)
    AbstractOutboxRelayService   # abstract — Template Method pattern; polling skeleton
    PaymentResultHandler         # @KafkaListener on payment.result — advances state machine
    EventPublisher               # interface — abstracts KafkaTemplate from OutboxRelayService (DIP)
    KafkaEventPublisher          # implements EventPublisher — wraps KafkaTemplate

service/adapter/
    GrpcFraudGateway             # implements FraudGateway — gRPC adapter over FraudServiceGrpc stub; @CircuitBreaker + @Retry
    GrpcFxGateway                # implements FxGateway — gRPC adapter over FxServiceGrpc stub; @CircuitBreaker + @Retry

service/validation/
    PaymentValidationStep        # interface — Chain of Responsibility contract
    CurrencyCodeValidator        # implements PaymentValidationStep
    AmountRangeValidator         # implements PaymentValidationStep
    MerchantLimitValidator       # implements PaymentValidationStep
    PaymentValidationChain       # assembles and runs the ordered chain

service/statehandler/
    TransactionStateHandler      # interface — Strategy contract for state transitions
    ReceivedHandler              # handles RECEIVED → FRAUD_CHECKING
    FraudCheckingHandler         # handles FRAUD_CHECKING → FRAUD_APPROVED / FRAUD_REJECTED
    FxLockingHandler             # handles FRAUD_APPROVED → FX_LOCKED / SUBMITTED_TO_BANK
    SubmittedToBankHandler       # handles SUBMITTED_TO_BANK → SUCCEEDED / FAILED / REVERSED

repository/
    TransactionRepository        # JPA repository for Transaction entity
    TransactionReadRepository    # fragment — query methods used by controllers/read paths
    TransactionWriteRepository   # fragment — mutation methods used by PaymentService
    OutboxRepository             # JPA repository for TransactionOutbox entity
    OutboxRelayRepository        # fragment — findPendingBatch, markSent, incrementRetry, markFailed
    AuditLogRepository           # JPA repository for AuditLog entity
    RoutingRulesRepository       # JPA repository for RoutingRule entity

domain/
    Transaction                  # @Entity — central aggregate
    TransactionOutbox            # @Entity — outbox table (base for both Kafka + webhook rows)
    AuditLog                     # @Entity — append-only audit trail
    RoutingRule                  # @Entity — static routing configuration
    TransactionStatus            # enum — state machine values
    FraudRequest                 # record — domain value object (no proto types)
    FraudResult                  # record — domain value object
    FraudVerdict                 # enum — APPROVED / REVIEW / REJECTED
    FxRateRequest                # record — domain value object
    FxRateResult                 # record — domain value object
    SagaContext                  # record — carries transient saga state across handler calls
    ValidationContext            # record — accumulates validation errors across chain steps

event/
    PaymentSubmittedEvent        # record — published to payment.submitted
    PaymentResultEvent           # record — consumed from payment.result
    WebhookDispatchEvent         # record — published to outbox for webhook delivery (Phase 10)
    TransactionStatusChangedEvent # Spring ApplicationEvent — triggers AuditLogListener

factory/
    TransactionOutboxFactory     # Centralises outbox row construction + JSON serialisation

logging/
    AuditLogListener             # @TransactionalEventListener — writes AuditLog on status change
    RestLoggingAspect            # @Aspect — REST controller entry/exit
    GrpcClientLoggingAspect      # @Aspect — GrpcFraudGateway / GrpcFxGateway entry/exit
    LogRedactor                  # Utility — centralised field-masking policy

web/dto/
    PaymentRequest               # record — REST inbound, validated
    PaymentResponse              # record — REST outbound
    ErrorResponse                # record — RFC 9457 ProblemDetail wrapper
```

---

## Phase 2.1 — Design Patterns and SOLID

This section documents every design pattern and SOLID principle applied across the service. Each entry names the pattern, identifies the exact class or interface it touches, provides a short snippet to make it actionable, and explains why it fits here rather than an alternative.

---

### SOLID: Single Responsibility — `PaymentService`

`PaymentService` in the original sketch orchestrates fraud evaluation, FX locking, routing rule lookup, outbox row construction, and audit log writes — five distinct responsibilities. The decomposition below makes each class answerable for one thing:

| Class | Single responsibility |
|---|---|
| `PaymentService` | Accept a validated `PaymentRequest`, resolve merchant identity from the JWT, and invoke the `SagaOrchestrator`. Nothing else. |
| `SagaOrchestrator` | Dispatch the transaction through `TransactionStateHandler` instances in sequence. |
| `RoutingService` | Select a `RoutingRule` from the database given `cardNetwork`, `country`, and `fromCurrency`. |
| `TransactionOutboxFactory` | Construct and persist `TransactionOutbox` rows (owns the JSON serialisation concern). |
| `AuditLogListener` | Write `AuditLog` rows in response to `TransactionStatusChangedEvent`. |

`PaymentService` after decomposition:

```java
@Service
@Transactional
public class PaymentService {

    private final SagaOrchestrator sagaOrchestrator;
    private final TransactionRepository transactions;

    public PaymentService(SagaOrchestrator sagaOrchestrator,
                          TransactionRepository transactions) {
        this.sagaOrchestrator = sagaOrchestrator;
        this.transactions = transactions;
    }

    public Transaction submit(PaymentRequest request, String merchantId, String idempotencyKey) {
        var tx = Transaction.create(request, merchantId, idempotencyKey);
        transactions.save(tx);
        return sagaOrchestrator.advance(tx, SagaContext.initial());
    }
}
```

Every cross-cutting concern (audit, outbox construction, routing) has been moved out. `PaymentService` is now measurably testable with only a `SagaOrchestrator` mock and a repository mock.

---

### SOLID: Single Responsibility — `PaymentControllerAdvice`

`PaymentControllerAdvice` maps several distinct exception families. This is acceptable within a single class because its one responsibility is _exception-to-HTTP-status translation_ — that is a single concern even if the input set is large. However, if the advice grows to include business-rule error enrichment (e.g., fetching the existing transaction for the idempotency key path), extract that lookup into a collaborator:

```java
@RestControllerAdvice
public class PaymentControllerAdvice {

    private final TransactionRepository transactions; // injected for idempotency lookup only

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<PaymentResponse> handleDuplicate(DataIntegrityViolationException ex,
                                                            HttpServletRequest request) {
        String key = extractIdempotencyKey(request);
        return transactions.findByIdempotencyKey(key)
                .map(tx -> ResponseEntity.ok(PaymentResponse.from(tx)))
                .orElseGet(() -> ResponseEntity.internalServerError().build());
    }
    // ... other handlers
}
```

The idempotency lookup belongs here because it is specific to the HTTP response contract. If it were in `PaymentService`, the service layer would need to know about HTTP headers.

---

### SOLID: Open/Closed — State machine transitions

The ad-hoc if/switch over `TransactionStatus` in `PaymentService` requires modification every time a new state is added. The Strategy pattern (see below) makes the state machine open for extension: new states are added by implementing `TransactionStateHandler` and registering a bean — existing handlers are untouched.

---

### SOLID: Open/Closed — `LogRedactor`

`LogRedactor` currently has a hard-coded list of sensitive field names. Adding a new sensitive field means editing the class. Instead, inject the field list as configuration:

```java
@Component
public class LogRedactor {

    private final Set<String> sensitiveFields;

    public LogRedactor(@Value("${logging.redaction.fields:pan,cvv,cvc}") String fields) {
        this.sensitiveFields = Set.of(fields.split(","));
    }

    public String sanitise(Object dto) {
        // reflect over dto fields, redact any whose name is in sensitiveFields
    }
}
```

New sensitive fields are added in `application.yml` under `logging.redaction.fields` — the class is never modified. This satisfies OCP and makes the redaction policy visible in configuration rather than buried in code.

---

### SOLID: Liskov Substitution — `FraudGateway` and `FxGateway`

`GrpcFraudGateway` and `GrpcFxGateway` must honour the same contract as any mock substitute regardless of whether a test or the real gRPC adapter is in use. The contract is: _always return a typed domain result or throw a typed domain exception — never let `StatusRuntimeException` escape_.

```java
// In service/ — callers depend only on these interfaces, never on adapter implementations
interface FraudGateway {
    FraudResult evaluate(FraudRequest request);  // throws FraudServiceUnavailableException
}

interface FxGateway {
    FxRateResult lockRate(FxRateRequest request); // throws FxServiceUnavailableException
}
```

Any implementation that leaks `StatusRuntimeException` to callers violates LSP because the caller then must know which concrete implementation it has. `GrpcFraudGateway` and `GrpcFxGateway` in `service/adapter/` must catch all `StatusRuntimeException` instances internally and re-raise as the typed domain exception. A mock in `FraudCheckingHandlerTest` that never throws `StatusRuntimeException` is then a valid substitute — the test passes, and the contract holds.

---

### SOLID: Interface Segregation — `TransactionRepository`

A single `TransactionRepository` interface will accumulate query methods over time. Controllers need `findById` and `findByMerchantId`; `PaymentService` needs `save`; `OutboxRelayService` never touches `Transaction` rows directly. Split using Spring Data custom repository fragments:

```java
public interface TransactionReadRepository {
    Optional<Transaction> findByIdempotencyKey(String key);
    Page<Transaction> findByMerchantId(String merchantId, Pageable pageable);
}

public interface TransactionWriteRepository {
    Transaction save(Transaction tx);
}

public interface TransactionRepository
        extends JpaRepository<Transaction, UUID>,
                TransactionReadRepository,
                TransactionWriteRepository {}
```

`PaymentController` can be typed against `TransactionReadRepository` — making it impossible to accidentally call a mutation from the controller. `PaymentService` is typed against `TransactionWriteRepository`. Both depend on the narrowest interface they actually need.

---

### SOLID: Interface Segregation — `OutboxRepository`

The outbox relay poller only needs three operations. The full `JpaRepository` surface exposes `deleteAll`, `saveAll`, and other operations that the relay must never call. Define a narrower fragment:

```java
public interface OutboxRelayRepository {
    List<TransactionOutbox> findPendingBatch(int limit);  // @Query + @Lock
    void markSent(UUID id, Instant sentAt);
    void incrementRetry(UUID id);
    void markFailed(UUID id);
}

public interface OutboxRepository
        extends JpaRepository<TransactionOutbox, UUID>,
                OutboxRelayRepository {}
```

`OutboxRelayService` is injected with `OutboxRelayRepository` — the narrowest possible interface. This makes the test trivial: mock only four methods.

---

### SOLID: Dependency Inversion — state handler gRPC dependencies

No class in the service layer may depend on `GrpcFraudGateway` or `GrpcFxGateway` concretely. `FraudCheckingHandler` and `FxLockingHandler` each depend on the `FraudGateway` and `FxGateway` interfaces respectively. `PaymentService` depends only on `SagaOrchestrator` (as shown in the SRP section) — it has no direct dependency on either gateway:

```java
@Component
public class FraudCheckingHandler implements TransactionStateHandler {

    private final FraudGateway fraudGateway; // interface — never GrpcFraudGateway

    public FraudCheckingHandler(FraudGateway fraudGateway) {
        this.fraudGateway = fraudGateway;
    }

    @Override
    public TransactionStatus handles() { return TransactionStatus.FRAUD_CHECKING; }

    @Override
    public Transaction advance(Transaction tx, SagaContext ctx) {
        FraudResult result = fraudGateway.evaluate(FraudRequest.from(tx));
        // ... state transition logic
    }
}
```

`PaymentServiceTest` injects `mock(SagaOrchestrator.class)` — it has no knowledge of either gateway. Handler-level unit tests (`FraudCheckingHandlerTest`) inject `mock(FraudGateway.class)` — no gRPC stub, no `grpc-testing` overhead. The integration test wires `GrpcFraudGateway` via the Spring context.

---

### SOLID: Dependency Inversion — `OutboxRelayService` Kafka dependency

`OutboxRelayService` must not import `KafkaTemplate` directly. It depends on `EventPublisher`:

```java
public interface EventPublisher {
    void publish(String topic, String key, Object payload);
}

@Component
public class KafkaEventPublisher implements EventPublisher {
    private final KafkaTemplate<String, Object> kafka;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(String topic, String key, Object payload) {
        kafka.send(topic, key, payload).get(5, TimeUnit.SECONDS);
    }
}
```

`OutboxRelayServiceTest` injects an in-memory `EventPublisher` that captures published payloads in a list — no `EmbeddedKafkaBroker` needed in the unit test.

---

### Pattern: Strategy — State machine transitions

The state machine currently described as ad-hoc if/switch logic inside `PaymentService` is replaced with a `TransactionStateHandler` strategy interface. Each state owns its own transition logic:

```java
// In service/statehandler/
public interface TransactionStateHandler {
    TransactionStatus handles();
    Transaction advance(Transaction tx, SagaContext ctx);
}

// Example implementation
@Component
public class FraudCheckingHandler implements TransactionStateHandler {

    private final FraudGateway fraudGateway;

    public FraudCheckingHandler(FraudGateway fraudGateway) {
        this.fraudGateway = fraudGateway;
    }

    @Override
    public TransactionStatus handles() { return TransactionStatus.FRAUD_CHECKING; }

    @Override
    public Transaction advance(Transaction tx, SagaContext ctx) {
        FraudResult result = fraudGateway.evaluate(FraudRequest.from(tx));
        return switch (result.verdict()) {
            case APPROVED -> tx.withStatus(TransactionStatus.FRAUD_APPROVED)
                              .withFraudVerdict(result);
            case REJECTED -> tx.withStatus(TransactionStatus.FRAUD_REJECTED)
                              .withFraudVerdict(result);
            case REVIEW   -> tx.withStatus(TransactionStatus.FRAUD_APPROVED)
                              .withFraudVerdict(result); // policy decision — treat REVIEW as pass
        };
    }
}
```

`SagaOrchestrator` holds a `Map<TransactionStatus, TransactionStateHandler>` assembled from all handler beans:

```java
@Service
public class SagaOrchestrator {

    private final Map<TransactionStatus, TransactionStateHandler> handlers;

    public SagaOrchestrator(List<TransactionStateHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        TransactionStateHandler::handles,
                        Function.identity()));
    }

    public Transaction advance(Transaction tx, SagaContext ctx) {
        TransactionStateHandler handler = handlers.get(tx.getStatus());
        if (handler == null) return tx; // terminal state
        Transaction next = handler.advance(tx, ctx);
        return advance(next, ctx); // recurse until terminal
    }
}
```

Adding `REVERSED` handling in the future means adding `ReversedHandler` — zero changes to `SagaOrchestrator` or any existing handler.

---

### Pattern: Chain of Responsibility — Validation pipeline

Bean Validation catches structural violations (`@NotNull`, `@Size`). Business-rule validation (ISO 4217 currency check, amount ceiling, merchant daily limit) cannot be expressed as annotations and does not belong in `PaymentService`. A chain makes each rule independently testable and adds new rules without touching existing ones:

```java
// In service/validation/
public interface PaymentValidationStep {
    void validate(PaymentRequest request, ValidationContext ctx)
            throws PaymentValidationException;
}

@Component
public class CurrencyCodeValidator implements PaymentValidationStep {
    private static final Set<String> ISO_4217 = Set.of(/* ... */);

    @Override
    public void validate(PaymentRequest request, ValidationContext ctx) {
        if (!ISO_4217.contains(request.fromCurrency()))
            throw new PaymentValidationException("Invalid fromCurrency: " + request.fromCurrency());
        if (!ISO_4217.contains(request.toCurrency()))
            throw new PaymentValidationException("Invalid toCurrency: " + request.toCurrency());
    }
}

@Component
public class PaymentValidationChain {
    private final List<PaymentValidationStep> steps;

    public PaymentValidationChain(List<PaymentValidationStep> steps) {
        this.steps = steps; // Spring injects all PaymentValidationStep beans in declaration order
    }

    public void validate(PaymentRequest request) {
        ValidationContext ctx = new ValidationContext();
        for (PaymentValidationStep step : steps) step.validate(request, ctx);
    }
}
```

`PaymentController` calls `validationChain.validate(request)` before handing off to `PaymentService`. Each step is unit-tested in isolation. A new rule (`MerchantLimitValidator`) is added by implementing the interface and annotating with `@Component` — no existing class is modified.

---

### Pattern: Template Method — Outbox relay and webhook relay

`OutboxRelayService` (Kafka publish) and `WebhookRelayService` (HTTP POST, Phase 10) share the same polling skeleton. The invariant part — fetch pending rows, iterate, mark sent or increment retry, enforce failure cutoff — is extracted into `AbstractOutboxRelayService`:

```java
// In service/
public abstract class AbstractOutboxRelayService<T extends TransactionOutbox> {

    @Scheduled(fixedDelay = 500)
    public final void pollAndRelay() {
        List<T> batch = fetchPendingBatch();
        for (T row : batch) {
            try {
                dispatch(row);
                markSent(row);
            } catch (Exception ex) {
                markFailed(row, ex);
            }
        }
    }

    protected abstract List<T> fetchPendingBatch();
    protected abstract void dispatch(T row) throws Exception;
    protected abstract void markSent(T row);
    protected abstract void markFailed(T row, Exception cause);
}

@Service
public class OutboxRelayService extends AbstractOutboxRelayService<TransactionOutbox> {

    private final OutboxRelayRepository outbox;
    private final EventPublisher publisher;

    @Override
    protected List<TransactionOutbox> fetchPendingBatch() {
        return outbox.findPendingBatch(50);
    }

    @Override
    protected void dispatch(TransactionOutbox row) throws Exception {
        publisher.publish("payment.submitted", row.getPartitionKey(), row.getPayload());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markSent(TransactionOutbox row) {
        outbox.markSent(row.getId(), Instant.now());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void markFailed(TransactionOutbox row, Exception cause) {
        outbox.incrementRetry(row.getId());
        if (row.getRetryCount() + 1 >= 5) outbox.markFailed(row.getId());
    }
}
```

`WebhookRelayService` extends the same abstract class and overrides only `dispatch` (HTTP POST via `RestClient`). The scheduling and retry accounting are never duplicated.

---

### Pattern: Factory — Outbox row construction

`TransactionOutboxFactory` centralises all `TransactionOutbox` construction. Without it, JSON serialisation and field mapping are scattered wherever a row is created (`PaymentService`, `PaymentResultHandler`, eventually `WebhookRelayService`):

```java
// In factory/
@Component
public class TransactionOutboxFactory {

    private final ObjectMapper mapper;

    public TransactionOutboxFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public TransactionOutbox paymentSubmitted(Transaction tx, PaymentSubmittedEvent event) {
        return TransactionOutbox.builder()
                .id(UUID.randomUUID())
                .transactionId(tx.getId())
                .eventType("payment.submitted")
                .payload(serialise(event))
                .partitionKey(tx.getId().toString())
                .status(OutboxStatus.PENDING)
                .build();
    }

    public TransactionOutbox webhookDispatch(Transaction tx, String callbackUrl, Object payload) {
        return TransactionOutbox.builder()
                .id(UUID.randomUUID())
                .transactionId(tx.getId())
                .eventType("webhook.dispatch")
                .payload(serialise(Map.of("callbackUrl", callbackUrl, "body", payload)))
                .partitionKey(tx.getId().toString())
                .status(OutboxStatus.PENDING)
                .build();
    }

    private String serialise(Object obj) {
        try { return mapper.writeValueAsString(obj); }
        catch (JsonProcessingException ex) { throw new IllegalArgumentException("Outbox serialisation failed", ex); }
    }
}
```

`PaymentService` receives `TransactionOutboxFactory` via constructor injection and calls `factory.paymentSubmitted(tx, event)` — it never touches `ObjectMapper`. The factory is testable independently: verify that the correct `event_type`, `partition_key`, and payload shape are produced for each event type.

---

### Pattern: Adapter — gRPC boundary isolation

Proto-generated types (`FraudServiceGrpc.FraudServiceBlockingStub`, proto request/response messages) must not appear anywhere in `service/` except inside the adapter implementations. `PaymentService` and `SagaOrchestrator` work exclusively with domain value objects.

```java
// In service/ — domain interfaces (DIP, LSP)
public interface FraudGateway {
    FraudResult evaluate(FraudRequest request); // throws FraudServiceUnavailableException
}

// In service/adapter/ — gRPC adapter; proto types never leave this class
public class GrpcFraudGateway implements FraudGateway {

    private final FraudServiceGrpc.FraudServiceBlockingStub stub;

    public GrpcFraudGateway(FraudServiceGrpc.FraudServiceBlockingStub stub) {
        this.stub = stub;
    }

    @Override
    @CircuitBreaker(name = "fraud-service", fallbackMethod = "fallback")
    @Retry(name = "fraud-service")
    public FraudResult evaluate(FraudRequest request) {
        try {
            var protoReq = FraudCheckRequest.newBuilder()
                    .setTransactionId(request.transactionId().toString())
                    .setAmount(request.amount().toPlainString())
                    .setMerchantId(request.merchantId())
                    .setCountry(request.country())
                    .build();
            var protoResp = stub.withDeadlineAfter(3, TimeUnit.SECONDS)
                                .checkFraud(protoReq);
            return new FraudResult(
                    FraudVerdict.valueOf(protoResp.getVerdict().name()),
                    protoResp.getReason());
        } catch (StatusRuntimeException ex) {
            throw new FraudServiceUnavailableException("gRPC call failed: " + ex.getStatus(), ex);
        }
    }

    private FraudResult fallback(FraudRequest request, Exception ex) {
        throw new FraudServiceUnavailableException("Circuit open", ex);
    }
}
```

Apply the same pattern for `GrpcFxGateway implements FxGateway`. Domain value objects (`FraudRequest`, `FraudResult`, `FraudVerdict`, `FxRateRequest`, `FxRateResult`) live in `domain/`. Both adapter classes (`GrpcFraudGateway`, `GrpcFxGateway`) live in `service/adapter/`. The proto module is imported only by these two adapter classes — nowhere else in the service layer.

---

### Pattern: Observer / Event — Audit log decoupling

Each `TransactionStateHandler` publishes `TransactionStatusChangedEvent` via `ApplicationEventPublisher` after setting the new status on the transaction. This removes the direct dependency on `AuditLogRepository` from every handler and from `SagaOrchestrator`:

```java
// Published inside each TransactionStateHandler after every status transition:
eventPublisher.publishEvent(
    new TransactionStatusChangedEvent(tx.getId(), oldStatus, tx.getStatus(), actorId));
```

```java
// In logging/ — separate listener
@Component
public class AuditLogListener {

    private final AuditLogRepository auditLogs;

    public AuditLogListener(AuditLogRepository auditLogs) {
        this.auditLogs = auditLogs;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void on(TransactionStatusChangedEvent event) {
        auditLogs.save(AuditLog.builder()
                .transactionId(event.transactionId())
                .event("STATUS_CHANGED")
                .oldValue(event.oldStatus().name())
                .newValue(event.newStatus().name())
                .actor(event.actorId())
                .build());
    }
}
```

**`BEFORE_COMMIT` vs `AFTER_COMMIT`:** Use `BEFORE_COMMIT` here. The audit log write must be in the same database transaction as the state change and outbox row write. If a crash occurs after the outer transaction commits but before `AFTER_COMMIT` listeners fire, the audit entry is lost permanently — a hard invariant violation for a payment audit trail. `BEFORE_COMMIT` places the audit write inside the same transaction boundary, so it either all commits or all rolls back together.

A future listener (e.g., a Micrometer counter increment) that does not need DB durability can safely use `AFTER_COMMIT`.

---

### Pattern: Decorator — Resilience4j wrapping

Annotation-based Resilience4j is used in place of the programmatic `CircuitBreaker.decorateCheckedSupplier` approach. Annotations remove boilerplate from `GrpcFraudGateway` and `GrpcFxGateway` and integrate automatically with Micrometer metrics via `resilience4j-micrometer`. Both adapter classes live in `service/adapter/`.

```java
@CircuitBreaker(name = "fraud-service", fallbackMethod = "fallback")
@Retry(name = "fraud-service")
public FraudResult evaluate(FraudRequest request) {
    // gRPC call only — no manual decoration boilerplate
}

private FraudResult fallback(FraudRequest request, Exception ex) {
    throw new FraudServiceUnavailableException("Circuit open", ex);
}
```

The `fallback` method must accept the same parameter types as the annotated method plus a trailing `Exception` parameter — Resilience4j requires this signature for fallback resolution.

`GrpcClientConfig` becomes minimal — it only registers the `@GrpcClient`-injected stubs as beans and provides the adapter implementations. No manual `CircuitBreaker.decorateCheckedSupplier` chains.

**Trade-off acknowledged:** annotation-based Resilience4j requires Spring AOP proxying, so calls from within the same bean bypass the circuit breaker. `GrpcFraudGateway.evaluate` is only ever called from `FraudCheckingHandler` (a different bean), and `GrpcFxGateway.lockRate` from `FxLockingHandler` (also a different bean) — so self-invocation is not an issue here. If self-invocation were needed, inject `self` or use the programmatic API — but that situation does not arise in this design.

---

## Phase 3 — Domain model and Flyway migrations

### State machine

```
RECEIVED
  └─► FRAUD_CHECKING
        ├─► FRAUD_REJECTED  (terminal)
        └─► FRAUD_APPROVED
              └─► FX_LOCKED          (rate locked; null if same currency — saga skips FX call)
                    └─► SUBMITTED_TO_BANK
                          ├─► SUCCEEDED   (terminal)
                          ├─► FAILED      (terminal)
                          └─► REVERSED    (terminal — compensation)
```

`FRAUD_CHECKING` and `FX_LOCKED` are in-progress states used when calls are made. `ROUTING` is not a visible state — routing rule selection (looking up `RoutingRule` by `cardNetwork`/`country`/`fromCurrency` and setting `bank_connector_id`) is an internal synchronous step inside the saga, not an observable state transition. The saga calls fraud and fx synchronously within the REST request thread before writing the outbox row (see Phase 9 for justification). If either call fails, the transaction moves to `FRAUD_REJECTED` or a `FAILED` terminal state without emitting to the bank.

### V1__create_transactions.sql

**Table: `transactions`**

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | Application-generated (`UUID.randomUUID()`) |
| `idempotency_key` | `varchar(255)` UNIQUE NOT NULL | From `Idempotency-Key` header forwarded by gateway |
| `merchant_id` | `varchar(255)` NOT NULL | From JWT `sub` claim |
| `amount` | `numeric(19,4)` NOT NULL | |
| `from_currency` | `char(3)` NOT NULL | ISO 4217 |
| `to_currency` | `char(3)` NOT NULL | ISO 4217 |
| `locked_rate` | `numeric(19,8)` | Populated after FX lock; null if same currency |
| `card_network` | `varchar(50)` | VISA, MASTERCARD, etc. |
| `country` | `char(2)` | ISO 3166-1 alpha-2 |
| `status` | `varchar(50)` NOT NULL | Enum string; default `RECEIVED` |
| `fraud_verdict` | `varchar(20)` | APPROVED / REVIEW / REJECTED |
| `fraud_reason` | `text` | From gRPC response |
| `bank_reference` | `varchar(255)` | Returned from bank-connector |
| `created_at` | `timestamptz` NOT NULL DEFAULT now() | |
| `updated_at` | `timestamptz` NOT NULL DEFAULT now() | |
| `version` | `bigint` NOT NULL DEFAULT 0 | Optimistic lock (`@Version`) |

Indexes:
- UNIQUE on `idempotency_key` — DB-level deduplication (the real guard after gateway Redis)
- Index on `(merchant_id, created_at DESC)` — powers the merchant list query
- Index on `status` WHERE `status NOT IN ('SUCCEEDED','FAILED','REVERSED')` — partial index for outbox poller and reconciliation scans

### V2__create_transaction_outbox.sql

**Table: `transaction_outbox`**

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `transaction_id` | `uuid` NOT NULL REFERENCES transactions(id) | |
| `event_type` | `varchar(100)` NOT NULL | e.g. `payment.submitted` |
| `payload` | `jsonb` NOT NULL | Serialized event JSON |
| `partition_key` | `varchar(255)` NOT NULL | `transactionId.toString()` — used as Kafka message key |
| `status` | `varchar(20)` NOT NULL DEFAULT `'PENDING'` | PENDING / SENT / FAILED |
| `created_at` | `timestamptz` NOT NULL DEFAULT now() | |
| `sent_at` | `timestamptz` | |
| `retry_count` | `smallint` NOT NULL DEFAULT 0 | |

Indexes:
- Index on `(status, created_at)` WHERE `status = 'PENDING'` — outbox poller query

### V3__create_audit_log.sql

**Table: `audit_log`**

| Column | Type | Notes |
|---|---|---|
| `id` | `bigserial` PK | |
| `transaction_id` | `uuid` NOT NULL | Not FK — audit log must survive transaction deletion |
| `event` | `varchar(100)` NOT NULL | e.g. `STATUS_CHANGED`, `FRAUD_VERDICT_RECEIVED` |
| `old_value` | `text` | |
| `new_value` | `text` | |
| `actor` | `varchar(255)` | merchant_id or `system` |
| `occurred_at` | `timestamptz` NOT NULL DEFAULT now() | |

Index on `transaction_id`.

### V4__create_routing_rules.sql

**Table: `routing_rules`**

| Column | Type | Notes |
|---|---|---|
| `id` | `bigserial` PK | |
| `card_network` | `varchar(50)` | null = wildcard |
| `country` | `char(2)` | null = wildcard |
| `from_currency` | `char(3)` | null = wildcard |
| `bank_connector_id` | `varchar(100)` NOT NULL | Identifies the bank-connector route |
| `priority` | `int` NOT NULL DEFAULT 0 | Higher wins |
| `active` | `boolean` NOT NULL DEFAULT true | |

Seed a default catch-all row in the same migration.

---

## Phase 4 — REST API

### Endpoints

| Method | Path | Auth | Role | Description |
|---|---|---|---|---|
| `POST` | `/api/v1/payments` | JWT required | `MERCHANT` | Submit a payment; requires `Idempotency-Key` header |
| `GET` | `/api/v1/payments/{id}` | JWT required | `MERCHANT` | Fetch single payment (merchant sees only their own) |
| `GET` | `/api/v1/payments` | JWT required | `MERCHANT` | Paginated list, filtered by merchant_id from JWT; accepts `?page=0&size=20&sort=createdAt,desc` (default page 0, size 20, sorted by `createdAt` descending); repository method: `findByMerchantId(String merchantId, Pageable pageable)` |
| `GET` | `/api/v1/payments/{id}/audit` | JWT required | `ADMIN` | Audit trail for a transaction |
| `GET` | `/actuator/health` | Open | — | Kubernetes probes |

### PaymentRequest record

```
transactionId   UUID     (optional — if absent, server generates; allows client to supply its own)
amount          BigDecimal  @NotNull @Positive
fromCurrency    String      @NotBlank @Size(min=3,max=3)
toCurrency      String      @NotBlank @Size(min=3,max=3)
cardNetwork     String      @NotBlank
country         String      @NotBlank @Size(min=2,max=2)
idempotencyKey  (from header, not body)
```

### PaymentResponse record

```
id              UUID
merchantId      String
amount          BigDecimal
fromCurrency    String
toCurrency      String
lockedRate      BigDecimal   (null if same currency)
status          String
fraudVerdict    String
createdAt       Instant
updatedAt       Instant
```

### Idempotency semantics

The gateway has already checked the `Idempotency-Key` in Redis and returned a cached response if the key was seen before. Transaction-service provides a second, durable layer: the `idempotency_key` column has a UNIQUE constraint. On a duplicate `POST` that slips through (e.g., Redis TTL expired, gateway restart), the service catches `DataIntegrityViolationException` on the unique constraint, looks up the existing transaction by `idempotency_key`, and returns `200 OK` with the existing record — not `409`. This is the correct payment API semantics: idempotency means "same result," not "error on repeat."

**PK collision edge case:** `PaymentRequest` includes an optional client-supplied `transactionId`. If a client supplies a UUID that collides with an existing record's primary key (not via the idempotency key), the `DataIntegrityViolationException` handler in `PaymentControllerAdvice` will look up by `idempotency_key` and find nothing, resulting in a 500 error. The recommended fix: `PaymentControllerAdvice` must distinguish between a PK violation and a unique constraint violation on `idempotency_key` by inspecting the constraint name in the exception. Alternatively — and simpler — the server should always generate `transactionId` internally and the client-supplied field should be removed from `PaymentRequest`.

### Error model

Use Spring's built-in `ProblemDetail` (RFC 9457). `PaymentControllerAdvice` maps:
- `ConstraintViolationException` → `400`
- `AccessDeniedException` → `403`
- `TransactionNotFoundException` → `404`
- `FraudRejectedException` → `422` with `fraudReason` extension field
- `DataIntegrityViolationException` (duplicate idempotency_key) → look up + `200`
- All unhandled → `500` with a safe, non-leaking message

---

## Phase 5 — Security config

`SecurityConfig` is a standard servlet-stack `@Configuration` (not `@EnableWebFluxSecurity`).

```yaml
# Drives config:
spring.security.oauth2.resourceserver.jwt.issuer-uri: ${KEYCLOAK_ISSUER_URI}
```

The `issuer-uri` causes Spring to auto-fetch JWKS from `<issuer>/.well-known/openid-configuration` at startup. Offline validation on every request — no per-request Keycloak round-trip.

**Role mapping:** Implement a `JwtAuthenticationConverter` (non-reactive, servlet-stack) that reads `realm_access.roles`, prefixes `ROLE_`, and returns a `Collection<GrantedAuthority>`. Wire it via `jwtAuthenticationConverter.setJwtGrantedAuthoritiesConverter(...)` and register on the `HttpSecurity` resource server config. Place in `config/SecurityConfig.java`.

Do **not** use `ReactiveJwtAuthenticationConverter` — that is WebFlux-only and will fail on a servlet stack. The api-gateway uses `ReactiveJwtAuthenticationConverter` because it is reactive; transaction-service uses `spring-boot-starter-web` and must use the non-reactive equivalent.

**Method security:** Use `@EnableMethodSecurity` and `@PreAuthorize("hasRole('MERCHANT')")` on controller methods rather than blanket `authorizeHttpRequests` path patterns — this is more maintainable as endpoints grow. Actuator paths remain open at the `HttpSecurity` level.

**Security rule:** `merchant_id` in service layer must always be sourced from the JWT (`SecurityContextHolder`) — never trusted from the request body. A merchant must not be able to query another merchant's transactions.

---

## Phase 6 — gRPC clients

### Dependency on proto module

In `build.gradle`, the proto module is a project dependency:
```groovy
implementation project(':proto')
```
No protobuf code generation is needed in transaction-service itself — the stubs are compiled in the proto module and consumed as a library.

### Client configuration

`net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE` provides `@GrpcClient`. Configure channel addresses from env vars in `application.yml`:

```yaml
grpc:
  client:
    fraud-service:
      address: "static://${FRAUD_SERVICE_GRPC_HOST:localhost}:${FRAUD_SERVICE_GRPC_PORT:9090}"
      negotiation-type: plaintext
    fx-service:
      address: "static://${FX_SERVICE_GRPC_HOST:localhost}:${FX_SERVICE_GRPC_PORT:9090}"
      negotiation-type: plaintext
```

`GrpcFraudGateway` and `GrpcFxGateway` in `service/adapter/` inject `@GrpcClient("fraud-service") FraudServiceGrpc.FraudServiceBlockingStub fraudStub` (and similarly for fx). The stubs are injected by `net.devh:grpc-client-spring-boot-starter` via the channel configuration above.

### Resilience4j policy

Both adapters make I/O calls to external services — must be wrapped with circuit breaker and retry.

Use annotation-based Resilience4j (see Phase 2.1 Pattern: Decorator). `GrpcClientConfig` does **not** define manual `CircuitBreaker.decorateCheckedSupplier` chains — circuit breaker and retry behaviour is declared via `@CircuitBreaker` + `@Retry` annotations on `GrpcFraudGateway.evaluate` and `GrpcFxGateway.lockRate`. The circuit breaker and retry instances are configured in `application.yml` under `resilience4j.circuitbreaker.instances` and `resilience4j.retry.instances`:

| Setting | Value | Rationale |
|---|---|---|
| `slidingWindowSize` | 20 | |
| `failureRateThreshold` | 50 | Open after 50% failures |
| `waitDurationInOpenState` | 10s | |
| `permittedCallsInHalfOpenState` | 3 | |
| Retry `maxAttempts` | 2 | 1 retry; gRPC is idempotent here |
| Retry `waitDuration` | 200ms | |

Do **not** use Resilience4j `TimeLimiter` for the gRPC timeout. `TimeLimiter` wraps calls in a `Future` and is designed for `CompletableFuture`-based async operations. For blocking gRPC stubs, apply the deadline directly on the stub: `stub.withDeadlineAfter(3, TimeUnit.SECONDS)`. This uses gRPC's own deadline propagation mechanism and is the correct approach for blocking stubs. Apply this in `GrpcFraudGateway.evaluate()` and `GrpcFxGateway.lockRate()`.

Throw a `FraudServiceUnavailableException` from the `GrpcFraudGateway` fallback method when the circuit is open — the saga catches this and transitions the transaction to `FAILED` with a recorded reason. Same pattern for `GrpcFxGateway.lockRate()` with its own circuit breaker instance.

---

## Phase 7 — Kafka

### Topics

| Topic | Partitions | Producer | Consumer |
|---|---|---|---|
| `payment.submitted` | 3 | transaction-service (via outbox relay) | bank-connector |
| `payment.result` | 3 | bank-connector | transaction-service |
| `payment.retry` | 3 | DefaultErrorHandler | transaction-service |
| `payment.dlq` | 1 | DeadLetterPublishingRecoverer | — (manual inspection) |

Partition by `transactionId` (string) on all topics — consistent ordering per transaction across the pipeline.

Partition count is 3 to match the existing `kafka.yaml` init Job in the cluster. If 6 partitions are preferred, update the Kafka init Job to create topics at 6 partitions before deploying — the init Job and this plan must stay in sync.

### Producer config (KafkaProducerConfig)

```yaml
spring:
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        retries: 2147483647
```

`JsonSerializer` with `SPRING_JSON_ADD_TYPE_HEADERS: false` — no type headers on the wire (loose coupling; consumers can be in any language).

### Consumer config (KafkaConsumerConfig)

```yaml
spring:
  kafka:
    consumer:
      group-id: transaction-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "hp.microservice.demo.transaction_service.event"
        spring.json.value.default.type: "hp.microservice.demo.transaction_service.event.PaymentResultEvent"
        spring.json.use.type.headers: false
```

`spring.json.use.type.headers: false` is required because the producer sets `spring.json.add.type.headers: false` (no type headers on the wire). Without this, the consumer falls back to fragile header-based type resolution and may fail or silently deserialize to the wrong type.

`DefaultErrorHandler` in `KafkaConsumerConfig` bean:

- 3 attempts with exponential backoff (1s, 2s, 4s)
- `DeadLetterPublishingRecoverer` → `payment.dlq` topic after exhaustion
- Also configure a `RetryTopicConfiguration` bean for `payment.retry` if retry-topic pattern is preferred over local retries — the `@RetryableTopic` approach is cleaner but adds infrastructure topic; start with `DefaultErrorHandler` local retries, defer retry topic to a follow-on phase.

Manual offset commit (`AckMode.MANUAL_IMMEDIATE`). `PaymentResultHandler` calls `acknowledgment.acknowledge()` only after the DB state transition is committed. `AckMode.MANUAL_IMMEDIATE` requires the `@KafkaListener` method signature to include `Acknowledgment` as a parameter:

```java
public void handle(PaymentResultEvent event, Acknowledgment acknowledgment)
```

Omitting the `Acknowledgment` parameter causes the container to use automatic offset commit regardless of the configured `AckMode`.

### Event POJOs (event/)

```
PaymentSubmittedEvent record:
  transactionId, merchantId, amount, fromCurrency, toCurrency,
  lockedRate, cardNetwork, country, routingTarget, occurredAt

PaymentResultEvent record:
  transactionId, status (SUCCEEDED|FAILED|REVERSED),
  bankReference, failureReason, occurredAt
```

No Jackson `@JsonTypeInfo` — type resolution is explicit via `value.default.type` consumer property.

---

## Phase 8 — Outbox pattern

### Design

The outbox table (`transaction_outbox`) is written **in the same local DB transaction** as the state change. The `@Transactional` boundary in `PaymentService` covers both the `transactions` insert/update and the `transaction_outbox` insert. Kafka is not touched inside the transaction.

`OutboxRelayService` extends `AbstractOutboxRelayService<TransactionOutbox>` (see Phase 2.1 Pattern: Template Method). The abstract base class owns the scheduling, iteration, and retry accounting; `OutboxRelayService` overrides only `fetchPendingBatch`, `dispatch`, `markSent`, and `markFailed`. The `@Scheduled(fixedDelay = 500)` annotation lives on `AbstractOutboxRelayService.pollAndRelay()` which is `final` — subclasses cannot change the polling contract.

The relay execution sequence:

1. Fetch up to 50 rows WHERE `status = 'PENDING'` ORDER BY `created_at` (FIFO per transaction, via index).
2. For each row: send to Kafka with `transactionId` as the message key (guarantees partition ordering).
3. On successful send (synchronous `KafkaTemplate.send(...).get()` with a 5s timeout): update row to `SENT`, set `sent_at = now()`.
4. On failure: increment `retry_count`; if `retry_count >= 5`, set `status = 'FAILED'` (manual intervention needed; reconciliation-service will flag it).
5. Steps 3/4 run in a new `@Transactional(REQUIRES_NEW)` per row so one failed row does not roll back others.

Use `@Scheduled(fixedDelay = 500)` — not `fixedRate`. With `fixedRate`, if a poll iteration takes longer than 500ms (e.g., a slow Kafka send), the next execution starts immediately after the previous finishes, causing overlapping executions. `fixedDelay` ensures the 500ms gap is always measured from completion, not from start.

The polling interval of 500ms is acceptable for a learning cluster. In production, transactional outbox polling is typically replaced by Debezium CDC, but that is explicitly deferred.

### Why outbox rather than direct Kafka publish in the saga

Publishing directly from within the JPA transaction is the classic "dual write" problem — if Kafka publish succeeds but the DB commit fails (or vice versa), the system is inconsistent. The outbox pattern makes the Kafka publish a consequence of a committed DB row, eliminating this race. The 500ms polling latency is the trade-off.

---

## Phase 9 — Saga orchestration

### Design decision: synchronous fraud + fx, then outbox

Fraud and FX are synchronous gRPC calls made **inside the REST request thread** before anything is written to the DB or Kafka. Rationale: the merchant waits for a synchronous response and needs an immediate answer on whether the payment was fraud-rejected or had an FX problem. Deferring these to async Kafka steps would require the merchant to poll for a result, which is more complex and not warranted here.

The saga is implemented via `SagaOrchestrator` which dispatches each state transition to the appropriate `TransactionStateHandler` (see Phase 2.1 Pattern: Strategy). `PaymentService` calls `sagaOrchestrator.advance(tx, SagaContext.initial())` — all state-machine logic is encapsulated in the handler map. `FraudCheckingHandler` calls `FraudGateway.evaluate`; `FxLockingHandler` calls `FxGateway.lockRate`. `GrpcFraudGateway` and `GrpcFxGateway` in `service/adapter/` are the concrete implementations — `PaymentService` and `SagaOrchestrator` never reference them.

### Sequence (happy path — cross-currency)

```
Client          PaymentController       PaymentService    SagaOrchestrator  GrpcFraudGateway  GrpcFxGateway  DB (JPA tx)  OutboxRelay  Kafka
  │                    │                      │                  │                  │               │             │             │          │
  │─POST /payments──►  │                      │                  │                  │               │             │             │          │
  │  Idempotency-Key   │                      │                  │                  │               │             │             │          │
  │                    │─submit(request,jwt)──►│                  │                  │               │             │             │          │
  │                    │                      │─persist RECEIVED───────────────────────────────────────────────►│             │          │
  │                    │                      │  + audit_log(RECEIVED)─────────────────────────────────────────►│             │          │
  │                    │                      │  (single tx commit)──────────────────────────────────────────── commit        │          │
  │                    │                      │                  │                  │               │             │             │          │
  │                    │                      │─advance(tx)──────►│                  │               │             │             │          │
  │                    │                      │                  │─evaluate(...)────►│               │             │             │          │
  │                    │                      │                  │◄─APPROVED─────────│               │             │             │          │
  │                    │                      │                  │─update FRAUD_APPROVED──────────────────────────────────────►│             │          │
  │                    │                      │                  │                  │               │             │             │          │
  │                    │                      │                  │─lockRate(...)──────────────────►│             │             │          │
  │                    │                      │                  │◄─lockedRate──────────────────────│             │             │          │
  │                    │                      │                  │─update FX_LOCKED + lockedRate──────────────────────────────►│             │          │
  │                    │                      │◄─tx (FX_LOCKED)──│  + outbox(payment.submitted, PENDING)──────────────────── commit        │          │
  │                    │                      │                  │                  │               │             │             │          │
  │◄─202 Accepted──────│◄─PaymentResponse─────│                  │                  │               │             │             │          │
  │  {id, status=FX_LOCKED, lockedRate}        │                  │                  │               │             │             │          │
  │                    │                      │                  │                  │               │             │   (500ms)    │          │
  │                    │                      │                  │                  │               │             │──poll PENDING►          │
  │                    │                      │                  │                  │               │             │             │─send key=txId►
  │                    │                      │                  │                  │               │             │             │◄─ack       │
  │                    │                      │                  │                  │               │             │◄─mark SENT──│          │
```

One outbox write per transaction, written after fraud and FX have succeeded. The `RECEIVED` state transition is recorded in `audit_log` directly — no outbox row for it. If fraud rejects, no outbox event is written and bank-connector never sees the payment.

### Compensation paths

| Failure point | Action | Terminal state |
|---|---|---|
| Fraud gRPC `REJECTED` verdict | Record verdict, no outbox write | `FRAUD_REJECTED` |
| Fraud gRPC circuit open / timeout | Write `FAILED` + reason `FRAUD_SERVICE_UNAVAILABLE`; no outbox write | `FAILED` |
| FX gRPC circuit open / timeout | Write `FAILED` + reason `FX_SERVICE_UNAVAILABLE`; no outbox write | `FAILED` |
| `payment.result` → `FAILED` from bank | Update status; write `audit_log` entry | `FAILED` |
| `payment.result` → `REVERSED` from bank | Update status; write audit; (webhook outbox row — deferred) | `REVERSED` |
| Outbox row `retry_count >= 5` | Leave in `FAILED` outbox status; transaction stays `FX_LOCKED` (stuck) | Reconciliation-service picks it up |

### PaymentResultHandler (@KafkaListener on payment.result)

1. Deserialize `PaymentResultEvent`.
2. Look up `Transaction` by `transactionId`. If not found, log and ack (idempotent — may have already been processed).
3. Validate state transition is legal (e.g. `SUBMITTED_TO_BANK → SUCCEEDED` is valid; `SUCCEEDED → FAILED` is not).
4. Update `status`, set `bankReference`.
5. Write `audit_log` entry.
6. (Deferred) Write webhook dispatch outbox row.
7. Commit DB transaction, then `acknowledgment.acknowledge()`.

---

## Phase 10 — Webhooks (deferred sketch)

Merchant webhooks are out of scope for the initial build but the architecture is sketched here so the domain model accommodates it without rework.

When a `payment.result` arrives and the transaction reaches a terminal state, `PaymentResultHandler` writes a row to `transaction_outbox` with `event_type = webhook.dispatch` and a payload containing the merchant's callback URL (from `routing_rules` or a future `merchant_profiles` table) and the event body.

A `WebhookRelayService` (scheduled, similar to `OutboxRelayService`) reads these rows, makes an HTTP POST to the callback URL via `RestClient` or `WebClient`, and marks the row `SENT`. Retry with exponential backoff up to configurable attempts. After exhaustion, mark `FAILED` and alert (log + metric).

Defer implementation until bank-connector is built and end-to-end flow is testable.

---

## Phase 11 — application.yml

Delete `application.properties`. Create `src/main/resources/application.yml`:

```yaml
server:
  port: 8080

spring:
  application:
    name: transaction-service

  threads:
    virtual:
      enabled: true

  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/paymentdb}
    username: ${SPRING_DATASOURCE_USERNAME:postgres}
    password: ${SPRING_DATASOURCE_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
      connection-timeout: 20000

  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        default_schema: public

  flyway:
    enabled: true
    locations: classpath:db/migration
    baseline-on-migrate: false

  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        retries: 2147483647
        spring.json.add.type.headers: false
    consumer:
      group-id: transaction-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      enable-auto-commit: false
      properties:
        spring.json.trusted.packages: "hp.microservice.demo.transaction_service.event"
        spring.json.value.default.type: "hp.microservice.demo.transaction_service.event.PaymentResultEvent"
        spring.json.use.type.headers: false

  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://keycloak.localhost:8090/realms/payments}

grpc:
  client:
    fraud-service:
      address: "static://${FRAUD_SERVICE_GRPC_HOST:localhost}:${FRAUD_SERVICE_GRPC_PORT:9090}"
      negotiation-type: plaintext
    fx-service:
      address: "static://${FX_SERVICE_GRPC_HOST:localhost}:${FX_SERVICE_GRPC_PORT:9090}"
      negotiation-type: plaintext

resilience4j:
  circuitbreaker:
    instances:
      fraud-service:
        slidingWindowSize: 20
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedCallsInHalfOpenState: 3
      fx-service:
        slidingWindowSize: 20
        failureRateThreshold: 50
        waitDurationInOpenState: 10s
        permittedCallsInHalfOpenState: 3
  retry:
    instances:
      fraud-service:
        maxAttempts: 2
        waitDuration: 200ms
      fx-service:
        maxAttempts: 2
        waitDuration: 200ms

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true

logging:
  config: classpath:logback-spring.xml
```

**Note on `SPRING_DATASOURCE_USERNAME`:** The `deployment.yaml` maps `SPRING_DATASOURCE_PASSWORD` from the secret but not `SPRING_DATASOURCE_USERNAME`. The default value `postgres` above matches the cluster Postgres setup. For explicitness, add `SPRING_DATASOURCE_USERNAME` to the `transaction-service-config` ConfigMap so the value is visible in the manifest rather than being an invisible default.

---

## Phase 12 — Logging

`src/main/resources/logback-spring.xml` — copy api-gateway's structure exactly, change the `customFields` value:

```xml
<customFields>{"service":"transaction-service"}</customFields>
```

**MDC propagation strategy:**

`PaymentController` extracts `transactionId` from the response after service call and adds it to MDC. For inbound requests, extract `X-Request-Id` from the header (set by api-gateway) and put it in MDC as `traceId`. Wrap in a try-finally to clear MDC after the request.

A `OncePerRequestFilter` (placed in `config/`) handles this systematically:
- On entry: `MDC.put("traceId", request.getHeader("X-Request-Id"))` (or generate UUID if absent)
- `MDC.put("merchantId", extractMerchantId(SecurityContextHolder))`
- On exit: `MDC.clear()`

`transactionId` is added to MDC inside `PaymentService` once the transaction entity is created.

For Kafka listeners: `PaymentResultHandler` extracts `transactionId` from the `PaymentResultEvent` and puts it in MDC at the start of processing, clears at the end.

gRPC calls: pass `traceId` as gRPC metadata header `x-trace-id` via a `ClientInterceptor` registered on both stubs.

---

## Phase 12.1 — Request/response logging via AOP

### Package placement

Place all logging aspects and the `LogRedactor` utility in a dedicated `logging/` sub-package rather than `config/`. `config/` is reserved for Spring bean wiring; these classes carry domain-aware redaction logic that is tested independently and is not Spring-infrastructure. `logging/` makes that boundary explicit.

```
logging/
    RestLoggingAspect          # @Aspect — REST controller entry/exit
    GrpcClientLoggingAspect    # @Aspect — FraudCheckService / FxService entry/exit
    LogRedactor                # Utility — centralised field-masking policy
```

### REST payload logging — `RestLoggingAspect`

```java
@Around("within(@org.springframework.web.bind.annotation.RestController *) && within(hp.microservice.demo.transaction_service.web..*)")
public Object logRestCall(ProceedingJoinPoint pjp) throws Throwable { ... }
```

On entry (INFO): method signature, sanitised request DTO via `LogRedactor`.
On exit (INFO): sanitised response DTO, `latencyMs`.
On exception (WARN): exception class + message, `latencyMs`.

Because `MDC` already carries `traceId`, `merchantId`, and (after `PaymentService` writes the entity) `transactionId`, every log line emitted inside the aspect is automatically correlatable — no extra fields need to be injected into the log statement itself.

Full payload bodies are gated at DEBUG level:

```yaml
logging:
  payload:
    enabled: false    # set true in local dev; never enable in prod
```

In code, check `log.isDebugEnabled()` before serializing the DTO to a string — payload serialization is not free even with virtual threads.

### gRPC client payload logging — `GrpcClientLoggingAspect`

```java
@Around("execution(public * hp.microservice.demo.transaction_service.service.adapter.GrpcFraudGateway.*(..)) || execution(public * hp.microservice.demo.transaction_service.service.adapter.GrpcFxGateway.*(..))")
public Object logGrpcCall(ProceedingJoinPoint pjp) throws Throwable { ... }
```

Logs the domain request object (INFO on entry), domain result (INFO on exit), `latencyMs`, and the Resilience4j outcome — distinguish `CallNotPermittedException` (circuit open) from a real gRPC `StatusRuntimeException` so ops can tell "circuit open" from "service actually failed". The aspect targets `GrpcFraudGateway` and `GrpcFxGateway` in `service/adapter/` — these are the only classes that see both the domain value objects and the gRPC call outcome, making them the correct interception point for redaction-aware logging.

**Alternative — gRPC `ClientInterceptor`:** a `ClientInterceptor` can intercept at the transport layer and log raw headers + message bytes. Prefer AOP here because: (a) the domain-level service objects are already the right granularity for redaction; (b) the Resilience4j outcome is visible at the service method level, not at the transport level; (c) the `ClientInterceptor` approach would require proto `toString()` or custom marshalling to produce readable log output. The trade-off is that AOP misses low-level transport errors (e.g. connection refused before the stub is called) — those surface as exceptions caught by the aspect's catch block, so nothing is silently lost.

### Kafka payload logging

**Outbound (`payment.submitted`):** implement `ProducerInterceptor<String, PaymentSubmittedEvent>` and register it via `spring.kafka.producer.properties.interceptor.classes`. The interceptor receives the fully serialized `ProducerRecord` before send — log topic, partition key, and (at DEBUG) the JSON payload via `LogRedactor`.

**Inbound (`payment.result`):** implement a Spring Kafka `RecordInterceptor<String, PaymentResultEvent>` and register it on the `ConcurrentKafkaListenerContainerFactory`. Logs topic, partition, offset, and (at DEBUG) payload on entry; logs outcome on exit.

Lighter alternative: a `log.info` at the top of `PaymentResultHandler.handle(...)` is perfectly readable and already has MDC context. Prefer the `RecordInterceptor` approach for symmetry with the producer side — both Kafka boundaries are logged at the same layer — but if the plan is executed and the interceptor adds friction, the inline log is an acceptable simplification.

### Redaction policy

This service processes payment amounts and card metadata. The `LogRedactor` utility enforces the following rules in one place so every aspect and interceptor calls `LogRedactor.sanitise(dto)` rather than scattering field checks:

| Field | Rule |
|---|---|
| PAN / full account number | Mask all but last 4 digits: `****-****-****-1234`. Today's DTOs do not carry PAN — this rule exists to prevent regression if PAN is added later. |
| CVV / CVC | Never log; replace with `[REDACTED]`. |
| `cardNetwork` | Loggable as-is (VISA, MASTERCARD, etc. — not sensitive). |
| `amount` + `currency` | Loggable — needed for support and incident investigation. |
| `merchantId` | Loggable — already in MDC. |
| `Idempotency-Key` | Log as-is — opaque token, not PII. |
| `lockedRate` | Loggable — FX rate, no PII. |
| `fraudReason` | Log at DEBUG only — may contain model feature names that are operationally sensitive. |

`LogRedactor` works against the DTO/event `record` types, not raw JSON strings, so it is applied before serialization — no regex scrubbing of already-serialised strings.

### Log levels and the `logging.payload.enabled` toggle

```yaml
logging:
  level:
    hp.microservice.demo.transaction_service.logging: INFO
  payload:
    enabled: false
```

- `INFO` — method entry/exit, latency, outcome. Always on.
- `DEBUG` — full (redacted) request and response bodies. Enabled by setting `logging.payload.enabled: true` or raising the logger level to `DEBUG`. Default is `false` so production runs lean without log-volume risk.
- All aspects short-circuit the payload serialization path with an `if (log.isDebugEnabled() && payloadLoggingEnabled)` guard — serializing a proto message or a DTO to string on every request at INFO with no consumer is wasteful even with virtual threads.

---

## Phase 13 — Observability

**Actuator endpoints** exposed (matching existing deployment.yaml probe paths):

- `GET /actuator/health` — both liveness and readiness probes point here; `show-details: always`; probes enabled so Spring Boot emits `/actuator/health/liveness` and `/actuator/health/readiness` separately if needed
- `GET /actuator/info`
- `GET /actuator/metrics`
- `GET /actuator/prometheus` — Micrometer Prometheus registry; `micrometer-registry-prometheus` dependency already planned

**Custom metrics to add (via `MeterRegistry`):**

| Metric | Type | Tags |
|---|---|---|
| `payment.submitted.total` | Counter | `status` (APPROVED, REJECTED) |
| `payment.fraud.verdict` | Counter | `verdict` (APPROVED, REVIEW, REJECTED) |
| `payment.outbox.pending` | Gauge | — |
| `grpc.client.duration` | Timer | `service`, `method` — Resilience4j + micrometer auto-register most of this |

**Health indicators:** Spring Boot auto-configures DB and Kafka health contributors. No custom `HealthIndicator` needed in Phase 1.

---

## Phase 14 — Testing strategy

### Unit tests (JUnit 5 + Mockito, no Spring context)

| Test class | Covers |
|---|---|
| `PaymentServiceTest` | Submit happy path, duplicate idempotency key handling; mocks `SagaOrchestrator` and `TransactionRepository` — no knowledge of `FraudGateway` / `FxGateway` |
| `FraudCheckingHandlerTest` | Fraud approved, rejected, REVIEW treated as pass; mocks `FraudGateway` interface — no gRPC stubs |
| `FxLockingHandlerTest` | Rate lock happy path, `FxGateway` unavailable → `FAILED` state; mocks `FxGateway` interface |
| `GrpcFraudGatewayTest` | Circuit breaker open path, retry, normal gRPC flow; uses `grpc-testing` in-process server |
| `GrpcFxGatewayTest` | Rate lock happy path, timeout, `StatusRuntimeException` → typed domain exception |
| `OutboxRelayServiceTest` | Polling logic, retry_count increment, FAILED cutoff |
| `PaymentResultHandlerTest` | State machine advancement, illegal transition guard, manual ack |
| `RestLoggingAspectTest` | `LogRedactor` is invoked; PAN present in DTO is masked before log output |
| `GrpcClientLoggingAspectTest` | `LogRedactor` is invoked; circuit-open outcome logged as distinct case from `StatusRuntimeException` |

Use `@ExtendWith(MockitoExtension.class)`. At the service layer, mock domain interfaces (`FraudGateway`, `FxGateway`, `EventPublisher`) — never raw gRPC stubs. gRPC stub behaviour is exercised only in `GrpcFraudGatewayTest` and `GrpcFxGatewayTest` which use `grpc-testing` in-process servers.

### Repository slice tests (@DataJpaTest)

- `TransactionRepositoryTest` — idempotency key unique constraint enforcement, merchant filter query
- `OutboxRepositoryTest` — PENDING query, ordering guarantee

Use Testcontainers `PostgreSQLContainer` with `@DataJpaTest`. `@DataJpaTest` replaces the datasource with H2 by default and disables Flyway — three additional annotations are required to make it work with a real Postgres container and real migrations:

1. `@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)` — prevents H2 substitution and keeps the datasource pointed at the Testcontainers Postgres.
2. `@DynamicPropertySource` — injects the container's JDBC URL, username, and password into the datasource config at test startup.
3. `@ImportAutoConfiguration(FlywayAutoConfiguration.class)` — re-enables Flyway in the test slice (it is excluded by default in `@DataJpaTest`).

With all three in place, Flyway migrations run against the real container and the schema matches production exactly.

### REST controller slice tests (@WebMvcTest)

- `PaymentControllerTest` — JWT present/absent, role enforcement, `@Valid` rejection, `ProblemDetail` shape
- Use `SecurityMockMvcRequestPostProcessors.jwt()` from `spring-security-test` to inject a mock JWT with the `realm_access.roles` claim matching the production converter:

```java
mockMvc.perform(post("/api/v1/payments")
    .with(jwt().jwt(j -> j.claim("realm_access", Map.of("roles", List.of("MERCHANT"))))))
```

`@WithMockJwt` does not exist in `spring-security-test`. The correct API is the `jwt()` request post-processor shown above.

### Integration tests (full Spring context + Testcontainers)

- `PaymentSagaIntegrationTest` — `@SpringBootTest`, Postgres + Kafka containers, WireMock for Keycloak JWKS, in-process gRPC server (from `grpc-testing`) for fraud and fx stubs
- Scenarios:
  1. Happy path — submit → fraud approved → FX locked → outbox relay → `payment.submitted` appears in Kafka
  2. Fraud rejection — submit → `FRAUD_REJECTED`, no Kafka message
  3. FX circuit open — submit → `FAILED`, no Kafka message
  4. `payment.result` SUCCEEDED consumed → transaction transitions to `SUCCEEDED`
  5. DLQ path — publish malformed `payment.result` → after 3 retries, message lands on `payment.dlq`

**Keycloak in tests:** WireMock stubs the JWKS endpoint. The JWT in tests is a self-signed RSA key generated in a `@BeforeAll` block. The `issuer-uri` is overridden with the WireMock URL via `@DynamicPropertySource`.

---

## Phase 15 — Docker image build

The multi-module `Dockerfile` follows the api-gateway pattern. It must be placed at the **repo root context** (`microservices.demo/`) so it can access both the `proto/` module and the service sources in one build context.

Create `microservices.demo/services/transaction-service/Dockerfile` (invoked from root context):

```dockerfile
# syntax=docker/dockerfile:1.6
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
COPY proto proto
COPY services/transaction-service services/transaction-service
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew :services:transaction-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/transaction-service/build/libs/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=25"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

**Build and import (PowerShell from `microservices.demo/`):**

```powershell
docker build -f services/transaction-service/Dockerfile -t micro/transaction-service:dev .
k3d image import micro/transaction-service:dev -c micro
kubectl rollout restart deployment/transaction-service -n app
```

Note: `imagePullPolicy: Never` in the deployment means the rollout will use whatever is in the k3d node's image store — always re-import after a new build or the pod will keep running the old image.

---

## Phase 16 — Cluster bring-up checklist

### Must exist before transaction-service pod goes green

| Dependency | Status check | Blocker? |
|---|---|---|
| Postgres `paymentdb` database | Verify: `kubectl exec -n data postgres-postgresql-0 -- psql -U postgres -c "\l"`. Create if absent: `kubectl exec -n data postgres-postgresql-0 -- psql -U postgres -c "CREATE DATABASE paymentdb;"` | Hard blocker — datasource URL hardcoded |
| `transaction-service-secret` with `DB_PASSWORD` | `kubectl get secret transaction-service-secret -n app` | Hard blocker |
| Kafka broker running | `kubectl get pods -n data -l app.kubernetes.io/name=kafka` | Hard blocker — `KafkaTemplate` fails context load if broker unreachable at startup (configure `spring.kafka.producer.properties.reconnect.backoff.max.ms` to allow retry and avoid hard startup failure) |
| Kafka topics created | `kafka-topics.sh --list ...` | Soft blocker — topics auto-create if `auto.create.topics.enable=true`, but it's better to pre-create with correct partition count |
| Keycloak realm `payments` with roles `MERCHANT`, `ADMIN` | `kubectl get pods -n auth` + Keycloak admin console | Hard blocker — JWKS endpoint must respond at startup (Spring fetches JWKS on application start) |
| `transaction-service-config` ConfigMap applied | `kubectl get configmap transaction-service-config -n app` | Hard blocker |

### Can be built and tested standalone (without k8s)

- Unit tests (Mockito only) — no infra needed
- `@DataJpaTest` with Testcontainers — spins its own Postgres
- `@WebMvcTest` — in-memory, no infra
- Integration test with Testcontainers — spins its own Postgres + Kafka + WireMock Keycloak

### Cross-service blockers (transaction-service healthy but flows broken)

| Service | Required for |
|---|---|
| `fraud-service` gRPC server at `fraud-service.app.svc.cluster.local:9090` | Payment submission (circuit breaker will open if absent; submissions go to `FAILED`) |
| `fx-service` gRPC server at `fx-service.app.svc.cluster.local:9090` | Cross-currency payments |
| `bank-connector` consuming `payment.submitted` | `payment.result` events; without it, transactions stuck at `SUBMITTED_TO_BANK` |

For early development, deploy stub gRPC servers for fraud and fx (returning hardcoded `APPROVED` / locked rate) before the real services are built. These can be simple `@GrpcService` Spring Boot apps with no real logic.

### Kafka topic creation (PowerShell via kubectl exec)

```powershell
kubectl exec -n data kafka-0 -- kafka-topics.sh `
  --bootstrap-server localhost:9092 `
  --create --if-not-exists `
  --topic payment.submitted --partitions 3 --replication-factor 1

kubectl exec -n data kafka-0 -- kafka-topics.sh `
  --bootstrap-server localhost:9092 `
  --create --if-not-exists `
  --topic payment.result --partitions 3 --replication-factor 1

kubectl exec -n data kafka-0 -- kafka-topics.sh `
  --bootstrap-server localhost:9092 `
  --create --if-not-exists `
  --topic payment.retry --partitions 3 --replication-factor 1

kubectl exec -n data kafka-0 -- kafka-topics.sh `
  --bootstrap-server localhost:9092 `
  --create --if-not-exists `
  --topic payment.dlq --partitions 1 --replication-factor 1
```

Note: 3 partitions matches the existing `kafka.yaml` init Job. If the init Job has already run and created these topics at a different partition count, use `--alter` to increase (Kafka does not support decreasing partition counts).

---

## Phase 17 — Out of scope / explicit deferrals

| Item | Why deferred |
|---|---|
| Webhook HTTP delivery | Needs merchant profile store; bank-connector must work end-to-end first |
| Debezium CDC for outbox | Overkill for learning cluster; polling scheduler is sufficient |
| `payment.retry` dead-letter retry topic (`@RetryableTopic`) | `DefaultErrorHandler` with local retries covers the learning goal; retry topics add infra complexity |
| PCI tokenization / card data storage | No real card data in this demo |
| Multi-region sharding / partition re-keying | Single broker, single region |
| Real circuit breaker on outbound webhook HTTP | Deferred with webhooks |
| Distributed tracing (OpenTelemetry / Zipkin) | MDC trace ID is sufficient for the learning goal; Otel agent can be bolted on later |
| `bank_connector_id` routing logic | `RoutingRule` table exists; actual routing selection deferred to bank-connector phase |
| JWT token refresh / short-lived token expiry handling | Gateway manages token lifecycle; transaction-service validates once per request |

---

## Phase-ordered task list

| Phase | Task | Verification |
|---|---|---|
| **1** | Update `build.gradle` with all dependencies; create stub `application.yml` with `issuer-uri` and `datasource` placeholders (required before Phase 5 tests can load a Spring context) | `.\gradlew.bat :services:transaction-service:dependencies` from root resolves cleanly |
| **2 / 2.1** | Create package skeleton (empty classes, no logic) using the Phase 2 layout, including all interfaces, abstract classes, and adapter stubs introduced in Phase 2.1 (`FraudGateway`, `FxGateway`, `TransactionStateHandler`, `PaymentValidationStep`, `AbstractOutboxRelayService`, `EventPublisher`, `TransactionOutboxFactory`, `AuditLogListener`, `GrpcFraudGateway`, `GrpcFxGateway` in `service/adapter/`) | `.\gradlew.bat :services:transaction-service:compileJava -x test` from root succeeds |
| **3** | Write V1–V4 Flyway migrations | `@DataJpaTest` slice starts, schema created |
| **4** | Implement `Transaction`, `TransactionStatus`, `TransactionOutbox`, `AuditLog`, `RoutingRule` entities | `@DataJpaTest` for `TransactionRepository` passes |
| **5** | Implement `PaymentRequest`/`PaymentResponse` DTOs + `PaymentController` shell (no service logic) | `@WebMvcTest` 401 on unauthenticated, 400 on invalid body |
| **6** | Implement `SecurityConfig` (JWT converter, role mapping, method security) | `@WebMvcTest` with `MERCHANT` JWT returns 200 shell response |
| **7** | Implement `GrpcFraudGateway` + `GrpcFxGateway` in `service/adapter/` (`@CircuitBreaker` + `@Retry` annotations, stub deadline, `StatusRuntimeException` → domain exception); implement `FraudCheckingHandler` + `FxLockingHandler` with `FraudGateway`/`FxGateway` interfaces | `GrpcFraudGatewayTest` + `GrpcFxGatewayTest` with in-process gRPC servers pass; `FraudCheckingHandlerTest` + `FxLockingHandlerTest` with mocked interfaces pass |
| **8** | Implement `SagaOrchestrator` + all `TransactionStateHandler` implementations; implement `PaymentService` (delegates to orchestrator) + outbox write via `TransactionOutboxFactory` | `PaymentServiceTest` unit tests pass |
| **9** | Implement `KafkaProducerConfig` + `OutboxRelayService` (scheduler + KafkaTemplate) | `OutboxRelayServiceTest` passes; integration test shows `payment.submitted` on Kafka |
| **10** | Implement `KafkaConsumerConfig` + `PaymentResultHandler` | Integration test: publish `payment.result` → transaction reaches `SUCCEEDED` |
| **11** | Implement `PaymentControllerAdvice` (ProblemDetail error mapping) | Unit test: fraud rejection returns `422` with extension field |
| **12a** | Expand stub `application.yml` to full version (Phase 11), delete `application.properties`, add `logback-spring.xml` | App starts locally with `.\gradlew.bat :services:transaction-service:bootRun` |
| **12b** | Implement `logging/` package: `LogRedactor`, `RestLoggingAspect`, `GrpcClientLoggingAspect`, Kafka `ProducerInterceptor` + `RecordInterceptor` | JSON log line for a sample `POST /api/v1/payments` shows `latencyMs` field; at `DEBUG` level, `request` and `response` fields appear with PAN masked to `****-****-****-1234` if present |
| **13** | Add MDC filter (`OncePerRequestFilter`) + gRPC client interceptor | Log output shows `traceId` and `transactionId` fields in JSON |
| **14** | Write full integration test suite (`PaymentSagaIntegrationTest`) | `.\gradlew.bat :services:transaction-service:test` from root green |
| **15** | Write `Dockerfile`, build image, import into k3d | Pod comes up `Running` in `app` namespace |
| **16** | End-to-end smoke test via api-gateway → transaction-service | `Invoke-RestMethod` with JWT → transaction `RECEIVED`, outbox relay publishes to `payment.submitted` |
