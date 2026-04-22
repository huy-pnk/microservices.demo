# Skeleton Build Guide — Step by Step

Follow top-to-bottom. Each phase ends with a **verification** you should pass before moving on. If a verification fails, do not proceed — fix first.

> This guide tells you *what* to do, not *what to write*. No source code or manifest contents here.

---

## Phase 0 — Prerequisites

### 0.1 Install tools
Make sure each of these is on your `PATH`:

| Tool | Purpose | Check |
|---|---|---|
| **Docker Desktop** (WSL2 backend on Windows) | container runtime for k3d, local image build | `docker version` |
| **k3d** (v5+) | spins up k3s clusters inside Docker | `k3d version` |
| **kubectl** | cluster control | `kubectl version --client` |
| **Helm** (v3+) | infra chart installs | `helm version` |
| **JDK 21** (Temurin or Corretto) | builds all Spring Boot services | `java -version` |
| **Gradle** (optional — wrapper is preferred) | build tool | `gradle -v` |
| **Git** | VCS | `git --version` |
| **curl** or **HTTPie** | smoke-test REST calls | `curl --version` |
| **grpcurl** | smoke-test gRPC services | `grpcurl -version` |
| **IntelliJ IDEA** (Community is fine) or VS Code + Java pack | IDE | — |

### 0.2 Allocate resources
Docker Desktop → Settings → Resources:
- **CPUs:** ≥ 4 (8 recommended)
- **Memory:** **18–20 GB** (ELK + Kafka + Keycloak + 6 services is heavy; 10 GB is not enough in practice — Elasticsearch will OOM first)
- **Disk:** ≥ 40 GB free

> If your host has 32 GB of RAM, allocating 18–20 GB to Docker still leaves 12+ GB for Windows + IDE + browser. Do not rely on the default 50%-of-host allocation — it's ~16 GB on a 32 GB host, which is tight once ELK is up.

### 0.3 Verification
- `docker run --rm hello-world` succeeds.
- `java -version` prints `21`.
- Docker Desktop shows healthy.

---

## Phase 1 — Repository Skeleton

### 1.1 Create the root folder
Working dir: `C:\Project\GitHub\Microservices` (already exists).

### 1.2 Create the folder tree
Create empty directories matching the layout in `OVERVIEW.md` section 6:
- `proto/`
- `services/api-gateway/`
- `services/transaction-service/`
- `services/fraud-service/`
- `services/fx-service/`
- `services/bank-connector/`
- `services/reconciliation-service/`
- `deploy/k3d/`
- `deploy/infra/postgres/`, `deploy/infra/redis/`, `deploy/infra/kafka/`, `deploy/infra/keycloak/`, `deploy/infra/elk/`
- `deploy/apps/` (one subfolder per service later)
- `scripts/`

### 1.3 Initialize Git
- `git init`
- Add a root `.gitignore` covering: Gradle caches (`.gradle/`, `build/`), IDE files (`.idea/`, `*.iml`, `.vscode/`), OS files (`Thumbs.db`, `.DS_Store`), env files (`.env`), local secrets.

### 1.4 Initialize the Gradle multi-module root
At the repo root you will create (content comes later — **not in this guide**):
- `settings.gradle` — declares all `services/*` modules + the `proto` module.
- `build.gradle` — shared conventions (Java 21 toolchain, common plugins, common dependency versions).
- `gradle.properties` — JVM args, org-level property pinning (e.g. `org.gradle.parallel=true`).
- `gradle/wrapper/` — produced by `gradle wrapper` (run once at the root).

### 1.5 Verification
- `./gradlew projects` (or `gradlew.bat projects` on Windows) lists the empty modules without errors.
- Folder structure matches `OVERVIEW.md` section 6.

---

## Phase 2 — k3d Cluster

### 2.1 Design the cluster
Decide in advance:
- **Name:** e.g. `micro`
- **Servers:** 1
- **Agents:** 1 (saves ~400 MB vs 2 agents; Filebeat still runs across both nodes as a DaemonSet — still realistic for a learning cluster)
- **Port mappings** on the loadbalancer:
  - `8080 → 80` (Traefik HTTP)
  - `8443 → 443` (Traefik HTTPS, optional)
  - `5601 → 5601` (Kibana, via port-forward later if you prefer)

### 2.2 Create the cluster config
In `deploy/k3d/` add a k3d cluster config file (YAML). It should declare the cluster name, agents, and port mappings above, and disable any components you don't want (k3s ships with Traefik by default — keep it).

### 2.3 Bring the cluster up
- `k3d cluster create --config deploy/k3d/cluster.yaml`
- `kubectl config use-context k3d-micro`

### 2.4 Create namespaces
Create these up front (one command or a single manifest):
- `auth`
- `app`
- `data`
- `observability`

### 2.5 Verification
- `kubectl get nodes` — one server + one agent, all `Ready`.
- `kubectl get pods -n kube-system` — Traefik running.
- `curl http://localhost:8080` — Traefik returns 404 (expected; no route yet).

---

## Phase 3 — Infrastructure Deployments

Order matters: Postgres first (Keycloak depends on it), then Redis, then Kafka, then Keycloak, then ELK last (heaviest).

### 3.1 Postgres (namespace: `data`)
- Install via the **Bitnami Postgres Helm chart** with a single replica, persistence enabled (small PVC).
- Create databases up front: `paymentdb`, `keycloakdb`.
  - Use the chart's `initdbScripts` value to run a small SQL snippet that creates these DBs on first boot.
- Store the admin password in a Kubernetes `Secret` named `postgres-admin`.

**Verify:** `kubectl exec` into the Postgres pod, run `psql -l`, see both DBs.

### 3.2 Redis (namespace: `data`)
- Install via the Bitnami Redis Helm chart, standalone mode, auth disabled for local dev.

**Verify:** `kubectl exec` into the Redis pod, `redis-cli PING` → `PONG`.

### 3.3 Kafka (namespace: `data`)
- Install via the Bitnami Kafka Helm chart, **KRaft mode**, single controller+broker, no ZooKeeper.
- Expose an internal headless service at `kafka.data.svc.cluster.local:9092`.
- **Heap cap:** set `KAFKA_HEAP_OPTS=-Xms512m -Xmx512m` via the chart's `extraEnvVars` value. Without this, Kafka defaults to ~1 GB heap, which is wasteful for a single-broker dev cluster.

**Verify:** `kubectl exec` into the Kafka pod, run `kafka-topics.sh --bootstrap-server localhost:9092 --list` successfully (empty list is fine).

### 3.4 Keycloak (namespace: `auth`)
- Install via the **Bitnami Keycloak Helm chart** (or the official Keycloak Operator — chart is simpler).
- Configure it to use the Postgres `keycloakdb` you created in 3.1 (point `externalDatabase.*` values at the Postgres service).
- Expose admin console through Traefik with host `keycloak.localhost` (add to your `hosts` file → `127.0.0.1`).
- Start with **dev mode** (no HTTPS requirement).
- **Heap cap:** set `JAVA_OPTS_APPEND=-Xms256m -Xmx512m` via `extraEnvVars`. Keycloak's Quarkus runtime is lighter than the old Wildfly, but the default heap target is still ~700 MB — cap it.

**Initial realm setup (via admin UI):**
1. Log in to `http://keycloak.localhost:8080` with the admin password from the chart.
2. Create realm: `payments`.
3. Create client: `payments-gateway` — confidential, standard flow + direct access grants, redirect URIs `*` (local only).
4. Create roles: `MERCHANT`, `ADMIN`.
5. Create a test merchant user (e.g. `acme-merchant`), set password, assign role `MERCHANT`.

**Verify:** obtain a token via direct-grant:
```
POST http://keycloak.localhost:8080/realms/payments/protocol/openid-connect/token
  grant_type=password
  client_id=payments-gateway
  client_secret=<from admin UI>
  username=acme-merchant
  password=<password>
```
Response contains `access_token`. Decode it on https://jwt.io — confirm `iss`, `realm_access.roles`.

### 3.5 ELK (namespace: `observability`)
- Install via **Elastic's official Helm charts** (`elastic/elasticsearch`, `elastic/kibana`, `elastic/logstash`, `elastic/filebeat`), single-node for all.
- Disable security on Elasticsearch for local dev (saves hours of config).
- Configure Filebeat as a DaemonSet, tailing `/var/log/containers/*.log`.
- Configure Logstash with a pipeline that parses JSON lines from Filebeat and writes to `logs-%{+YYYY.MM.dd}` indices.
- **Heap caps (mandatory for laptop dev):**

  | Component | Helm value | Setting |
  |---|---|---|
  | Elasticsearch | `esJavaOpts` | `-Xms512m -Xmx512m` |
  | Logstash | `logstashJavaOpts` | `-Xms256m -Xmx256m` |
  | Kibana | (no JVM — Node.js) | leave at defaults |

  Without these, Elasticsearch's chart default targets ~2 GB heap and will either OOM-kill itself in a Docker Desktop VM sized for this project or starve other pods.

- **On-demand mode:** if you're not debugging logs, scale the entire namespace to zero — saves ~2 GB. Add `scripts/elk-up.sh` and `scripts/elk-down.sh` wrapping `kubectl scale` to toggle it.

**Verify:**
- `kubectl port-forward svc/kibana 5601:5601 -n observability`
- Open `http://localhost:5601` → Discover → create data view `logs-*` → see entries from `kube-system` pods.

### 3.6 Phase 3 verification checklist
- [ ] `kubectl get pods -A` — everything `Running`.
- [ ] Kibana shows logs.
- [ ] Keycloak issues a JWT.
- [ ] Kafka pod lists topics without error.
- [ ] Redis responds to `PING`.
- [ ] Postgres lists both DBs (`paymentdb`, `keycloakdb`).

---

## Phase 4 — Shared Proto Module

Before any service, set up shared contracts.

### 4.1 Lay out the `proto/` module
- `proto/src/main/proto/` — one `.proto` file per internal service with a gRPC surface: `fraud.proto`, `fx.proto`. (transaction-service is REST-in / gRPC-out, so no server proto of its own; bank-connector and reconciliation-service have no gRPC surface.)
- `proto/build.gradle` — applies the **Protobuf Gradle plugin**, generates Java stubs for both server and client.
- Each service that needs stubs adds `proto` as a Gradle dependency.

### 4.2 Verification
- `./gradlew :proto:build` compiles and generates classes in `proto/build/generated/source/proto/`.

---

## Phase 5 — Service Scaffolding (pattern)

Repeat the pattern below **once per service**, in the order from `OVERVIEW.md` section 9.

### 5.1 Generate the Spring Boot skeleton
Use **start.spring.io** (or the IntelliJ wizard) with:
- Project: **Gradle - Groovy**
- Language: Java
- Spring Boot: latest 3.x
- Java: 21
- Packaging: Jar
- Group: `com.example.micro`
- Artifact: `<service-name>`

Dependencies per service type — pick the right set:

| Service type | Add these starters |
|---|---|
| All services | `Spring Web` (or `Reactive` for gateway), `Actuator`, `Spring Boot DevTools` (dev only), `OAuth2 Resource Server`, `Prometheus` (optional), `Validation` |
| Persistence services | `Spring Data JPA`, `PostgreSQL Driver`, `Flyway` |
| Redis users | `Spring Data Redis` |
| gRPC services | add the `grpc-spring-boot-starter` dependency manually (not on start.spring.io) |
| Kafka producers/consumers | `Spring for Apache Kafka` |
| Gateway | `Reactive Gateway` (Spring Cloud), no `Spring Web` |

Download the zip, unpack into the correct `services/<name>/` folder, **delete** the generated wrapper/settings (they conflict with the root wrapper).

### 5.2 Wire into the root build
- Add the module to the root `settings.gradle`.
- Move any service-specific versions to the root `build.gradle` convention plugin so they stay aligned across services.

### 5.3 Create the package layout (convention)
Inside each service's `src/main/java/com/example/micro/<svc>`:
- `config/` — security, Kafka, Redis, gRPC config classes
- `web/` — REST controllers (or `grpc/` for gRPC services)
- `service/` — domain services
- `repository/` — JPA repositories
- `domain/` — entities / value objects
- `event/` — Kafka event classes (shared DTOs can live in a small `common` module later if duplication hurts)

Under `src/main/resources/`:
- `application.yml`
- `logback-spring.xml` (JSON output via `logstash-logback-encoder`)
- `db/migration/` (Flyway) for persistence services

### 5.4 Containerization
- Add a `Dockerfile` to each service — multi-stage (builder stage with JDK 21 → runtime stage on `eclipse-temurin:21-jre`).
- Add a `.dockerignore`.
- **JVM memory tuning:** do **not** hard-code `-Xmx` in the image. Let Kubernetes manage memory via container limits + `JAVA_TOOL_OPTIONS` injected from the Deployment. This keeps images portable across environments.

### 5.5 Kubernetes manifests
Under `deploy/apps/<service>/` create:
- `deployment.yaml`
- `service.yaml` (ClusterIP; for gateway also an `IngressRoute` for Traefik)
- `configmap.yaml` (non-secret config)
- `secret.yaml` (DB password, client secrets — use `stringData` for readability; real secrets can come from `kubectl create secret` instead of committing)

**Every app service Deployment must include these env vars and resource limits:**

```yaml
resources:
  requests:
    memory: "384Mi"
    cpu: "100m"
  limits:
    memory: "512Mi"
    cpu: "1000m"
env:
  - name: JAVA_TOOL_OPTIONS
    value: "-XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=25"
```

And in each service's `application.yml`:

```yaml
spring:
  threads:
    virtual:
      enabled: true   # JDK 21 virtual threads — free throughput win for I/O-bound services
```

With `MaxRAMPercentage=50` against a 512 Mi limit, each service lands at ~250–300 MB RSS — six services total ~1.8 GB instead of ~3 GB with defaults.

### 5.6 Image build + load loop
Local iteration without a registry:
- `docker build -t micro/<svc>:dev services/<svc>`
- `k3d image import micro/<svc>:dev -c micro`
- `kubectl rollout restart deploy/<svc> -n app`

### 5.7 Per-service verification

| Service | Verification |
|---|---|
| bank-connector (echo) | `kafka-console-producer` a `payment.submitted` JSON event; pod log shows consumption; `kafka-console-consumer` on `payment.result` receives a SUCCESS result with the matching `transactionId` |
| transaction-service | `POST /payments` via port-forward returns 202 with a `transactionId`; transaction row exists in `paymentdb` (state `CREATED`+); `transaction_outbox` row is eventually marked dispatched; `payment.submitted` event visible in Kafka |
| fraud-service | `grpcurl -plaintext <host>:<port> fraud.FraudService/Evaluate` with a test payload returns `APPROVED` / `REVIEW` / `REJECTED` per the rule set (velocity, amount, country blocklist) |
| fx-service | `grpcurl` for a rate returns a value; second call for the same currency pair is served from Redis cache (check log or Redis `GET`) |
| bank-connector (full) | Inject a transient failure via config — event lands on `payment.retry`, is retried with backoff, and after N attempts ends on `payment.dlq`. Open the circuit → new events short-circuit to retry without hitting the simulated bank |
| reconciliation-service | Seed a transaction stuck in `PROCESSING`; scheduled job fires and resolves it (state transitions to `COMPLETED` / `FAILED`); pod log shows "reconciled N stuck transactions" |
| api-gateway | Unauthenticated request → 401; authenticated request reaches `transaction-service` with `Authorization` header relayed; duplicate `Idempotency-Key` returns the cached response without hitting downstream |

---

## Phase 6 — End-to-End Verification

Once all six services are deployed:

1. Obtain a JWT from Keycloak (see 3.4 verification).
2. Call `POST http://localhost:8080/api/payments` with `Authorization: Bearer <jwt>`, `Idempotency-Key: <uuid>`, and a JSON body `{ amount, currency, merchantId, card }`.
3. Expect **202 Accepted** with a `transactionId` (the Saga continues asynchronously after the synchronous fraud + FX calls).
4. Check:
   - **Postgres:** transaction row in `paymentdb` progresses `CREATED` → `FRAUD_CHECKED` → `PROCESSING` → `COMPLETED`; the matching `transaction_outbox` row is marked dispatched; an `audit_log` trail exists for the state transitions.
   - **Kafka:** `kafka-console-consumer` sees a `payment.submitted` event followed by a `payment.result` event on the same `transactionId` partition.
   - **bank-connector pod logs:** "simulated bank call for transactionId=…" line, plus circuit-breaker state metric on Actuator.
   - **Merchant webhook:** `transaction-service` logs the outbound settlement POST (stub the receiver locally or inspect the log line).
   - **Kibana:** filter `kubernetes.namespace: app` — see the lifecycle traversing gateway → transaction-service → fraud-service → fx-service → (Kafka `payment.submitted`) → bank-connector → (Kafka `payment.result`) → transaction-service, all correlated by `transactionId` / `correlationId` in MDC.
5. Replay the same request with the same `Idempotency-Key` — gateway returns the cached response without touching downstream services.

---

## Phase 7 — Teardown / Restart

- Stop cluster (preserves state): `k3d cluster stop micro`
- Start again: `k3d cluster start micro`
- Full wipe: `k3d cluster delete micro` (PVCs go with it; Keycloak realm and DB data will be lost — export realm to JSON first if you want to persist it).

Keep a small script under `scripts/` to re-apply infra + realm import after a wipe so rebuilds are cheap.

---

## Common Pitfalls

- **Memory pressure:** ELK alone can use 4–5 GB at chart defaults. Apply the heap caps in Phase 3.3 / 3.4 / 3.5 before first install, not after the first OOM. If Kubernetes still evicts pods, scale the observability namespace to zero when you don't need logs (`scripts/elk-down.sh`) or drop Logstash entirely (Filebeat can write to ES directly).
- **Docker Desktop memory default is a trap:** on a 32 GB host it allocates ~16 GB by default, which will not fit the full stack once ELK is up. Raise it to 18–20 GB before starting Phase 3.
- **`hosts` file:** for `keycloak.localhost` to resolve, add `127.0.0.1 keycloak.localhost` on Windows (`C:\Windows\System32\drivers\etc\hosts`).
- **Keycloak issuer mismatch:** when services validate JWTs, the `iss` claim must match the URL they use to fetch JWKS. If the gateway hits Keycloak via the in-cluster DNS name but the token was issued via `keycloak.localhost`, validation fails. Pick one hostname and stick with it.
- **Kafka networking:** Bitnami's chart uses two listeners (internal + external). Inside the cluster, services must connect to the **internal** listener name, not `localhost:9092`.
- **gRPC + Traefik:** gRPC needs HTTP/2 end-to-end. gRPC services in this project are **internal only** — do not expose them through Traefik; call them service-to-service via ClusterIP.
- **Image not updating in k3d:** forgetting `k3d image import` after a rebuild is the #1 cause of "my change didn't take effect." Script it.

---

## What's Next (after skeleton works)

- Correlate requests end-to-end with MDC `requestId` propagated across REST, gRPC, and Kafka headers.
- Add a Kibana dashboard saved object and commit it to `deploy/infra/elk/`.
- Optional: add OpenTelemetry → Jaeger for real distributed tracing.
- Optional: replace the shell-loop build step with Skaffold or Tilt for hot reloads.
