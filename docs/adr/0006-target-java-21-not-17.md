# ADR 0006 — Target Java 21, not Java 17

- **Status:** Accepted
- **Date:** 2026-09-29
- **Phase:** 4
- **Supersedes:** the `maven.compiler.release=17` decision made implicitly in Phase 1

## Context

The project brief asked for **Java 17**, and separately asked for **records, sealed interfaces,
pattern matching and virtual threads**. Phases 1–3 were built with `maven.compiler.release=17`.

That requirement list cannot be satisfied. Two of the four named features are not Java 17:

| Feature | Final in | Status in 17 |
|---|---|---|
| Records | 16 | ✅ final |
| Sealed classes and interfaces | 17 | ✅ final |
| Pattern matching for `instanceof` | 16 | ✅ final |
| **Pattern matching for `switch`** | **21** | ⚠️ preview (JEP 406/420/427/433) |
| **Virtual threads** | **21** | ❌ not present |

Phase 2 worked around the virtual-thread gap by injecting a Spring `AsyncTaskExecutor` instead of
calling `Thread.ofVirtual()`, so no Java 21 API appeared in the source. That was a genuine
workaround, and it had a real limitation: the feature only materialised if the *runtime* happened
to be 21+, which meant the headline "virtual threads" claim was conditional on something the build
did not enforce.

Pattern matching for `switch` has no equivalent workaround. It is syntax, and
`--release 17` rejects it:

```
patterns in switch statements are not supported in -source 17
  (use -source 21 or higher to enable patterns in switch statements)
```

The alternative under 17 is a chain of `instanceof` patterns ending in a `default` branch — which
compiles, and which discards **the reason the sealed hierarchy exists**: exhaustiveness checking.
A sealed interface whose consumers cannot be compiler-verified against it is a comment.

## Decision

Set `maven.compiler.release=21` for the whole platform. Use no language or API feature newer
than 21.

Three supporting points:

1. **21 is a strict superset of 17.** No JD requirement is lost. Everything claimed for Java 17 —
   records, sealed interfaces, `instanceof` patterns, `EnumMap`/`EnumSet`, text blocks — is still
   exactly what the code uses and still what a "Java 17" JD is asking about.
2. **21 is the current LTS** (September 2023), and its support window runs well past the life of
   this project. A JD asking for "Java 17" in 2026 is asking for *modern Java rather than 8*, not
   for 17 specifically.
3. **It makes the virtual-thread claim unconditional.** The `AsyncTaskExecutor` injection stays,
   because a configurable threading model is worth having on its own merits, but the feature is now
   guaranteed by the build rather than dependent on the deployer's JDK.

The documentation is corrected rather than quietly adjusted: `docs/jd-mapping.md` now states, per
feature, whether it is a 17 feature or a 21 feature.

## Alternatives considered

**Stay on 17 and use `instanceof` chains.** Keeps the literal "Java 17" claim. Rejected because it
throws away compile-time exhaustiveness over the sealed hierarchy — the property that makes adding
a seventh event type a compile error in every consumer rather than a silent no-op. That property is
worth more in an interview than the version number, and it is worth more in the code than either.

**Stay on 17 and raise only the modules that need 21.** Considered seriously, and it is defensible:
`market-data-service` needs virtual threads, `position-service` needs switch patterns, the rest
genuinely compiles on 17. Rejected because a multi-module build with two language levels is a
maintenance trap — a class moved from one module to another can stop compiling for reasons unrelated
to the change, and the shared modules would have to stay at the lowest level for ever. The
complexity buys a version number on a CV.

**Stay on 17 and drop the two features.** The most honest reading of "Java 17" as a hard
constraint. Rejected because the brief names both features explicitly, and the whole point of the
project is to demonstrate them.

## Consequences

**Good**

- Pattern matching for `switch` over the sealed `DomainEvent` hierarchy, with compiler-checked
  exhaustiveness and no `default` branch. Used in `PositionEventListener`: adding a seventh event
  type to `oms-common` makes that class fail to compile until somebody decides what it should do.
- Virtual threads are guaranteed rather than conditional.
- `Math.clamp`, sequenced collections and the rest of the 21 standard library are available, though
  nothing currently depends on them.

**Costs, accepted**

- **A deployment target running JDK 17 cannot run this.** Stated plainly rather than discovered:
  the Docker images in Phase 6 pin a 21 base image, and the CI matrix builds on 21.
- The literal "Java 17" box on a JD is now answered with "21, which is 17 plus the two features
  your own requirement list asks for". That is a stronger answer than a matching number, but it is
  an answer that has to be *given* — it is not self-evident from the POM.
- Phases 1–3 were written and tested under `--release 17` and now compile under 21. Nothing in them
  used a feature that changed meaning, and the full suite passes unchanged, but the earlier
  `--release 17` claim in their commit messages is now historical rather than current.

## Notes for the C++ reader

This is the `-std=c++17` versus `-std=c++20` conversation, with one difference that matters: the
JVM's compatibility guarantee runs the other way round from a C++ ABI. Bytecode targeting 21 runs
unchanged on 22, 23, 25 and beyond — the runtime reads the class-file version and behaves — so
raising the target has no forward cost. What it does is set a **floor** on the deployment runtime,
and that floor is the whole content of this decision.

The preview-feature mechanism is also worth knowing about, because it has no C++ analogue. A
preview feature in Java is fully implemented and deliberately unusable without
`--enable-preview`, and — crucially — class files compiled with preview features refuse to load on
any *other* JDK version, even a newer one. That is the opposite of a compiler flag you can quietly
leave on: it is designed to be impossible to ship by accident. It is why "pattern matching for
switch exists in 17" is technically true and practically false.
