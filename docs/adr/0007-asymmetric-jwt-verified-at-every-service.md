# ADR 0007 — Asymmetric JWT, verified at the gateway *and* at every service

- **Status:** Accepted
- **Date:** 2026-09-30
- **Phase:** 5

## Context

Phases 2 to 4 read the caller's trading account from an `X-Account-Id` header. That was acceptable
only because nothing was authenticated: the header is client-supplied, so any caller could claim any
account. Phase 5 has to replace it, and two decisions have to be made together.

**Which signing scheme.** A shared HMAC secret (HS256) or a public/private key pair (RS256).

**Where validation happens.** Only at the gateway, with services trusting a header it injects — or
at the gateway *and* independently at every service.

## Decision

### 1. RS256 with a public/private key pair, published as a JWK set

The gateway holds the private key and is the only component that can **issue** a token. The other
four services fetch the public half from `GET /oauth2/jwks` and can only **verify**.

The argument against a shared secret is one sentence: **with a symmetric key, every service that can
verify a token can also mint one.** Four services would each hold the secret, and a read-only
service compromised through an unrelated bug becomes a token factory able to impersonate any account
with any role. RS256 splits the capability — the same principle as not giving a read replica the
write credentials.

Publishing the public half at a JWKS endpoint rather than configuring it into each service makes
rotation a gateway-side operation: publish both keys, wait for caches to pick up the new `kid`,
retire the old. With a shared secret, rotation is a coordinated restart of everything.

### 2. Every service validates independently

The gateway verifies the token and enforces coarse route rules. It forwards the `Authorization`
header unchanged, and **each service validates the same token again** and derives the account from
the claim rather than from any header.

The tempting alternative — validate once at the edge, inject a trusted `X-Account-Id` — is one
network misconfiguration away from total authorisation bypass. Anything that can reach a service
directly (a debug port, a misapplied NetworkPolicy, a compromised sidecar, a developer's
`kubectl port-forward`) can set that header to any account. Re-validating costs a cached JWKS lookup
and a signature check, and it means **the security boundary is the service, not the network
topology**.

Concretely, in `oms-web`:

- `@AccountId` + `AccountIdArgumentResolver` — injects the account from the verified claim. A
  missing claim is a 401, not a null passed downstream, because a null account would query "all
  orders for account null" and the interesting question would be whether that returns nothing or
  everything.
- `OmsJwtAuthenticationConverter` — maps the `roles` claim to `ROLE_*` authorities. Shared, so four
  services cannot derive authorities differently.
- `ResourceServerSupport.tokenValidator()` — expiry with 30s skew, issuer, **and audience**.

### 3. Validation is more than a signature check

A valid signature only proves the token was minted by the private-key holder. The validator also
enforces:

| Check | Why |
|---|---|
| Expiry / not-before, ±30s skew | Without skew, two hosts a second apart reject each other's fresh tokens — intermittent 401s that correlate with nothing |
| Issuer | A token from a different issuer is not ours |
| **Audience** | A token *this* issuer minted for a **different system** must not work here |

The audience check is the one most often omitted, and it is the one that stops a token intended for
an unrelated service being replayed against this platform.

### 4. Roles as capabilities, with separation of duties

| Role | May | May not |
|---|---|---|
| `TRADER` | Place and cancel orders, read own orders and positions | Read book depth or metrics |
| `RISK` | Read orders, positions and P&L | **Trade** |
| `ADMIN` | Operational endpoints, book depth, metrics | — |

`RISK` is read-only by design. A risk officer who can also place orders is not a control, and that
is the whole reason the role exists as something other than a label.

Note carefully what the role does *not* decide: **the role decides whether an endpoint may be
called; the account claim decides which rows come back.** Getting only the first half right —
"TRADER may read positions" without scoping the query to the caller's account — is the classic
broken-object-level-authorisation bug. Every query in position-service and order-service is
parameterised by the account from the token.

### 5. Rate limiting keyed by account

Redis token bucket, keyed by the authenticated account, falling back to remote address for the
unauthenticated token endpoint.

| Key | Problem |
|---|---|
| Global | One busy client throttles everyone; a shared limit is a shared outage |
| Client IP | A whole office behind one NAT shares a bucket, while an attacker with a /24 gets 256 |
| **Account** | The unit that corresponds to a user, and the caller cannot change it — it comes from a signed claim |

Redis rather than in-memory counters because the limit must hold across gateway replicas: three
instances with local counters give each client three times the limit, and the limit then changes
whenever the deployment is scaled.

## Alternatives considered

**Shared HMAC secret (HS256).** Rejected: see above. Simpler and strictly worse for a multi-service
platform.

**Opaque tokens with introspection.** The gateway issues a random string and every service calls an
introspection endpoint to resolve it. Genuinely better in one respect — **revocation is immediate**,
because the authority is consulted per request. Rejected because it puts a synchronous call to a
single service on every request in the platform: that service becomes a hard dependency for all
traffic and a latency floor for the order path. A JWT is verified locally with a cached public key.
The cost is stated plainly in Consequences.

**Gateway-only validation with trusted headers.** Rejected: see above. This is the most common
microservice authentication design and the one whose failure mode is worst.

**mTLS between services instead of token propagation.** Solves a different problem — it authenticates
the *service*, not the *user*. Both are wanted eventually; mTLS is service-mesh configuration rather
than application code, so it belongs with the Kubernetes work and is not in this ADR.

**A real identity provider (Keycloak, Auth0, Cognito).** The right production answer, and the first
thing to replace here. The gateway's `/auth/token` + `UserStore` exist so the platform is runnable
and demonstrable without provisioning an IdP. Because the gateway is already a standard OAuth2
resource server pointing at a JWKS URI, swapping in an IdP means changing one URI and deleting
`AuthController`, `UserStore`, `TokenIssuer` and `SigningKeys` — the four services need no change at
all. That is not an accident; it is why the design uses the standard mechanisms rather than something
bespoke.

## Consequences

**Good**

- A compromised read-only service cannot mint tokens.
- A network misconfiguration is a network misconfiguration, not an authorisation bypass.
- Key rotation does not require touching four services.
- Verification is local: no per-request call to an auth service, no latency floor, no single point of
  failure for all traffic.
- Replacing the embedded issuer with a real IdP is a configuration change.

**Costs, accepted**

- **No revocation.** A stateless JWT is valid until it expires, so **the expiry is the revocation
  window**. 15 minutes is the trade between that window and how often a client re-authenticates. A
  deny-list of `jti` values in Redis would close it for the cases that matter (a sacked employee, a
  leaked token) and is the natural next step; it is not built, and pretending otherwise would be the
  wrong answer to an interview question about revocation.
- **Tokens do not survive a gateway restart.** The signing key is generated at startup, because **no
  private key is committed to this repository** — a private key in version control is a real finding,
  not a style issue. For production the key is mounted from a secret or the issuer is replaced. The
  cost is that a local restart means logging in again.
- **Authorisation rules are duplicated** between the gateway and each service. Deliberate — that
  duplication *is* the defence in depth — but it is two places to keep in step, and a rule tightened
  at the gateway and forgotten in the service is a real risk. Mitigated by the rules living in one
  file per service and by slice tests that assert the matrix.
- **The demo user store is a config file.** Passwords are BCrypt-hashed and comparison is
  constant-time, including for unknown usernames (so there is no enumeration oracle), but it is still
  not an identity provider.
- **Kafka is not authenticated.** The engine's real input arrives over Kafka, and broker-level SASL
  plus topic ACLs are the control there. That belongs with the deployment configuration in Phase 6.
  Recorded here so the gap is explicit rather than implied to be covered by this ADR.

## Notes for the C++ reader

The interesting part has no real C++ analogue, because it is a trust-boundary decision rather than a
language one — but two things map directly.

**A JWT is a signed, self-describing struct passed by value.** Verification is a local computation
over bytes you already hold, which is why it scales: no lock, no shared state, no round trip. The
cost of that locality is the one every cache pays — you cannot invalidate what you have already
handed out. Short expiry is the same trade as a short TTL.

**Public-key verification is capability separation, expressed cryptographically.** The principle is
the one behind dropping privileges after `bind()`: hold the powerful credential in one place, for as
short a time as possible, and give everything else the weakest capability that still does the job.
`HS256` is the design where every process runs as root because it was easier to configure.
