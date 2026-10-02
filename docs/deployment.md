# Deployment

Images, the one-command local stack, Kubernetes manifests and CI.

> **Verification status.** Everything in this chapter was written and statically checked but
> **has not been executed** — there is no Docker daemon on the machine it was authored on. The
> Java build is verified (`./mvnw test`, 330 tests green); the container and cluster paths are
> not. [§7](#7-what-is-not-yet-verified) lists exactly what to run first and what is most
> likely to need a fix.

---

## 1. Images

One `Dockerfile` for all five services, parameterised by `MODULE`:

```bash
docker build --build-arg MODULE=order-service --build-arg SERVICE_PORT=8081 \
             -t oms/order-service:local .
```

Five near-identical Dockerfiles would be five places for a base image to go stale and for a
security fix to be applied four times. The only thing that differs between services is which
module to build and which port it listens on, so those are build arguments.

| Decision | Why |
|---|---|
| **Multi-stage**, `maven:3.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-alpine` | The shipped image has no compiler and no `jar` tool. Smaller, and a smaller attack surface. |
| **POMs copied before source** | Dependency resolution is the slow part of a Maven build. This ordering redoes it only when a POM changes, not on every source edit. |
| **BuildKit cache mount** for `~/.m2` | Keeps the local repository warm between builds without baking a 500MB layer into the image. |
| **`-pl $MODULE -am`** | Builds only the requested module and what it depends on, so a change in market-data-service does not rebuild order-service. |
| **Tests skipped in the image build** | They run in CI, where a failure blocks the merge and the report is published. Running them here would double build time *and* make the image depend on Testcontainers reaching a Docker daemon from inside a build. |
| **Non-root (uid 10001)** | A container escape from a root process is a root process on the host. |
| **`MaxRAMPercentage=70`, not `-Xmx`** | The JVM reads the cgroup limit, so one setting stays correct whatever memory the container is given. A hard-coded `-Xmx` is wrong the moment somebody changes the Kubernetes limit, and the failure mode is an OOMKill rather than an error message. |
| **`ExitOnOutOfMemoryError`** | A JVM that has exhausted its heap will not recover. A process that limps along failing every request is worse for the platform than one that dies and gets replaced. |
| **`exec` in the entrypoint** | Without it the shell stays as PID 1, swallows `SIGTERM`, and the container must be killed after the grace period — which for order-service means abandoning in-flight orders and an undrained outbox. |

**Not done, deliberately: layered jars.** `-Djarmode=layertools extract` would split dependencies
and application classes into separate layers so a code-only change re-pushes kilobytes instead of
a ~50MB fat jar. The tool was renamed between Boot 3.2 and 3.3 (`layertools` → `tools`) and the
launcher class moved packages, and with no Docker available to test against, shipping an image
that might not start was the worse trade. The command to add it is in [§7](#7-what-is-not-yet-verified).

---

## 2. The local stack

```bash
docker compose up --build
```

Brings up PostgreSQL, Kafka (KRaft, no ZooKeeper), Redis, topic creation, all five services,
Prometheus, Tempo and Grafana. First run builds five images and takes several minutes.

| What | Where |
|---|---|
| API (the only port a client needs) | http://localhost:8080 |
| Swagger UI, all five services in one dropdown | http://localhost:8080/swagger-ui.html |
| Grafana (anonymous viewer) | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| Services, published for direct curling | 8081–8084 |

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"trader1","password":"trader1-password"}' | jq -r .accessToken)

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/quotes/HBL | jq

curl -s -X POST http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"clientOrderId":"cl-1","symbol":"HBL","side":"BUY","orderType":"LIMIT",
       "timeInForce":"DAY","limitPrice":"172.4500","quantity":1000}' | jq
```

### Details that matter

**`depends_on` uses `service_healthy`, not `service_started`.** Without it a service races the
database, Flyway fails, and the container restarts in a loop that looks like an application bug.
`kafka-init` uses `service_completed_successfully`, so no service starts before its topics exist.

**The Postgres healthcheck is `pg_isready -U postgres -d oms`, not a bare `pg_isready`.** The
bare form reports the server up before the database exists, and the services then fail their
first connection.

**Kafka auto-topic-creation is off.** A typo in a topic name should fail loudly rather than
silently create a topic with one partition that nobody monitors.

**One Postgres role per service** (`ops/postgres/01-init-schemas.sql`), each owning only its own
schema, with no cross-schema grants. That is what turns ADR 0001's "schema per service, no shared
tables" into an *enforced* boundary: order-service physically cannot read `oms_position`, so a
well-meaning cross-service join fails at the database instead of quietly creating a distributed
monolith. The absence of a `GRANT` is the mechanism.

**Redis runs with `maxmemory` and `allkeys-lru`.** Every use of Redis here is a cache or a
rate-limit bucket — losing an entry degrades latency, it does not lose data — so eviction is
strictly better than refusing writes.

**That init script runs once, on an empty data directory.** After changing it you need
`docker compose down -v`.

---

## 3. Kubernetes

```bash
kubectl apply -k deploy/k8s/base
```

`deploy/k8s/base/` — namespace and NetworkPolicies, ConfigMap, Secret, then a Deployment +
Service + PodDisruptionBudget per service, an Ingress and two HPAs.

### The decisions worth defending

**Three probes, not two.** A `startupProbe` separate from `livenessProbe` is the difference
between a slow start and a hang. A Spring context plus Flyway plus a Kafka consumer-group join
can take forty seconds on a cold node; with only a liveness probe, Kubernetes kills the pod
before it ever becomes ready and the deployment crash-loops for a reason that is not a bug.

**Readiness includes the database but deliberately not Redis.** A cold cache degrades latency; it
does not break correctness. Taking a pod out of rotation for an unavailable cache would be an
outage *caused by* a cache. The readiness group is configured per service in `application.yml`.

**`maxUnavailable: 0`.** That is what makes a rollout zero-downtime. The default of 25% drops
capacity while the new pods are still becoming ready.

**Memory limit, no CPU limit.** A CPU limit throttles the container through the CFS quota, and for
a JVM that means GC threads and the matching loop being descheduled mid-work — it presents as
latency spikes that look like application bugs. Requests give the scheduler what it needs; the
limit only adds jitter. The memory limit *is* set, because a JVM that exceeds it should be killed
rather than swap the node.

**`readOnlyRootFilesystem: true`** with `/tmp` mounted as an `emptyDir`. The JVM writes heap dumps
and perf data there, and Tomcat needs a work directory; everything else is immutable.

**`matching-engine` HPA is capped at 12.** Not a resource decision — a Kafka one. A partition is
consumed by at most one member of a consumer group, so a 13th pod sits idle. **The partition count
is the scaling ceiling of the engine**, and its scale-down stabilisation window is 900s because
every scale event triggers a rebalance, and a rebalance means replaying a partition to rebuild an
in-memory book. Thrashing here is expensive in a way that scaling a stateless service is not.

**Default-deny ingress**, then allow the gateway → services and the monitoring namespace →
actuator. This is also the manifest that makes ADR 0007 concrete: the policy is a control, and
it is a control that one misapplied label disables — which is precisely why the services do not
trust the network for authorisation and re-validate the JWT themselves.

### The Secret is a placeholder, and says so

`11-secret.yaml` uses `stringData` with development credentials, specifically so nobody mistakes
base64 for protection — `base64 -d` is the whole attack. A real cluster uses External Secrets
pulling from Vault or a cloud secret manager, or Sealed Secrets / SOPS so the encrypted form is
safe to commit. **Infrastructure manifests here are for completeness**: PostgreSQL, Kafka and
Redis in production are managed services, not StatefulSets somebody has to operate.

---

## 4. CI

`.github/workflows/ci.yml`, four jobs:

| Job | Runs | Gate |
|---|---|---|
| `unit-tests` | `mvn test` — 330 tests, no Docker | Fails in ~2 min on an obvious break |
| `integration-tests` | `mvn verify -Pcoverage-gate` — Failsafe `*IT` on Testcontainers | **70% line coverage** |
| `manifests` | `docker compose config`, `kubeconform -strict`, `shellcheck` | Catches a YAML typo in seconds |
| `publish-images` | 5-way matrix build → GHCR, then Trivy | main only, after tests pass |

**Unit tests run before integration tests** so a compile error or a broken assertion fails in two
minutes rather than after the container suite has finished.

**`concurrency: cancel-in-progress`** — pushing three fixes in a minute would otherwise queue
three full builds, two of which are already irrelevant.

**`packages: write` is scoped to the publish job**, not the workflow. The test jobs have no
business holding a token that can push an image.

**Test reports are published with `if: always()`.** The report is most useful precisely when the
step above failed; a condition that skips it on failure hides the reason.

**Trivy reports but does not fail the build** (`exit-code: 0`, `HIGH,CRITICAL`, `ignore-unfixed`).
A new CVE appearing overnight in the base image should be visible, not a blocker on an unrelated
deploy — and a scanner that reports every `LOW` trains people to ignore it.

---

## 5. Configuration

The same image runs under compose and under Kubernetes. An image that needs rebuilding to move
between environments is not a deployable artifact.

| Variable | Purpose |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `docker` locally, `docker,k8s` in the cluster |
| `DB_HOST` / `DB_PORT` / `DB_NAME` | shared, from the ConfigMap |
| `DB_USER` / `DB_PASSWORD` | **per service, from the Secret** |
| `KAFKA_BOOTSTRAP`, `REDIS_HOST`, `REDIS_PORT` | shared |
| `JWKS_URI` | where each service fetches the gateway's public keys |
| `MANAGEMENT_OTLP_TRACING_ENDPOINT` | Tempo |
| `MANAGEMENT_TRACING_SAMPLING_PROBABILITY` | `1.0` locally, `0.1` in the cluster |
| `REFERENCE_SOURCE` | `remote` in both, so the real reference-data path is exercised |

---

## 6. Graceful shutdown

`server.shutdown: graceful` in every service, with `terminationGracePeriodSeconds: 30`.

It matters most for order-service: `SIGTERM` stops new requests, lets in-flight ones finish, and
gives the outbox poller time to drain its current batch. Without it, a rolling deploy abandons
in-flight orders and leaves unpublished outbox rows for the next instance to notice — correct,
because the outbox is durable, but needlessly slow.

For matching-engine the opposite is true and worth knowing: its books are in memory, so a
shutdown always loses them, and the pod that takes over the partition rebuilds by replaying.
That is safe only because trade ids are deterministic (`TradeIds`), which is the subject of
ADR 0005.

---

## 7. What is not yet verified

Honest list, in the order to work through it.

1. **`docker compose up --build`** has never run. Most likely to need attention: the Kafka KRaft
   environment variables (the `apache/kafka` image is particular about listener configuration),
   and the `create-topics.sh` path to `kafka-topics.sh` inside that image.
2. **`./mvnw verify`** — the five Testcontainers suites still have never executed. They now
   compile against the new auth (`TestJwt` + `TestSecurityConfig`), but compiling is not passing.
   **This is the single highest-value thing to run**, and it needs only Docker, not the full stack.
3. **`kubectl apply -k deploy/k8s/base`** has never been applied. `kubeconform -strict` in CI will
   catch schema errors; it will not catch a wrong label selector or a bad DNS name.
4. **The Grafana dashboard** is hand-written JSON with metric names taken from the Micrometer
   registrations in the code. Any panel showing "No data" is a name mismatch — check against
   `curl localhost:8081/actuator/prometheus | grep oms_`.
5. **Layered jars**, once the stack runs. Replace the single `COPY app.jar` with:
   ```dockerfile
   RUN java -Djarmode=layertools -jar app.jar extract --destination extracted
   COPY --from=build /app/extracted/dependencies/ ./
   COPY --from=build /app/extracted/spring-boot-loader/ ./
   COPY --from=build /app/extracted/snapshot-dependencies/ ./
   COPY --from=build /app/extracted/application/ ./
   ENTRYPOINT ["sh","-c","exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
   ```
   Verify the launcher class name against the Boot version before trusting it.
