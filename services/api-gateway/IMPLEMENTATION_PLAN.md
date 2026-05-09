# api-gateway — Implementation Plan

> Status: **Planning only — no code changes yet.** Review this document before I start coding.

## 0. Locked decisions

| # | Question | Decision |
|---|---|---|
| 1 | Route path prefix | **`/api/payments/**`** |
| 2 | `Idempotency-Key` header | **Required** on `POST /api/payments/**` — missing header → 400 |
| 3 | Rate limiter key | **Per merchant** (from JWT `preferred_username`) |
| 4 | Body SHA-256 check on duplicates | **Included in MVP** — same key + different body → 422 |
| 5 | Keycloak issuer strategy | Canonical issuer **`http://keycloak.localhost:8090/realms/payments`**; pods use `hostAliases` to reach it in-cluster |

---

## 1. Purpose (recap from OVERVIEW.md)

The **single external entrypoint** for the payment gateway. Sits behind Traefik inside the cluster. Responsibilities:

1. **JWT validation at the edge** — reject unauthenticated traffic fast (before it reaches any business service).
2. **Token relay** — forward the validated JWT to downstream services via the `Authorization` header.
3. **Idempotency Guard** — look up the `Idempotency-Key` header in Redis. On a duplicate, return the cached response without touching downstream.
4. **Rate limiting** — protect downstream services from abusive merchants (Redis-backed).
5. **Route mapping** — `/api/payments/**` → `transaction-service`.

---

## 2. Current state vs. target

| Item | Current | Target |
|---|---|---|
| Entry point class | ✅ `ApiGatewayApplication.java` | — |
| Security config | ⚠️ Basic JWT, no role check | Role-based: `MERCHANT` required for `/api/payments/**` |
| Routes in `application.yml` | ❌ Old e-commerce (user-service, product-service, order-service) | `transaction-service` only |
| Keycloak realm URI | ❌ `microservices-demo` | `payments` |
| Redis dependency | ❌ Missing | `spring-boot-starter-data-redis-reactive` |
| Idempotency Guard filter | ❌ Missing | Custom `GatewayFilterFactory` |
| Rate limiter | ❌ Missing | `RequestRateLimiter` filter (Redis-backed) |
| Virtual threads | ❌ Not enabled | `spring.threads.virtual.enabled=true` |
| `Dockerfile` | ❌ Missing | Multi-stage (JDK 21 build → JRE runtime) |
| K8s manifests | ❌ Missing | `deploy/apps/api-gateway/*` |
| JSON logging | ⚠️ `logstash-logback-encoder` in deps but check `logback-spring.xml` | Confirm JSON output |
| Tests | ⚠️ Default smoke test only | Add integration tests for security + idempotency |

---

## 3. Request flow (happy path)

```
Client
  │  POST /api/payments
  │  Authorization: Bearer <jwt>
  │  Idempotency-Key: <uuid>
  ▼
Traefik (host: localhost:8090)
  │
  ▼
api-gateway :8080
  │
  ├─1─ SecurityWebFilterChain
  │     • Validate JWT against Keycloak JWKS
  │     • Reject (401) if invalid / missing
  │     • Extract authorities from realm_access.roles claim
  │     • Authorize: require MERCHANT role for /api/payments/**
  │
  ├─2─ IdempotencyGuardFilter (custom GatewayFilter)
  │     • Read Idempotency-Key header
  │     • Redis key: idempotency:{merchantId}:{idemKey}
  │     • If present and "COMPLETED" → return cached body + status
  │     • If present and "IN_FLIGHT" → return 409 Conflict
  │     • If absent → SETNX with status "IN_FLIGHT", TTL 24h
  │     • On downstream response → update Redis entry with body + status "COMPLETED"
  │
  ├─3─ TokenRelay filter (built-in Spring Cloud Gateway)
  │     • Adds Authorization: Bearer <jwt> to downstream call
  │
  ├─4─ RequestRateLimiter filter (Redis-backed)
  │     • Key: by merchantId (from JWT preferred_username or sub)
  │     • Limit: 100 req/s burst, 50 req/s replenish (configurable)
  │
  ▼
transaction-service :8080  (http://transaction-service.app.svc.cluster.local:8080)
```

---

## 4. Dependencies to add to `build.gradle`

| Dependency | Why |
|---|---|
| `org.springframework.boot:spring-boot-starter-data-redis-reactive` | Idempotency Guard + rate limiter state |
| `org.springframework.boot:spring-boot-starter-validation` | Validate `Idempotency-Key` header format (UUID) |
| `io.micrometer:micrometer-registry-prometheus` | Expose `/actuator/prometheus` — optional but aligns with SKELETON_GUIDE.md |

**Keep as-is:**
- `spring-cloud-starter-gateway` (reactive, pulls Netty + WebFlux)
- `spring-boot-starter-oauth2-resource-server`
- `spring-boot-starter-actuator`
- `net.logstash.logback:logstash-logback-encoder:8.0`

**Do NOT add:** `spring-boot-starter-web` — it conflicts with reactive Netty.

---

## 5. Package layout

```
hp.microservice.demo.api_gateway/
├── ApiGatewayApplication.java            (✅ exists)
├── config/
│   ├── SecurityConfig.java               (🔧 refactor — role mapper + path rules)
│   ├── RedisConfig.java                  (🆕 reactive Redis template, serializer)
│   ├── GatewayConfig.java                (🆕 optional: programmatic route bean if YAML gets too long)
│   └── IdempotencyProperties.java        (🆕 @ConfigurationProperties: TTL, key prefix)
├── filter/
│   ├── IdempotencyGuardFilterFactory.java (🆕 AbstractGatewayFilterFactory)
│   └── MerchantIdExtractor.java           (🆕 small helper: pulls merchant id from JWT)
├── model/
│   └── IdempotencyRecord.java             (🆕 record: status, httpStatus, body, createdAt)
└── exception/
    └── GatewayExceptionHandler.java       (🆕 WebExceptionHandler for consistent error JSON)
```

**Resources:**
```
src/main/resources/
├── application.yml          (🔧 rewrite — payment gateway routes, payments realm)
└── logback-spring.xml       (✅ exists — verify JSON output layout)
```

---

## 6. `application.yml` — target shape

```yaml
server:
  port: 8080

spring:
  application:
    name: api-gateway

  threads:
    virtual:
      enabled: true

  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI:http://keycloak.localhost:8090/realms/payments}
          jwk-set-uri: ${KEYCLOAK_ISSUER_URI:http://keycloak.localhost:8090/realms/payments}/protocol/openid-connect/certs

  data:
    redis:
      host: ${REDIS_HOST:redis-master.data.svc.cluster.local}
      port: ${REDIS_PORT:6379}
      timeout: 2s

  cloud:
    gateway:
      default-filters:
        - TokenRelay=
      routes:
        - id: transaction-service
          uri: ${TRANSACTION_SERVICE_URL:http://transaction-service.app.svc.cluster.local:8080}
          predicates:
            - Path=/api/payments/**
          filters:
            - name: IdempotencyGuard
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 50
                redis-rate-limiter.burstCapacity: 100
                key-resolver: "#{@merchantKeyResolver}"

idempotency:
  ttl: 24h
  key-prefix: "idempotency"

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always

logging:
  config: classpath:logback-spring.xml
```

---

## 7. Design decisions

### 7.1 JWT issuer URL

The `iss` claim baked into a JWT depends on **which hostname was used to request the token**. We saw this earlier:

- Token obtained via `http://localhost:9091/...` → `iss = http://localhost:9091/realms/payments`
- Token obtained via `http://keycloak.localhost:8090/...` → `iss = http://keycloak.localhost:8090/realms/payments`
- Token obtained via `http://keycloak.auth.svc.cluster.local/...` (in-cluster DNS) → different again

**Decision:** All services (gateway + downstream) use **`http://keycloak.localhost:8090/realms/payments`** as the canonical issuer. Inside the cluster, we add a `hostAliases` entry to each service's Deployment pod spec that points `keycloak.localhost` at the Keycloak Service ClusterIP, so in-cluster JWKS fetches still work without leaving the cluster.

_(Alternative considered and rejected: set Keycloak's `KC_HOSTNAME` to an in-cluster DNS name like `keycloak.auth.svc.cluster.local`. Rejected because it would break browser-based admin access.)_

### 7.2 Role extraction from JWT

Keycloak puts realm roles under `realm_access.roles` (we verified this in Phase 3). Spring Security doesn't map this by default.

**Decision:** In `SecurityConfig`, register a `ReactiveJwtAuthenticationConverter` with a custom `Converter<Jwt, Collection<GrantedAuthority>>` that reads `realm_access.roles` and prefixes each with `ROLE_`. Then `hasRole("MERCHANT")` works out of the box.

### 7.3 Idempotency Guard state machine

```
(no key)
  │  request arrives with Idempotency-Key
  ▼
IN_FLIGHT  ──┐
  │          │ duplicate arrives while in flight → 409 Conflict
  │          ▼
  │       (caller retries)
  │
  │  downstream returns
  ▼
COMPLETED (body + status cached)
  │  duplicate arrives → return cached (200/202 + stored body)
  ▼
(expires after TTL, 24h)
```

**Redis key shape:**
```
Key:   idempotency:{merchantId}:{idempotencyKey}
Value: JSON { status, httpStatus, headers, body, bodyHash, createdAt }
TTL:   24h
```

**Atomicity:** Use `SET ... NX EX 86400` to set the `IN_FLIGHT` marker (with the `bodyHash` already included). Only one request gets through.

**Body hash check (locked — MVP):**
- On each request, compute `bodyHash = SHA-256(requestBody)` before the SETNX.
- On a duplicate: compare incoming `bodyHash` against the stored one.
  - Match → return the cached response (replay).
  - Mismatch → **422 Unprocessable Entity** with a clear error ("idempotency key reused with different request body").
- Reading the body in a reactive pipeline requires caching it — we'll use a `ServerWebExchangeDecorator` that buffers the body on first read and re-emits it downstream (the standard Spring Cloud Gateway pattern for body-mutation/inspection filters).

### 7.4 Rate limiter key

Per-merchant, extracted from JWT `preferred_username` (from `azp` claim → `payments-gateway`, from `sub` claim → user UUID, from `preferred_username` → `acme-merchant`). Using `preferred_username` is most human-readable.

Bean name `merchantKeyResolver` — a `KeyResolver` `@Bean` that reads the JWT from the reactive `SecurityContext`.

### 7.5 Error response shape

Consistent JSON for all gateway-level errors (401, 403, 429, 409, 502):

```json
{
  "timestamp": "2026-04-22T07:39:43Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Missing or invalid Bearer token",
  "path": "/api/payments"
}
```

Implemented via a `WebExceptionHandler` with order < 0 (so it runs before Spring's default).

---

## 8. `Dockerfile`

```dockerfile
# syntax=docker/dockerfile:1.6

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
COPY proto proto
COPY services/api-gateway services/api-gateway
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :services:api-gateway:bootJar -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/services/api-gateway/build/libs/*.jar app.jar
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=25"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
```

**Note:** The build stage pulls from the repo root (multi-module), so the Docker build context must be the repo root (not the service dir). The corresponding local build command:

```bash
docker build -t micro/api-gateway:dev -f services/api-gateway/Dockerfile .
k3d image import micro/api-gateway:dev -c micro
```

---

## 9. Kubernetes manifests — `deploy/apps/api-gateway/`

### 9.1 `configmap.yaml`
Non-secret config: Keycloak issuer URI, Redis host, transaction-service URL, log level.

### 9.2 `secret.yaml` (optional)
Nothing secret at the gateway level yet — Redis is unauthenticated in dev. Reserved slot if we add API keys later.

### 9.3 `deployment.yaml`
Key points:
- `replicas: 1` (dev; HA is out of scope per OVERVIEW.md §8)
- Container image `micro/api-gateway:dev`, `imagePullPolicy: IfNotPresent`
- `resources`:
  ```yaml
  requests: { memory: 384Mi, cpu: 100m }
  limits:   { memory: 512Mi, cpu: 1000m }
  ```
- `env`:
  - `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=25`
  - `KEYCLOAK_ISSUER_URI` from ConfigMap
  - `REDIS_HOST` from ConfigMap
  - `TRANSACTION_SERVICE_URL` from ConfigMap
- `hostAliases`:
  ```yaml
  - ip: "<Keycloak Service ClusterIP>"   # templated or resolved once
    hostnames: ["keycloak.localhost"]
  ```
  _Alternatively, use a headless Service + init container that resolves it — simpler path is hardcoding in ConfigMap and templating at deploy time._
- `livenessProbe` / `readinessProbe` → `/actuator/health/liveness` and `/actuator/health/readiness`

### 9.4 `service.yaml`
ClusterIP, port 8080 → targetPort 8080.

### 9.5 `ingressroute.yaml` (Traefik IngressRoute, not k8s Ingress)
Route host `localhost` (or a dedicated `api.localhost`) to `api-gateway:8080`. Example:
```yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: api-gateway
  namespace: app
spec:
  entryPoints: [web]
  routes:
    - match: PathPrefix(`/api`)
      kind: Rule
      services:
        - name: api-gateway
          port: 8080
```
So from the host: `curl http://localhost:8090/api/payments -H "Authorization: ..."` → Traefik → api-gateway.

---

## 10. Testing strategy

### 10.1 Unit tests
- `IdempotencyGuardFilterFactory` — mocked `ReactiveRedisTemplate`, verify:
  - Missing header → pass-through
  - First request → sets `IN_FLIGHT` then `COMPLETED` after downstream
  - Duplicate → returns cached body/status

### 10.2 Integration test (`@SpringBootTest`)
- Use `embedded-redis` (or Testcontainers with a tiny Redis image) + Spring Security test dsl to mint a JWT.
- Cases:
  1. No token → 401
  2. Valid token, no `MERCHANT` role → 403
  3. Valid token + `MERCHANT` role, no `Idempotency-Key` → 400
  4. Valid token + role + fresh key → forwarded (use a `WireMock` backend)
  5. Valid token + role + duplicate key + **same body** → returns cached response, backend not hit
  6. Valid token + role + duplicate key + **different body** → 422 (body hash mismatch)
  7. Rate limiter: 101st request from the same merchant within 1s → 429

### 10.3 Smoke test (per SKELETON_GUIDE.md §5.7)
After deploy to k3d:
```bash
# 1. get token
TOKEN=$(curl ... /token | jq -r .access_token)

# 2. unauthenticated → 401
curl -i http://localhost:8090/api/payments

# 3. authenticated → reaches transaction-service
curl -i http://localhost:8090/api/payments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{...}'

# 4. duplicate key → same response, no downstream hit (check transaction-service logs)
```

---

## 11. Open questions

_All resolved — see §0 (Locked decisions)._

---

## 12. Implementation order (when we start coding)

1. Update `build.gradle` (add Redis reactive + validation + micrometer prometheus)
2. Rewrite `application.yml` (payment gateway routes, payments realm, Redis, virtual threads)
3. Refactor `SecurityConfig` (role mapper + `/api/payments/**` requires MERCHANT)
4. Add `RedisConfig` + `IdempotencyProperties`
5. Add `IdempotencyRecord` model
6. Add `IdempotencyGuardFilterFactory`
7. Add `merchantKeyResolver` bean (for rate limiter)
8. Add `GatewayExceptionHandler`
9. Add unit tests
10. Add integration test (embedded Redis + mock JWT)
11. Add `Dockerfile`
12. Add k8s manifests under `deploy/apps/api-gateway/`
13. Build image, `k3d image import`, deploy, smoke test per §10.3
