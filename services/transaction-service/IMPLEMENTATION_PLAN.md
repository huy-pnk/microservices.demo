# transaction-service — Detailed Implementation Plan

## Preliminary findings from codebase inspection

- **Multi-module build**: `microservices.demo/settings.gradle` already includes `services:transaction-service` and `proto`. The root `build.gradle` applies the snapshot repository to all subprojects. The transaction-service `settings.gradle` is standalone-only; it must be updated to reference the snapshot plugin repo and the `proto` composite or the root settings must be the build entry point — more on this in Phase 1.
- **Proto contracts exist and are compiled**: `fraud.proto` and `fx.proto` are authored. Generated stubs are in `hp.microservice.demo.proto.fraud` and `hp.microservice.demo.proto.fx`. The proto module uses grpc `1.68.0` and protobuf `3.25.5`.
- **api-gateway conventions to inherit**: Spring Boot `3.5.14-SNAPSHOT`, Spring Cloud `2025.0.0-SNAPSHOT`, `logstash-logback-encoder:8.0`, `micrometer-registry-prometheus`, Lombok, two-stage Dockerfile (`eclipse-temurin:21-jdk` → `eclipse-temurin:21-jre`), `logback-spring.xml` with `JSON_STDOUT` / `local` profile split, `realm_access.roles` → `ROLE_<role>` authority mapping.
- **No Redis env var in configmap**: the deployment and configmap have no `REDIS_*` keys. Idempotency deduplication in transaction-service must not require Redis — the gateway owns that. Transaction-service deduplication is DB-level (unique constraint on `idempotency_key` in the `transactions` table).
- **Keycloak issuer**: `http://keycloak.auth.svc.cluster.local:8080/realms/payments` — same in configmap.

---

## Phase 1 — Gradle build wiring

### 1.1 Build script changes

Replace the skeleton `build.gradle` with a full dependency set. The `settings.gradle` must be updated to include the snapshot plugin repo **and** composite-build the proto module so the transaction-service can depend on it when built standalone. When built from the root, the root `settings.gradle` already handles inclusion.

**Plugins to add:**

| Plugin | Coordinate | Version |
|---|---|---|
| Spring Boot | `org.springframework.boot` | `3.5.14-SNAPSHOT` (match api-gateway) |
| Dependency Management | `io.spring.dependency-management` | `1.1.7` |
| Protobuf | `com.google.protobuf` | `0.9.4` (match proto module) |

**Spring BOM imports:**

```
springCloudVersion = '2025.0.0-SNAPSHOT'   // same as api-gateway
```

No Spring Cloud starter is needed (transaction-service is not a gateway), but the BOM is pulled for consistent version pins.

**Runtime dependencies:**

| Dependency | Notes |
|---|---|
| `spring-boot-starter-web` | Servlet-stack REST (not reactive — transaction-service is blocking I/O, virtual threads handle concurrency) |
| `spring-boot-starter-security` | Base security; oauth2-resource-server pulls it transitively but explicit is cleaner |
| `spring-boot-starter-oauth2-resource-server` | JWT validation via JWKS |
| `spring-boot-starter-data-jpa` | Hibernate + JPA |
| `spring-boot-starter-actuator` | `/actuator/health`, metrics |
| `spring-boot-starter-validation` | `@Valid` on request bodies |
| `org.postgresql:postgresql` | Driver; version managed by Spring BOM |
| `org.flywaydb:flyway-core` | Schema migrations; version managed by Spring BOM |
| `org.flywaydb:flyway-database-postgresql` | Flyway PostgreSQL dialect module (required since Flyway 10) |
| `org.springframework.kafka:spring-kafka` | `KafkaTemplate`, `@KafkaListener` |
| `io.grpc:grpc-netty-shaded:1.68.0` | gRPC transport (matches proto module grpcVersion) |
| `io.grpc:grpc-stub:1.68.0` | Re-exported by proto but needed at runtime |
| `io.grpc:grpc-protobuf:1.68.0` | Same |
| `net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE` | `@GrpcClient` injection, compatible with Spring Boot 3.5.x |
| `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` | Circuit breaker + retry; `2.2.0` is the latest GA on Boot 3 |
| `io.github.resilience4j:resilience4j-grpc:2.2.0` | Resilience4j gRPC decorator |
| `io.micrometer:micrometer-registry-prometheus` | Metrics scraping |
| `net.logstash.logback:logstash-logback-encoder:8.0` | Match api-gateway |
| `project(':proto')` | Generated stubs via multi-module dependency |
| Lombok (compile-only + annotation processor) | Match api-gateway |

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

The `settings.gradle` needs one addition to support standalone builds:

```groovy
includeBuild '../../proto'
```

This lets `project(':proto')` resolve when running `gradlew.bat build` directly from the service directory, without requiring the root build.

### Phase 1 verification

Run from `microservices.demo/services/transaction-service/`:

```powershell
.\gradlew.bat dependencies --configuration runtimeClasspath
```

Confirm `grpc-netty-shaded`, `spring-kafka`, `flyway-core`, and the proto stub classes resolve with no version conflicts.

---

## Phase 2 — Package layout

Root package: `hp.microservice.demo.transaction_service`

```
config/
    SecurityConfig               # WebSecurityCustomizer, oauth2ResourceServer, JWT converter
    KafkaProducerConfig          # KafkaTemplate bean, producer factory, idempotent producer props
    KafkaConsumerConfig          # Consumer factory, DefaultErrorHandler, DLT recoverer
    GrpcClientConfig             # Resilience4j decorators wired around stub channels
    OutboxSchedulerConfig        # @EnableScheduling, scheduler thread pool

web/
    PaymentController            # POST /api/v1/payments, GET /api/v1/payments/{id}, GET /api/v1/payments (list)
    PaymentControllerAdvice      # @RestControllerAdvice — maps domain exceptions to ProblemDetail (RFC 9457)

service/
    PaymentService               # Saga entry point — orchestrates fraud → fx → outbox write
    FraudCheckService            # Wraps FraudServiceGrpc stub; Resilience4j circuit breaker
    FxService                    # Wraps FxServiceGrpc stub; Resilience4j circuit breaker
    OutboxRelayService           # Scheduled poller — reads pending outbox rows, publishes to Kafka, marks sent
    PaymentResultHandler         # @KafkaListener on payment.result — advances state machine, triggers webhook outbox row

repository/
    TransactionRepository        # JPA repository for Transaction entity
    OutboxRepository             # JPA repository for TransactionOutbox entity
    AuditLogRepository           # JPA repository for AuditLog entity
    RoutingRulesRepository       # JPA repository for RoutingRule entity

domain/
    Transaction                  # @Entity — central aggregate
    TransactionOutbox            # @Entity — outbox table
    AuditLog                     # @Entity — append-only audit trail
    RoutingRule                  # @Entity — static routing configuration
    TransactionStatus            # enum — state machine values

event/
    PaymentSubmittedEvent        # record — published to payment.submitted
    PaymentResultEvent           # record — consumed from payment.result
    WebhookDispatchEvent         # record — published to outbox for webhook delivery (future)

web/dto/
    PaymentRequest               # record — REST inbound, validated
    PaymentResponse              # record — REST outbound
    ErrorResponse                # record — RFC 9457 ProblemDetail wrapper
```

---

## Phase 3 — Domain model and Flyway migrations

### State machine

```
RECEIVED
  └─► FRAUD_CHECKING
        ├─► FRAUD_REJECTED  (terminal)
        └─► FRAUD_APPROVED
              └─► FX_LOCKING        (only when from_currency != to_currency)
              └─► ROUTING            (currencies match, skip FX)
                    └─► SUBMITTED_TO_BANK
                          ├─► SUCCEEDED   (terminal)
                          ├─► FAILED      (terminal)
                          └─► REVERSED    (terminal — compensation)
```

`FRAUD_CHECKING`, `FX_LOCKING`, `ROUTING` are in-progress states used when calls are made. The saga calls fraud and fx synchronously within the REST request thread before writing the outbox row (see Phase 9 for justification). If either call fails, the transaction moves to `FRAUD_REJECTED` or a `FAILED` terminal state without emitting to the bank.

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
| `GET` | `/api/v1/payments` | JWT required | `MERCHANT` | Paginated list, filtered by merchant_id from JWT |
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

**Role mapping:** Mirror api-gateway exactly. Implement a `JwtAuthenticationConverter` that reads `realm_access.roles`, prefixes `ROLE_`, and returns a `Collection<GrantedAuthority>`. Place in `config/SecurityConfig.java`.

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

`FraudCheckService` and `FxService` classes in `service/` inject `@GrpcClient("fraud-service") FraudServiceGrpc.FraudServiceBlockingStub fraudStub` (and similarly for fx).

### Resilience4j policy

Both stubs are I/O calls to external services — must be wrapped.

**`GrpcClientConfig`** defines two `CircuitBreakerConfig` beans (one for fraud, one for fx):

| Setting | Value | Rationale |
|---|---|---|
| `slidingWindowSize` | 20 | |
| `failureRateThreshold` | 50 | Open after 50% failures |
| `waitDurationInOpenState` | 10s | |
| `permittedCallsInHalfOpenState` | 3 | |
| Retry `maxAttempts` | 2 | 1 retry; gRPC is idempotent here |
| Retry `waitDuration` | 200ms | |
| TimeLimiter `timeoutDuration` | 3s | Fail fast if fraud-service is stuck |

`FraudCheckService.evaluate()` wraps the blocking stub call with Resilience4j `CircuitBreaker.decorateCheckedSupplier` + `Retry.decorateCheckedSupplier`. Throw a `FraudServiceUnavailableException` when the circuit is open — the saga catches this and transitions the transaction to `FAILED` with a recorded reason.

Same pattern for `FxService.lockRate()` with its own circuit breaker instance.

---

## Phase 7 — Kafka

### Topics

| Topic | Partitions | Producer | Consumer |
|---|---|---|---|
| `payment.submitted` | 6 | transaction-service (via outbox relay) | bank-connector |
| `payment.result` | 6 | bank-connector | transaction-service |
| `payment.retry` | 6 | DefaultErrorHandler | transaction-service |
| `payment.dlq` | 1 | DeadLetterPublishingRecoverer | — (manual inspection) |

Partition by `transactionId` (string) on all topics — consistent ordering per transaction across the pipeline.

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
```

`DefaultErrorHandler` in `KafkaConsumerConfig` bean:

- 3 attempts with exponential backoff (1s, 2s, 4s)
- `DeadLetterPublishingRecoverer` → `payment.dlq` topic after exhaustion
- Also configure a `RetryTopicConfiguration` bean for `payment.retry` if retry-topic pattern is preferred over local retries — the `@RetryableTopic` approach is cleaner but adds infrastructure topic; start with `DefaultErrorHandler` local retries, defer retry topic to a follow-on phase.

Manual offset commit (`AckMode.MANUAL_IMMEDIATE`). `PaymentResultHandler` calls `acknowledgment.acknowledge()` only after the DB state transition is committed.

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

A separate `@Scheduled` poller in `OutboxRelayService` runs every 500ms:

1. Fetch up to 50 rows WHERE `status = 'PENDING'` ORDER BY `created_at` (FIFO per transaction, via index).
2. For each row: send to Kafka with `transactionId` as the message key (guarantees partition ordering).
3. On successful send (synchronous `KafkaTemplate.send(...).get()` with a 5s timeout): update row to `SENT`, set `sent_at = now()`.
4. On failure: increment `retry_count`; if `retry_count >= 5`, set `status = 'FAILED'` (manual intervention needed; reconciliation-service will flag it).
5. Steps 3/4 run in a new `@Transactional(REQUIRES_NEW)` per row so one failed row does not roll back others.

The polling interval of 500ms is acceptable for a learning cluster. In production, transactional outbox polling is typically replaced by Debezium CDC, but that is explicitly deferred.

### Why outbox rather than direct Kafka publish in the saga

Publishing directly from within the JPA transaction is the classic "dual write" problem — if Kafka publish succeeds but the DB commit fails (or vice versa), the system is inconsistent. The outbox pattern makes the Kafka publish a consequence of a committed DB row, eliminating this race. The 500ms polling latency is the trade-off.

---

## Phase 9 — Saga orchestration

### Design decision: synchronous fraud + fx, then outbox

Fraud and FX are synchronous gRPC calls made **inside the REST request thread** before anything is written to the DB or Kafka. Rationale: the merchant waits for a synchronous response and needs an immediate answer on whether the payment was fraud-rejected or had an FX problem. Deferring these to async Kafka steps would require the merchant to poll for a result, which is more complex and not warranted here.

### Sequence (happy path — cross-currency)

```
Client          PaymentController       PaymentService     FraudCheckService   FxService   DB (JPA tx)   OutboxRelay   Kafka
  │                    │                      │                    │               │            │              │           │
  │─POST /payments──►  │                      │                    │               │            │              │           │
  │  Idempotency-Key   │                      │                    │               │            │              │           │
  │                    │─submit(request,jwt)──►│                    │               │            │              │           │
  │                    │                      │─persist RECEIVED──────────────────────────────►│              │           │
  │                    │                      │  + outbox(PENDING) ──────────────────────────►│              │           │
  │                    │                      │  (single tx commit)────────────────────────── commit         │           │
  │                    │                      │                    │               │            │              │           │
  │                    │                      │─evaluate(...)─────►│               │            │              │           │
  │                    │                      │◄─APPROVED──────────│               │            │              │           │
  │                    │                      │─update FRAUD_APPROVED────────────────────────►│              │           │
  │                    │                      │                    │               │            │              │           │
  │                    │                      │─lockRate(...)──────────────────────►│          │              │           │
  │                    │                      │◄─lockedRate────────────────────────│           │              │           │
  │                    │                      │─update FX_LOCKED + lockedRate────────────────►│              │           │
  │                    │                      │  + outbox(payment.submitted, PENDING)─────── commit         │           │
  │                    │                      │                    │               │            │              │           │
  │◄─202 Accepted──────│◄─PaymentResponse─────│                    │               │            │              │           │
  │  {id, status=FX_LOCKED, lockedRate}                            │               │            │              │           │
  │                    │                      │                    │               │            │   (500ms)     │           │
  │                    │                      │                    │               │            │──poll PENDING─►           │
  │                    │                      │                    │               │            │              │─send key=txId►
  │                    │                      │                    │               │            │              │◄─ack        │
  │                    │                      │                    │               │            │◄─mark SENT───│           │
```

Note: the initial `RECEIVED + outbox(PENDING)` row is an internal tracking row (event_type=`payment.initiated`, for audit). The `payment.submitted` outbox row is written **after** fraud and FX have succeeded. This is the cleanest model: if fraud rejects, no outbox event is written and bank-connector never sees the payment.

**Clarification on the two outbox writes:**
- Write 1 (RECEIVED): captures that the payment arrived — useful for reconciliation but not sent to bank-connector.
- Write 2 (payment.submitted, after fraud+fx): the actual event consumed by bank-connector.

Only Write 2 goes to the `payment.submitted` Kafka topic. Write 1 can be omitted from Kafka and kept purely in `audit_log` if the audit log captures the `RECEIVED` transition.

**Simpler model (what to implement):** One outbox write per transaction, written after fraud+fx succeed. The `RECEIVED` state transition is recorded in `audit_log` directly (synchronous DB write, no outbox needed). This avoids phantom `payment.submitted` events for fraud-rejected transactions.

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
  timelimiter:
    instances:
      fraud-service:
        timeoutDuration: 3s
      fx-service:
        timeoutDuration: 3s

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
| `PaymentServiceTest` | Saga happy path, fraud rejection, FX failure, state transitions |
| `FraudCheckServiceTest` | Circuit breaker open path, retry, normal flow |
| `FxServiceTest` | Rate lock happy path, timeout |
| `OutboxRelayServiceTest` | Polling logic, retry_count increment, FAILED cutoff |
| `PaymentResultHandlerTest` | State machine advancement, illegal transition guard, manual ack |

Use `@ExtendWith(MockitoExtension.class)`. Mock repository interfaces and gRPC stubs.

### Repository slice tests (@DataJpaTest)

- `TransactionRepositoryTest` — idempotency key unique constraint enforcement, merchant filter query
- `OutboxRepositoryTest` — PENDING query, ordering guarantee

Use Testcontainers `PostgreSQLContainer` with `@DataJpaTest`. Flyway migrations run automatically against the container — this tests migrations too.

### REST controller slice tests (@WebMvcTest)

- `PaymentControllerTest` — JWT present/absent, role enforcement, `@Valid` rejection, `ProblemDetail` shape
- Use `@WithMockJwt` (from `spring-security-test`) with a custom `realm_access.roles` claim structure matching the production converter

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
| Postgres `paymentdb` database | `kubectl exec -n data postgres-postgresql-0 -- psql -U postgres -c "\l"` | Hard blocker — datasource URL hardcoded |
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
  --topic payment.submitted --partitions 6 --replication-factor 1

kubectl exec -n data kafka-0 -- kafka-topics.sh `
  --bootstrap-server localhost:9092 `
  --create --if-not-exists `
  --topic payment.result --partitions 6 --replication-factor 1

kubectl exec -n data kafka-0 -- kafka-topics.sh `
  --bootstrap-server localhost:9092 `
  --create --if-not-exists `
  --topic payment.retry --partitions 6 --replication-factor 1

kubectl exec -n data kafka-0 -- kafka-topics.sh `
  --bootstrap-server localhost:9092 `
  --create --if-not-exists `
  --topic payment.dlq --partitions 1 --replication-factor 1
```

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
| **1** | Update `build.gradle` + `settings.gradle` with all dependencies | `.\gradlew.bat dependencies` resolves cleanly |
| **2** | Create package skeleton (empty classes, no logic) | `.\gradlew.bat compileJava -x test` succeeds |
| **3** | Write V1–V4 Flyway migrations | `@DataJpaTest` slice starts, schema created |
| **4** | Implement `Transaction`, `TransactionStatus`, `TransactionOutbox`, `AuditLog`, `RoutingRule` entities | `@DataJpaTest` for `TransactionRepository` passes |
| **5** | Implement `PaymentRequest`/`PaymentResponse` DTOs + `PaymentController` shell (no service logic) | `@WebMvcTest` 401 on unauthenticated, 400 on invalid body |
| **6** | Implement `SecurityConfig` (JWT converter, role mapping, method security) | `@WebMvcTest` with `MERCHANT` JWT returns 200 shell response |
| **7** | Implement `FraudCheckService` + `FxService` (gRPC stubs, Resilience4j wrapping) | Unit tests with mocked stubs pass |
| **8** | Implement `PaymentService` saga logic (fraud → fx → outbox write) | `PaymentServiceTest` unit tests pass |
| **9** | Implement `KafkaProducerConfig` + `OutboxRelayService` (scheduler + KafkaTemplate) | `OutboxRelayServiceTest` passes; integration test shows `payment.submitted` on Kafka |
| **10** | Implement `KafkaConsumerConfig` + `PaymentResultHandler` | Integration test: publish `payment.result` → transaction reaches `SUCCEEDED` |
| **11** | Implement `PaymentControllerAdvice` (ProblemDetail error mapping) | Unit test: fraud rejection returns `422` with extension field |
| **12** | Write `application.yml`, delete `application.properties`, add `logback-spring.xml` | App starts locally with `.\gradlew.bat bootRun` |
| **13** | Add MDC filter (`OncePerRequestFilter`) + gRPC client interceptor | Log output shows `traceId` and `transactionId` fields in JSON |
| **14** | Write full integration test suite (`PaymentSagaIntegrationTest`) | `.\gradlew.bat test` green |
| **15** | Write `Dockerfile`, build image, import into k3d | Pod comes up `Running` in `app` namespace |
| **16** | End-to-end smoke test via api-gateway → transaction-service | `Invoke-RestMethod` with JWT → transaction `RECEIVED`, outbox relay publishes to `payment.submitted` |
