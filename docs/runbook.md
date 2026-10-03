# Runbook

How to build, test, run and exercise this platform, in the order that gets you the most
confidence per minute.

Commands are given for Windows `cmd.exe` (`mvnw.cmd`). On Linux or macOS use `./mvnw`; everything
else is identical.

> **What has and has not been executed.** Stages 1, 2 and 4 are verified. Stages 3 and 5 need a
> Docker daemon, and have never completed on the authoring machine. Stage 3 reaches the point of
> starting containers and stops there, which is as far as this machine can get — see
> [deployment.md §7](deployment.md#7-what-is-not-yet-verified) and
> [testing.md §5](testing.md#5-what-has-not-been-executed). The quickest way to verify them
> without installing anything locally is [stage 8](#stage-8--let-ci-do-it).

---

## Stage 0 — Prerequisites

| Need | Required for | Notes |
|---|---|---|
| JDK 21 or newer | everything | Maven bootstraps itself through the committed wrapper. Verified on JDK 25 compiling to `--release 21` |
| Docker Desktop with the WSL2 backend | stages 3 and 5 | Integration tests, Compose, image builds |

Nothing else. No local Maven install, no local PostgreSQL, Kafka or Redis.

---

## Stage 1 — Build and unit tests

```
mvnw.cmd clean install -DskipTests
```

```
mvnw.cmd test
```

**Expect:** `BUILD SUCCESS`, **518 tests**, 0 failures, roughly two minutes.

**The `install` first is not optional.** `oms-common`, the `oms-web` auto-configuration and the
`oms-web` *test-jar* (which carries the shared `TestJwt` fixtures) have to be in the local
repository before the five services can compile against them. Running `mvnw.cmd test -pl
order-service` on a clean machine fails for this reason, and the error — a missing artifact — does
not obviously point at the cause.

---

## Stage 2 — The coverage gate

```
mvnw.cmd verify -DskipITs -Pcoverage-gate
```

**Expect:** `BUILD SUCCESS`. The gate is 70% line coverage **per module**, and it passes on the
unit suite alone, so it does not depend on stage 3. Reports: `<module>/target/site/jacoco/index.html`.

Why this is worth running rather than trusting: when it was first run, it failed. See
[testing.md §6](testing.md#6-what-this-pass-found).

---

## Stage 3 — Integration tests

**The highest-value thing you can run against this repository.** Five Testcontainers tests that
start real PostgreSQL, Kafka and Redis.

Install Docker Desktop, enable the WSL2 backend, wait for it to settle, then confirm the daemon
is actually reachable:

```
docker run --rm hello-world
```

```
mvnw.cmd verify
```

**Expect:** 518 unit tests plus 5 integration tests. Five to eight minutes on a first run, which
pulls `postgres:16-alpine`, `apache/kafka:3.8.1` and `redis:7-alpine`.

> These tests were unrunnable until the first CI run exposed why: Failsafe was resolving its
> classes directory to the Boot-repackaged jar, so discovery failed on the service's own DTO
> before a container was ever started. Fixed in the parent POM; the story is in
> [testing.md §7](testing.md#7-what-ci-found-that-no-local-build-could), and it is a good
> illustration of why "written" is not "verified".

### What only these tests can establish

Everything below is invisible to a mocked test, because a mock cannot be wrong in the way a real
dependency can:

- **Flyway migrations actually apply** to PostgreSQL 16 — including the two append-only triggers
  and the CHECK constraints. A constraint with a logic error is simply absent from a unit test.
- **The audit trail is really append-only.** The `reject_audit_mutation()` trigger is a database
  object; nothing in the Java suite can confirm it fires.
- **`SELECT ... FOR UPDATE SKIP LOCKED`** behaves as claimed when two pollers claim concurrently.
- **Kafka retry and DLT policy** — which is the `config/KafkaConfig` the coverage gate deliberately
  excludes, precisely because its behaviour belongs here rather than in a unit test.
- **Redis TTL expiry.**

### If one fails

Run it alone. `-Dit.test` is Failsafe's filter, not Surefire's `-Dtest`:

```
mvnw.cmd verify -pl order-service -Dit.test=OrderPersistenceIT
```

The two to suspect first are `MarketDataFlowIT` and `PositionFlowIT`, and the likely cause is
container startup timing rather than application logic — a Kafka container that is listening but
has not finished electing a controller looks exactly like a consumer that never receives anything.

---

## Stage 4 — Benchmarks

```
mvnw.cmd -pl matching-bench -am package -DskipTests
```

```
java -jar matching-bench/target/benchmarks.jar QuotePullBenchmark -f 2 -prof gc
```

Roughly four minutes, and it reproduces the headline result in
[performance.md](performance.md): the tuned book stays flat across a tenfold increase in depth
while the baseline loses 78% of its throughput. The whole matrix, without the filter, is about
eleven minutes.

```
java -jar matching-bench/target/benchmarks.jar -f 2 -prof gc -rf json -rff jmh-result.json
```

**Close everything else first.** This is a workstation, not an isolated host: a browser occupying
a core moves the tail percentiles far more than any code change in the book. The figures currently
in `performance.md` were taken under `--release 17`, before [ADR 0006](adr/0006-target-java-21-not-17.md);
re-taking them on 21 is a legitimate open task.

---

## Stage 5 — The whole platform

```
docker compose up --build
```

First build is five to ten minutes. Compose waits on health checks, so the services start after
PostgreSQL, Kafka and Redis are actually ready rather than merely running.

```
curl http://localhost:8080/actuator/health
```

| What | Where |
|---|---|
| API and Swagger UI (all five services aggregated) | http://localhost:8080/swagger-ui.html |
| Grafana (`admin` / `admin`) | http://localhost:3000 |
| Prometheus | http://localhost:9090 |
| order-service · matching-engine · market-data · position | :8081 · :8082 · :8083 · :8084 |

### Predicted failure points, in order of likelihood

This path has never been executed, so this list is a genuine prediction rather than a
troubleshooting history.

1. **Kafka KRaft listener configuration.** The `apache/kafka` image is strict about
   `KAFKA_LISTENERS`, `KAFKA_ADVERTISED_LISTENERS` and `KAFKA_CONTROLLER_LISTENER_NAMES` agreeing
   with each other. A mismatch shows up as the broker exiting during startup.
2. **Topic creation.** `ops/create-topics.sh` assumes a path to `kafka-topics.sh` inside the image.
   ```
   docker compose logs kafka-init
   ```
3. **Line endings on that script.** `.gitattributes` keeps it LF, but a `bad interpreter: no such
   file or directory` means it arrived with CRLF.
4. **Grafana panels showing "No data"** — a metric-name mismatch, not a broken service. Check the
   real name against the service:
   ```
   curl -s http://localhost:8081/actuator/prometheus | findstr oms_
   ```

```
docker compose down -v
```

Removes the volumes too, which is what you want between attempts — a half-initialised PostgreSQL
volume will otherwise be reused and the schema bootstrap will not re-run.

---

## Stage 6 — Exercise the domain

This is the sequence worth rehearsing, because it demonstrates the parts of the project that are
not generic CRUD.

### Demo identities

Created at startup with a deliberately loud warning in the log. Accounts match the ones Flyway
seeds into order-service.

| Username | Password | Account | Roles | Max order notional | Max position |
|---|---|---|---|---|---|
| `trader1` | `trader1-password` | `ACC-TRADER-1` | TRADER | 5,000,000 | 100,000 |
| `trader2` | `trader2-password` | `ACC-TRADER-2` | TRADER | 5,000,000 | 100,000 |
| `mm1` | `mm1-password` | `ACC-MM-1` | TRADER | 50,000,000 | 1,000,000 |
| `riskuser` | `risk-password` | `ACC-TRADER-1` | RISK | — (cannot trade) | — |
| `admin` | `admin-password` | `ACC-TRADER-1` | ADMIN, RISK | — | — |

### Instruments

| Symbol | Reference | Price band | Status |
|---|---|---|---|
| `HBL` | 172.4500 | ±10% | ACTIVE |
| `OGDC` | 205.9000 | ±10% | ACTIVE |
| `LUCK` | 915.7500 | ±7.5% | ACTIVE |
| `ENGRO` | 318.2000 | ±10% | ACTIVE |
| `PSO`, `MCB` | 182.60, 264.30 | ±10% | ACTIVE |
| `TRG`, `SYS` | 58.75, 412.90 | ±15%, ±12.5% | ACTIVE |
| `PIAA` | 12.4000 | ±10% | **HALTED** |
| `DEAD` | 50.0000 | ±10% | **DELISTED** |

`PIAA` and `DEAD` exist to make the `INSTRUMENT_TRADEABLE` rejection path demonstrable.

### 1. Get a token

```
curl -s -X POST http://localhost:8080/auth/token -H "Content-Type: application/json" -d "{\"username\":\"trader1\",\"password\":\"trader1-password\"}"
```

```
set TOKEN=paste-the-accessToken-value-here
```

### 2. Make a trade happen

A trade needs two sides, and the aggressor must cross a resting order. Get a second token for
`mm1`, rest a sell with it, then buy into it as `trader1`.

```
curl -s -X POST http://localhost:8080/api/v1/orders -H "Authorization: Bearer %MM_TOKEN%" -H "Content-Type: application/json" -d "{\"clientOrderId\":\"mm-1\",\"symbol\":\"HBL\",\"side\":\"SELL\",\"orderType\":\"LIMIT\",\"timeInForce\":\"DAY\",\"limitPrice\":172.4500,\"quantity\":1000}"
```

```
curl -s -X POST http://localhost:8080/api/v1/orders -H "Authorization: Bearer %TOKEN%" -H "Content-Type: application/json" -d "{\"clientOrderId\":\"c-1\",\"symbol\":\"HBL\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"DAY\",\"limitPrice\":172.5000,\"quantity\":1000}"
```

Both return `201` immediately. **The fill arrives afterwards, over Kafka** — that is the design,
not a delay to apologise for. Poll until the status moves:

```
curl -s http://localhost:8080/api/v1/orders -H "Authorization: Bearer %TOKEN%"
```

```
curl -s http://localhost:8080/api/v1/positions -H "Authorization: Bearer %TOKEN%"
```

```
curl -s http://localhost:8080/api/v1/pnl -H "Authorization: Bearer %TOKEN%"
```

### 3. The five things actually worth showing

**The audit trail.** Every transition, with its reason, actor and trace id:

```
curl -s http://localhost:8080/api/v1/orders/<orderId>/audit -H "Authorization: Bearer %TOKEN%"
```

Then try to tamper with it, and watch the database refuse:

```
docker compose exec postgres psql -U oms_order -d oms -c "UPDATE oms_order.order_audit SET reason='edited' WHERE seq=1;"
```

**A risk rejection is a `201`, not a `400`.** 50,000 HBL is about 8.6m against a 5m limit:

```
curl -s -X POST http://localhost:8080/api/v1/orders -H "Authorization: Bearer %TOKEN%" -H "Content-Type: application/json" -d "{\"clientOrderId\":\"c-big\",\"symbol\":\"HBL\",\"side\":\"BUY\",\"orderType\":\"LIMIT\",\"timeInForce\":\"DAY\",\"limitPrice\":172.5000,\"quantity\":50000}"
```

The order **exists**, with status `REJECTED` and an audit row naming `MAX_ORDER_VALUE`. A rejected
order the client cannot retrieve afterwards is a support ticket, which is why this is not a 400.
A limit of `250.0000` on HBL instead trips `PRICE_BAND`, and any order on `PIAA` trips
`INSTRUMENT_TRADEABLE`.

**401 and 403 are different answers.** Book depth is ADMIN-only, because a full order book is
other participants' resting intent:

```
curl -s -o nul -w "%%{http_code}\n" http://localhost:8080/api/v1/books/HBL -H "Authorization: Bearer %TOKEN%"
```

`403` with the trader token, `200` with an `admin` token, `401` with no token at all.

**Separation of duties.** `riskuser` can read every position and place no order — a `POST` with
that token is `403`. A risk user who can trade is not a risk control.

**The account cannot be spoofed.** Add a header claiming to be someone else:

```
curl -s http://localhost:8080/api/v1/positions -H "Authorization: Bearer %TOKEN%" -H "X-Account-Id: ACC-MM-1"
```

It is ignored. The account comes from a signature-verified claim, and phases 2–4 of this project
used exactly that header until [ADR 0007](adr/0007-asymmetric-jwt-verified-at-every-service.md)
replaced it.

**The streaming quote feed**, which conflates rather than queueing under backpressure:

```
curl -N http://localhost:8080/api/v1/quotes/stream?symbols=HBL,ENGRO -H "Authorization: Bearer %TOKEN%"
```

---

## Stage 7 — Observability

```
curl -s http://localhost:8081/actuator/prometheus | findstr oms_
```

The **OMS Platform** dashboard is provisioned into Grafana from the repository, so it is present
without anyone clicking it together. The top row answers "is the platform healthy"; the rows below
answer "why not".

The one worth looking at deliberately: place an order, then find it in Tempo. **One order should be
one trace**, spanning the gateway, order-service, Kafka, matching-engine and position-service. Five
unrelated traces instead means the two `observation-enabled` flags did not take, and connecting an
order to the fill it produced is exactly the question you want to ask when a fill looks wrong.

Worth knowing: `/actuator/prometheus` requires the `ADMIN` role through the gateway. It carries
order rates, book depth and per-account activity, and an unauthenticated metrics endpoint is a slow
information leak that nobody notices.

---

## Stage 8 — Let CI do it

The GitHub Actions workflow runs on every push to `main`, on a runner that **has a Docker daemon**.
That makes it the cheapest way to verify the parts of this runbook that need Docker, without
installing anything.

```
gh run watch
```

```
gh run view --log-failed
```

Four jobs, and what each one actually proves:

| Job | Proves |
|---|---|
| Unit tests | the 518 tests pass on a clean machine, which is a stronger claim than passing on the machine that wrote them |
| Integration tests (Testcontainers) | **stage 3**, plus the 70% coverage gate |
| Manifest and script checks | `docker compose config` parses, `kubeconform -strict` accepts all 11 Kubernetes manifests, `shellcheck` passes on `create-topics.sh` |
| Build and publish images | the Dockerfile builds for all five services, and Trivy reports HIGH/CRITICAL CVEs |

A green run on the integration-tests job is the single strongest signal available that this
platform works end to end, because that job is the only thing in the project that exercises
PostgreSQL, Kafka and Redis together.

---

## Priority order

1. **Watch CI.** It is already running, it costs nothing, and it covers stage 3.
2. **Install Docker and run `mvnw.cmd verify`** locally — and you need Docker for stage 5 anyway.
3. **`docker compose up --build`** — this is the demo.
4. **Stage 6** — the walkthrough to rehearse before an interview.
5. **Stage 4** — re-take the benchmark figures on `--release 21`.
