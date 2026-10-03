# Testing

518 tests, 81.9% line coverage, a 70% per-module gate enforced by CI — and an honest account of
which parts of the suite have actually been executed.

```bash
./mvnw test                      # 518 unit tests, no Docker needed
./mvnw verify                    # adds the 5 Testcontainers integration tests
./mvnw verify -Pcoverage-gate    # what CI runs: fails below 70% line coverage per module
```

---

## 1. Where the suite stands

| Module | Line coverage | What the tests are mostly about |
|---|---|---|
| `oms-web` | **98.0%** | the shared error contract, trace filter, roles converter, token validator, auto-configuration conditions |
| `oms-common` | **96.3%** | the wire contract: event round-trips, partition keys, fixed-point money, the error codes |
| `api-gateway` | **94.1%** | the authorisation matrix, token issuance, rate-limit key derivation |
| `matching-engine` | **81.8%** | the order book contract, invariants, snapshot publication under concurrency |
| `market-data-service` | **78.7%** | the tick simulator, conflation, the cache's failure behaviour |
| `position-service` | **75.9%** | average-cost P&L, crossing zero, mark-to-market |
| `order-service` | **75.7%** | risk suite, state machine, outbox, idempotent fill application |
| **aggregate** | **81.9%** | |

**Coverage is a floor, not evidence.** It measures which lines ran, not whether anything was
asserted about them. The tests worth pointing at are the ones that encode a property rather than
an execution path:

| Test | What it establishes |
|---|---|
| `BookInvariantTest` | 20,000 random operations × 4 seeds: the book never crosses, and `submitted = 2 × traded + cancelled + resting` holds exactly |
| `SnapshotPublicationConcurrencyTest` | one writer at full speed plus four readers for 1.5 s: no torn snapshot, no crossed book, no blocking |
| `LimitOrderBookContractTest` | 27 cases run against **both** book implementations — a faster book that fails the contract is not faster, it is wrong |
| `TradeIdsTest` | a replayed stream reproduces byte-identical trade ids, which is the whole basis of recovery |
| `PositionEntityTest` | the crossing-through-zero case, where a sell larger than the long position closes it and opens a short in one fill |
| `ErrorContractTest` | every `ErrorCode` maps to a 4xx/5xx, and exactly two codes are 5xx faults |
| `OmsWebAutoConfigurationTest` | the library still loads when its optional dependencies are *absent* — tested with `FilteredClassLoader` |

---

## 2. The test pyramid, by kind

| Kind | Count | Needs | Runs in |
|---|---|---|---|
| Unit (domain, services with mocks) | ~380 | nothing | milliseconds |
| Slice (`@WebMvcTest` + real filter chain) | ~70 | a Spring context, no infrastructure | ~1 s each |
| Property / concurrency / contract | ~60 | nothing | up to 1.5 s |
| Architecture (ArchUnit) | 4 suites | nothing | ~3 s |
| **Integration (Testcontainers)** | **5** | **a Docker daemon** | **~30 s each** |

**Slice tests import the service's real `SecurityConfig`.** A `@WebMvcTest` that mocks security
away tests a filter chain that does not ship; these import `OmsWebAutoConfiguration`,
`OmsSecurityAutoConfiguration` and the service's own chain, so the 401/403 behaviour under test is
the behaviour that deploys. `jwt()` from `spring-security-test` is the only seam: signature
verification is Spring Security's code, and what is worth testing here is what happens *after* a
token is accepted — which role reaches which endpoint, and whether the account really comes from
the claim rather than a header.

**ArchUnit tests encode the boundaries that ADR 0001 asserts.** `oms-common` holds no `@Entity`
and no Spring Boot dependency; a shared contract module that gains an entity is how five services
quietly become one distributed monolith with five deployment pipelines.

---

## 3. What the coverage gate excludes, and why

An excluded class is removed from the numerator *and* the denominator, so an exclusion is a claim
that measuring something would make the number less informative. There are exactly two:

| Excluded | Reason |
|---|---|
| `**/*Application.class` | One line, `SpringApplication.run`. Covering it means starting a context, and the context-load tests exist on their own merit. |
| `**/config/KafkaConfig*` | Producer and consumer factory wiring. The bodies are lambdas inside container customisers that only execute on message dispatch, so the most a unit test can reach is "a factory returns a factory". The retry and DLT behaviour these classes configure is asserted behaviourally by the integration tests. |

**What is deliberately *not* excluded**, although excluding it would have been easy and would have
raised the number: every `SecurityConfig`, every listener, every DTO, every record, and
`NaiveOrderBook`. Filter-chain rules are behaviour and are tested through MockMvc. A DTO with no
test is a DTO whose shape nobody has checked. And `NaiveOrderBook` — the deliberately un-tuned
baseline kept so [performance.md](performance.md) is checkable rather than asserted — is held to
the same 27-case contract test as the tuned book, so excluding it would have *lowered* the
measured coverage by removing well-tested code.

---

## 4. The gate is per module, not aggregated

Each module must reach 70% **on its own tests**. The alternative — one aggregated number — hides
exactly the problem this project had: `oms-web` sat at **0%** while being exercised constantly by
four other modules' tests, because per-module JaCoCo cannot attribute cross-module execution. An
aggregate report would have shown a comfortable total and concealed the fact that the shared error
contract, the trace filter, the roles converter and the token validator — code every service
depends on — had no tests of their own.

That was a real finding, not a hypothetical: see [§6](#6-what-this-pass-found).

---

## 5. What has *not* been executed

> The 5 Testcontainers integration tests have **never run**, because there is no Docker daemon on
> the machine this was authored on.

They are written, they compile, and they are wired into CI
([ci.yml](../.github/workflows/ci.yml) `integration-tests`), and that is the whole of the claim.
[deployment.md §7](deployment.md#7-what-is-not-yet-verified) lists this with everything else
unverified, in priority order. `./mvnw verify` with Docker installed is the single
highest-value thing left to run against this repository.

What they would add, which the unit suite cannot:

- **Flyway migrations actually applying** to a real PostgreSQL 16, including the append-only
  triggers and the CHECK constraints — a constraint that is wrong is invisible to a mocked test.
- **The outbox round trip** through a real broker, and the `FOR UPDATE SKIP LOCKED` claim under
  two concurrent pollers.
- **Retry and DLT behaviour**, which is the `KafkaConfig` the coverage gate excludes.
- **Redis cache semantics** including TTL expiry.

Because the gate passes on the unit suite alone, the integration tests being unrunnable here
does not weaken the gate — but it does mean the database and broker layers are verified by
*design review*, not by execution, and this document says so rather than letting a coverage
percentage imply otherwise.

---

## 6. What this pass found

Adding these tests was not a coverage exercise; it surfaced three real defects in the build:

1. **The coverage gate did not pass.** The README and the JD mapping both claimed ">70%, enforced
   by CI". Running `./mvnw verify -Pcoverage-gate` failed on the first module. CI would have gone
   red on its first execution — and the claim was being made in the documentation in the meantime.
2. **`oms-web` had no tests at all**, at 0% of 610 lines, despite holding the error contract and
   the security plumbing for four services. It looked covered because downstream modules exercised
   it; per-module measurement is what exposed that.
3. **`ResourceServerSupport` renders `ApiError` with the injected `ObjectMapper`.** A bare
   `new ObjectMapper()` cannot serialise the `Instant` on that record, so with a wrongly configured
   mapper a 401 would throw while being rendered and surface as a 500 with no body. Production is
   safe because the injected mapper is Boot's, with JSR-310 registered — but the test now builds
   its mapper the same way Boot does, rather than passing a bare one and testing a configuration
   the platform never uses.

Two of my own tests were also wrong before they were right, and both were wrong in the same
direction — asserting something the environment could not exercise:

- An SSE test called `emitter.complete()` and expected the subscription to unregister. Outside a
  servlet container nothing dispatches `onCompletion`, so it was asserting a path only an
  integration test can reach. It now closes the subscription and asserts the `finally` in the
  delivery loop, which is the backstop behind all three callbacks and the thing that actually
  prevents the leak.
- A validation test asserted `fieldErrors.length() == 0`. `@JsonInclude(NON_EMPTY)` omits the
  field entirely, so the correct assertion — and the correct statement of the contract — is that
  a client checks for *presence*, not length.

---

## 7. What CI found that no local build could

The first CI run after publishing the repository failed, and it failed on something no local
command had ever been able to reveal: **the integration tests could not start at all.**

```
[ERROR] TestEngine with ID 'junit-jupiter' failed to discover tests
```

Not one test ran. The real cause was only in `order-service/target/failsafe-reports/*.dump`:

```
Caused by: java.lang.NoClassDefFoundError: com/oms/order/api/dto/PlaceOrderRequest
```

The integration tests could not see their own module's main classes.

**Why.** Failsafe binds after `package`, and `package` is where Spring Boot's `repackage` goal
rewrites `order-service.jar` into an executable jar with the application's classes moved under
`BOOT-INF/classes/`. Failsafe's default `classesDirectory` resolves to that jar, where `com.oms.*`
is no longer at the root — so discovery dies on the service's own DTO, before any container is
started and before any assertion is reached.

The fix is one line in the parent POM, with the reasoning kept beside it:

```xml
<classesDirectory>${project.build.outputDirectory}</classesDirectory>
```

**Three things about this are worth saying out loud.**

It was **invisible to every local build**, because every local build skipped the integration tests
— there is no Docker daemon here, so `-DskipITs` was always on, and `skipITs` skips the
`integration-test` goal entirely, discovery included. The defect lived in the gap between what
could be run and what was claimed.

It is **not a Docker problem**, which is what the symptom looked like. Discovery happens before
Testcontainers is touched. Once the POM was fixed, the same `./mvnw verify` on this machine reached
`Running com.oms.order.it.OrderFlowIT` and failed with `Could not find a valid Docker environment`
— the correct failure, and the proof that discovery was the real issue. All five ITs now resolve.

And **`matching-engine` was never affected, by accident.** Its `repackage` uses
`<classifier>app</classifier>` so that `matching-bench` can read the plain jar — a change made in
phase 3 for an unrelated reason, which left its main artifact untouched and its ITs discoverable.
One module silently working for a reason that had nothing to do with testing is exactly why the
other three went unnoticed.

The honest summary: **the suite was documented as "written but not executed", and the word
"written" was carrying more weight than it had earned.** A test that cannot be discovered is not a
test that has not run yet; it is a test that would never have run. CI on a machine with a Docker
daemon is what turned that distinction from a footnote into a red build.
