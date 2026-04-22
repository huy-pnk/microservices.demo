# Mini Payment Gateway Project — Overview

A local-only learning project exercising microservices, gRPC, REST, event streaming, caching, and centralized logging on a lightweight Kubernetes cluster — structured around the patterns of a multinational payment gateway.

---

## 1. Goals

- Run **entirely locally** on a developer laptop (no cloud dependency).
- Touch each required technology in a way that reflects how it is actually used in production.
- Practice the core patterns of a payment system — idempotency, Saga orchestration, outbox, circuit breakers, DLQ, reconciliation — without the weight of real bank integrations or ML fraud models.
- Keep the scope small: six services, clear boundaries, stubs where appropriate.
- Be reproducible: `docker` + `k3d` + a handful of manifests should bring the whole system up.

---

## 2. Domain

A minimal **multinational payment gateway**. The full target architecture lives in `Payment system/architecture-explained.md`; this project implements a pared-down version focused on the learning-rich patterns.

Chosen because it naturally produces:

- Synchronous internal calls (fraud check, FX rate lookup) → good fit for **gRPC**.
- External merchant/client calls → good fit for **REST** (with idempotency headers).
- Events flowing asynchronously between the orchestrator and bank connectors → good fit for **Kafka** (with retry topic + DLQ).
- Hot, repeatedly-read data (idempotency keys, FX rates) → good fit for **Redis**.
- High log volume plus long-retention audit needs → good fit for **ELK**.

### What is stubbed / simplified

Because this is a learning project, we deliberately do **not** implement:

- **Real bank integrations** — a single `bank-connector` service simulates Bank A / B / C / PSP with configurable latency and failure rates.
- **ML-based fraud** — `fraud-service` is a rules engine (velocity checks, amount thresholds, country blocklist) that exercises the same interface pattern.
- **Real FX providers** — `fx-service` uses a static rate table with Redis-backed caching to exercise the cache-aside pattern.
- **Sharded Postgres** — single Postgres instance; sharding is called out as a production concern but not built.
- **PCI DSS tokenization, PSD2/SCA, 3D Secure** — out of scope.

---

## 3. Services

All services are **Spring Boot 3.x on JDK 21**, packaged as Docker images, deployed to k3d.

| Service | Inbound | Outbound | Persistence | Purpose |
|---|---|---|---|---|
| `api-gateway` (Spring Cloud Gateway) | REST (external) | REST → internal services | Redis (idempotency keys) | Edge JWT validation, token relay, rate limiting, Idempotency Guard |
| `transaction-service` | REST | gRPC → fraud/fx, Kafka producer/consumer, Postgres | Postgres (`paymentdb`) | Saga orchestrator; owns payment routing rules; state machine; outbox pattern; sends merchant webhooks |
| `fraud-service` | gRPC | — | — | Rules-based fraud evaluation (velocity, amount, country blocklist) |
| `fx-service` | gRPC | Redis | Redis (cache) | Foreign exchange rate lookup with cache-aside pattern; rate locking per transaction |
| `bank-connector` | Kafka consumer | Kafka producer | — | Simulates bank/PSP calls behind a circuit breaker; emits `payment.result`; retry + DLQ handling |
| `reconciliation-service` | Scheduled (cron) | Postgres, simulated bank status API | — | Finds transactions stuck in ambiguous states and resolves them |
| `keycloak` (infra) | REST (admin + OIDC) | Postgres | Postgres (`keycloakdb`) | Identity provider, issues JWTs |

### Communication pattern summary

- **REST** — external merchant/client traffic via the gateway.
- **gRPC** — internal synchronous calls (`transaction-service` → `fraud-service`, `fx-service`).
- **Kafka** — asynchronous events between `transaction-service` and `bank-connector`, with dedicated retry and dead-letter topics.
- **Redis** — idempotency keys at the gateway; FX rate cache inside `fx-service`.

Each protocol has a real reason to be here — not just a box to tick.

---

## 4. Tech Stack

### Application layer
- **Java 21** (virtual threads available for I/O-bound services).
- **Spring Boot 3.x**
  - `spring-boot-starter-web` (REST)
  - `spring-cloud-starter-gateway` (api-gateway only)
  - `spring-boot-starter-oauth2-resource-server` (JWT validation on every service)
  - `grpc-spring-boot-starter` (gRPC server/client)
  - `spring-kafka` (producer/consumer, DLT pattern via `DefaultErrorHandler` + `DeadLetterPublishingRecoverer`)
  - `spring-data-redis`
  - `spring-boot-starter-data-jpa` + Flyway + Postgres driver
  - `spring-boot-starter-actuator` (health, metrics)
  - `resilience4j-spring-boot3` (circuit breaker inside `bank-connector`)
  - `spring-boot-starter-quartz` or `@Scheduled` (reconciliation-service cron)
- **Protocol Buffers** for gRPC contracts (shared `proto` module).
- **Gradle** — build tool for all services, shared convention plugin where useful.

### Data & messaging
- **PostgreSQL** — single instance with two logical DBs (`paymentdb`, `keycloakdb`). Sharding by `merchant_id` / `region` is a noted production concern, not built here.
  - `paymentdb` schemas/tables: `transactions`, `transaction_outbox`, `audit_log` (append-only), `routing_rules`.
- **Redis** — cache + ephemeral state (idempotency keys at gateway, FX rate cache in `fx-service`).
- **Kafka** (single broker, KRaft mode — no ZooKeeper) — event backbone, **JSON** payloads (no schema registry for now).
  - Topics: `payment.submitted`, `payment.result`, `payment.retry`, `payment.dlq`.
  - Partitioned by `transactionId` to preserve per-transaction order.

### Identity & auth
- **Keycloak** — in-cluster OIDC provider, backed by Postgres.
  - One realm (`payments`), one confidential client for the gateway.
  - Roles: `MERCHANT`, `ADMIN`.
- **JWT validation**:
  - `api-gateway` validates tokens at the edge (fail fast for unauthenticated traffic).
  - Each downstream service also validates independently (defense in depth) using Keycloak's **JWKS** endpoint — offline validation, no per-request call to Keycloak.
  - Gateway relays the token downstream via `Authorization` header (`TokenRelay` filter).

### Observability
- **Elasticsearch** — log store (also serves as the long-retention audit log sink).
- **Logstash** — ingestion / parsing pipeline.
- **Kibana** — dashboards and search (end-to-end trace per `transactionId`).
- **Filebeat** — DaemonSet shipping container logs from every k3d node.
- Spring Boot apps log as **JSON** (via `logstash-logback-encoder`); `correlationId` and `transactionId` injected into MDC and propagated through REST headers, gRPC metadata, and Kafka record headers.

### Runtime & orchestration
- **Docker** — image build for each service.
- **k3d** — lightweight k3s in Docker; the entire cluster lives in containers on the host.
- **kubectl** + **Helm** (for ELK / Kafka / Redis / Postgres / Keycloak charts) — app services deployed via plain manifests to keep things readable.

---

## 5. Local Topology

```
                ┌───────────────────────────────────────────────────────────────────────┐
                │                             k3d cluster                              │
                │                                                                       │
   host:8080 ─► │  Traefik Ingress                                                      │
                │     │                                                                 │
                │     ├─► keycloak ── Postgres(keycloakdb)                              │
                │     │                                                                 │
                │     └─► api-gateway  (JWT + Idempotency Guard ── Redis)               │
                │              │                                                        │
                │              └─ REST ─► transaction-service  (Saga + routing rules)  │
                │                               │                                       │
                │                               ├─ gRPC ─► fraud-service                │
                │                               ├─ gRPC ─► fx-service ── Redis          │
                │                               ├── Postgres(paymentdb)                 │
                │                               └── Kafka(payment.submitted) ──┐        │
                │                                                              │        │
                │                         bank-connector (circuit breaker) ◄───┘        │
                │                                   │                                    │
                │                                   ├── Kafka(payment.retry) (loopback)  │
                │                                   ├── Kafka(payment.dlq)               │
                │                                   └── Kafka(payment.result) ──┐        │
                │                                                               │        │
                │                                   transaction-service ◄───────┘        │
                │                                           │                             │
                │                                           └── Webhook ─► merchant       │
                │                                                                          │
                │  reconciliation-service (scheduled) ──► paymentdb + simulated bank API  │
                │                                                                          │
                │  Filebeat (DaemonSet) ─► Logstash ─► Elasticsearch ─► Kibana            │
                └──────────────────────────────────────────────────────────────────────────┘
          host:5601 (Kibana)   host:8081 (Keycloak)   host:9200 (ES, optional)
```

**Ingress:** Traefik (bundled with k3d) handles cluster-level routing and TLS. Spring Cloud Gateway sits behind it as the app-level gateway — JWT, token relay, Idempotency Guard, rate limiting.

---

## 6. Repository Layout (proposed)

```
Microservices/
├── OVERVIEW.md
├── Payment system/             # target architecture reference (source of truth for design)
│   ├── architecture-explained.md
│   └── architecture.mmd
├── settings.gradle             # Gradle multi-module root
├── build.gradle                # shared conventions
├── proto/                      # shared .proto files (published as a module)
├── services/
│   ├── api-gateway/            # Spring Cloud Gateway + JWT + Idempotency Guard
│   ├── transaction-service/    # Saga orchestrator + routing rules + outbox
│   ├── fraud-service/          # gRPC rules engine
│   ├── fx-service/             # gRPC FX with Redis cache
│   ├── bank-connector/         # Kafka consumer/producer + circuit breaker + retry/DLQ
│   └── reconciliation-service/ # scheduled reconciliation
├── deploy/
│   ├── k3d/                    # cluster config
│   ├── infra/                  # postgres, redis, kafka, elk, keycloak manifests / helm values
│   └── apps/                   # per-service k8s manifests
└── scripts/                    # bootstrap / teardown helpers
```

---

## 7. End-to-End Flow (happy-path payment)

1. Merchant authenticates against **Keycloak** (`POST /realms/payments/protocol/openid-connect/token`) and receives a JWT.
2. Merchant `POST /payments` with `Authorization: Bearer <jwt>` and `Idempotency-Key: <uuid>` → Traefik → **Spring Cloud Gateway**.
3. Gateway validates the JWT, checks the Idempotency-Key in **Redis** (`SET NX` with TTL) — on a duplicate, it returns the cached response without calling downstream.
4. Gateway relays the request + JWT to `transaction-service`.
5. `transaction-service` re-validates the JWT, creates a transaction record in **Postgres** (state `CREATED`), then calls `fraud-service` over **gRPC**.
6. `fraud-service` runs the rules engine → returns `APPROVED` / `REVIEW` / `REJECTED`. On approval, state moves to `FRAUD_CHECKED`.
7. `transaction-service` calls `fx-service` over gRPC → returns a rate from Redis cache (or static table on miss). The locked rate is persisted on the transaction record.
8. `transaction-service` evaluates routing rules (region / currency / card network) and picks a target bank. State → `PROCESSING`.
9. `transaction-service` writes the transaction update **and** an outbox row in a single Postgres transaction, then a background publisher emits `payment.submitted` to **Kafka** (outbox pattern → no lost events if Kafka is briefly down).
10. `bank-connector` consumes `payment.submitted` → invokes the simulated bank endpoint through a **Resilience4j circuit breaker**.
    - Success → publishes `payment.result` (outcome=SUCCESS).
    - Transient failure → republishes to `payment.retry` with exponential backoff; after max attempts → `payment.dlq`.
    - Open circuit → short-circuits to `payment.retry` without calling the bank.
11. `transaction-service` consumes `payment.result` → updates state to `COMPLETED` / `FAILED` → fires a webhook to the merchant's registered endpoint.
12. `reconciliation-service` runs on a 15-minute schedule: finds transactions stuck in `PROCESSING` beyond a threshold, queries the simulated bank status endpoint, and resolves them (`COMPLETED` / `FAILED` / compensating action).
13. All services emit **JSON logs** with `correlationId` + `transactionId` → Filebeat → Logstash → Elasticsearch → **Kibana** dashboard renders the full lifecycle.

---

## 8. Out of Scope (intentionally)

- HA for Kafka / Redis / Postgres / Elasticsearch / Keycloak — single replica for all infra.
- Service mesh (Istio/Linkerd) — not needed at this scale.
- CI/CD — local `docker build` + `kubectl apply` is fine.
- Distributed tracing — can be added later (OTel → Jaeger) if desired.
- Kafka schema registry — JSON payloads are sufficient.
- Real bank/PSP integrations — stubbed by `bank-connector`.
- ML-based fraud scoring — rules-only in `fraud-service`.
- PCI DSS tokenization, PSD2/SCA, 3D Secure — out of scope.
- Sharded Postgres — single instance; sharding noted as a production concern but not implemented.
- Production-grade Keycloak setup (HTTPS, external DB credentials via secrets manager) — dev-mode is acceptable locally.

---

## 9. Build-Out Order (suggested)

1. k3d cluster up + Traefik sanity check.
2. Deploy **Postgres**, Redis, Kafka, ELK, **Keycloak**; confirm Kibana shows logs and Keycloak admin console is reachable.
3. Create the Keycloak realm (`payments`), confidential client, and a test merchant user; verify token issuance via `curl`.
4. Scaffold `bank-connector` first as a pure Kafka echo service — consume `payment.submitted`, publish `payment.result` with a hard-coded SUCCESS. This gives later services a working Kafka partner to integrate against.
5. Build `transaction-service` end-to-end on the happy path: REST ingress + Postgres + Flyway migrations + Saga state machine + outbox publisher + `payment.result` consumer + webhook caller. No fraud/FX yet — inline stubs.
6. Add `fraud-service` (gRPC rules engine) and wire `transaction-service` → fraud gRPC call into the Saga.
7. Add `fx-service` (gRPC + Redis cache) and wire into the Saga. Implement rate locking.
8. Flesh out `bank-connector`: Resilience4j circuit breaker, retry topic with exponential backoff, DLQ, and configurable random failure injection (latency, transient errors, hard declines).
9. Add compensating actions to the Saga (release rate lock, reverse reservation, refund on post-settlement failure).
10. Add `reconciliation-service` with scheduled job and a simulated bank status endpoint on `bank-connector`.
11. Add `api-gateway` (Spring Cloud Gateway): JWT validation, token relay, Redis-backed Idempotency Guard, rate limiting. Wire Traefik → gateway.
12. Build a Kibana dashboard: full payment lifecycle correlated by `transactionId`, with panels for Saga state transitions, circuit breaker state, DLQ depth, and reconciliation outcomes.

---

## 10. Decisions (locked)

| Question | Decision |
|---|---|
| Domain | **Payment gateway** — learning version of the architecture in `Payment system/`. |
| Persistence | **PostgreSQL**, single instance with logical DBs (`paymentdb`, `keycloakdb`). Sharding noted but not implemented. |
| Gateway | **Spring Cloud Gateway**, sitting behind Traefik ingress; Redis-backed Idempotency Guard. |
| Auth | **Keycloak** (in-cluster) issuing **JWTs**; validated at gateway and at each service via JWKS. |
| Build tool | **Gradle** (multi-module root). |
| Kafka payloads | **JSON** — no schema registry. Topics: `payment.submitted`, `payment.result`, `payment.retry`, `payment.dlq`. Partitioned by `transactionId`. |
| Orchestration pattern | **Saga with compensating actions**, persisted state machine, **outbox pattern** for DB→Kafka. |
| Resilience | **Resilience4j** circuit breaker per simulated bank inside `bank-connector`; exponential backoff via Kafka retry topic; DLQ for poison messages. |
| Bank integrations | **Stubbed** in `bank-connector` — configurable latency and failure rates. |
| Fraud detection | **Rules-only** (no ML). |
| Payment routing | Implemented as a component **inside `transaction-service`** (not a separate service) — keeps the service count at six. |
