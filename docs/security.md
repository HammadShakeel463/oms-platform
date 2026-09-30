# Security

The decision and its alternatives are in
[ADR 0007](adr/0007-asymmetric-jwt-verified-at-every-service.md). This document is the practical
view: how to use it, what enforces what, and what is deliberately not covered.

---

## 1. The shape of it

```
                 POST /auth/token
   client ─────────────────────────────▶ api-gateway ──┐
          ◀───────── access token (RS256) ─────────────┘ holds the PRIVATE key
                                                         (only component that can ISSUE)
   client ── Authorization: Bearer <token> ──▶ api-gateway
                                                  │ verifies signature, issuer, audience, expiry
                                                  │ enforces coarse route rules by role
                                                  │ rate-limits by account
                                                  │ forwards the header UNCHANGED
                                                  ▼
                                    order-service / market-data / position / matching-engine
                                                  │ verifies the SAME token again
                                                  │ enforces its own path rules
                                                  │ derives the account from the CLAIM
                                                  ▼
                                    GET /oauth2/jwks  ◀── public key only, cached 5 min
```

**Two independent checks, not one.** The gateway validating is convenience; the service validating
is the security boundary. See §5.

---

## 2. Getting a token

```bash
curl -s -X POST http://localhost:8080/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"trader1","password":"trader1-password"}'
```

```json
{
  "accessToken": "eyJraWQiOiI4YmM...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "accountId": "ACC-TRADER-1",
  "roles": ["TRADER"],
  "expiresAt": "2026-09-30T09:30:00Z"
}
```

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/token \
  -H 'Content-Type: application/json' \
  -d '{"username":"trader1","password":"trader1-password"}' | jq -r .accessToken)

curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/positions
```

`GET /auth/me` decodes whatever token you send, which is the fastest way to see why a permission
check is failing.

### Demo users

Created at startup **only when no users are configured**, with a loud warning in the log. Populate
`oms.auth.users[]` in anything that is not a laptop.

| Username | Password | Account | Roles |
|---|---|---|---|
| `trader1` | `trader1-password` | ACC-TRADER-1 | TRADER |
| `trader2` | `trader2-password` | ACC-TRADER-2 | TRADER |
| `mm1` | `mm1-password` | ACC-MM-1 | TRADER |
| `riskuser` | `risk-password` | ACC-TRADER-1 | RISK |
| `admin` | `admin-password` | ACC-TRADER-1 | ADMIN, RISK |

Passwords are BCrypt-hashed at startup and never stored in plaintext. Comparison is constant-time,
**including for an unknown username** — returning early would make "no such user" measurably faster
than "wrong password", which is a username-enumeration oracle.

---

## 3. The token

```json
{
  "sub": "trader1",
  "account_id": "ACC-TRADER-1",
  "roles": ["TRADER"],
  "name": "Demo Trader One",
  "iss": "oms-gateway",
  "aud": ["oms-platform"],
  "iat": 1790000000, "nbf": 1790000000, "exp": 1790000900,
  "jti": "a3f1..."
}
```

Three details worth being able to defend:

**`sub` and `account_id` are separate.** `sub` is *who is calling*; `account_id` is *whose money is
at stake*. They are one-to-one for the demo users, but conflating them makes it impossible to let one
user act for several accounts, or to have an operations user act for none. `sub` is what lands in the
`actor` column of the order audit trail — the difference between "ACC-TRADER-1 cancelled this" and
"hammad cancelled this on behalf of ACC-TRADER-1".

**Roles are bare names.** The `ROLE_` prefix is applied by `OmsJwtAuthenticationConverter` and
nowhere else. Spring's `hasRole("TRADER")` prepends it while `hasAuthority("TRADER")` does not, and
having the prefix in the token as well is how a project ends up with `ROLE_ROLE_TRADER`.

**`exp` is 15 minutes, and that is the revocation window.** See §7.

---

## 4. Who can do what

| Path | Method | TRADER | RISK | ADMIN | Anonymous |
|---|---|:-:|:-:|:-:|:-:|
| `/auth/token` | POST | ✅ | ✅ | ✅ | ✅ |
| `/oauth2/jwks` | GET | ✅ | ✅ | ✅ | ✅ |
| `/actuator/health`, `/info` | GET | ✅ | ✅ | ✅ | ✅ |
| `/actuator/**` (metrics, prometheus) | GET | ❌ | ❌ | ✅ | ❌ |
| `/api/v1/orders/**` | POST, DELETE | ✅ | ❌ | ❌ | ❌ |
| `/api/v1/orders/**` | GET | ✅ | ✅ | ✅ | ❌ |
| `/api/v1/positions/**`, `/api/v1/pnl` | GET | ✅ | ✅ | ✅ | ❌ |
| `/api/v1/instruments/**`, `/api/v1/quotes/**` | GET | ✅ | ✅ | ✅ | ❌ |
| `/api/v1/books/**` | GET | ❌ | ❌ | ✅ | ❌ |

**`RISK` cannot trade.** A risk officer who can also place orders is not a control — separation of
duties is the whole reason the role exists as something other than a label.

**Metrics are not public.** `/actuator/prometheus` carries order rates, book depth and per-account
activity. An unauthenticated metrics endpoint is a slow information leak that nobody notices.

**Book depth is ADMIN only.** Full depth reveals every resting order, its size and its price — for a
market maker, the position they are trying not to broadcast. A client that wants prices subscribes to
market data, which publishes aggregated top-of-book.

### The distinction that matters most

**The role decides whether an endpoint may be called. The account claim decides which rows come
back.**

`TRADER` is enough to call `GET /api/v1/positions`, but the rows returned are scoped to the
`account_id` in the token — not to a parameter, and not to a header. Getting only the first half right
is the classic broken-object-level-authorisation bug: every trader authorised, every trader able to
read everyone else's book. Every query in order-service and position-service is parameterised by the
claim, and
`PositionControllerTest.rowsAreScopedToTheTokenAccount` pins it by sending a *spoofed* header
alongside a valid token and asserting the header is ignored.

---

## 5. Why every service validates too

The gateway already checked the token. Each service checks it again, and derives the account from the
claim rather than from a header the gateway could have injected.

The alternative — validate once at the edge, trust `X-Account-Id` downstream — is the most common
microservice authentication design and has the worst failure mode. Anything that can reach a service
directly can set that header to any account:

- a debug or management port left open
- a misapplied `NetworkPolicy`
- a compromised sidecar in the same pod
- somebody's `kubectl port-forward`

Re-validating costs a **cached** JWKS lookup and a signature check. What it buys is that a network
misconfiguration stays a network misconfiguration instead of becoming a total authorisation bypass.
**The security boundary is the service, not the network topology.**

Phases 2 to 4 read `X-Account-Id` because nothing was authenticated yet, and it was flagged as
temporary at the time. The swap to `@AccountId` was a one-line change per endpoint because the header
was read in exactly one place per service — which was the point of putting it there.

---

## 6. Validation is more than a signature check

A valid signature only proves the token was minted by the holder of the private key. Shared across
all services via `ResourceServerSupport.tokenValidator()`:

| Check | Why it is there |
|---|---|
| Expiry / not-before, **±30s skew** | Without skew, two hosts a second apart reject each other's freshly minted tokens — intermittent 401s that correlate with nothing and are very hard to diagnose |
| Issuer | A token from a different issuer is not ours |
| **Audience** | A token *this* issuer minted for a **different system** must not work here |

The audience check is the one most often omitted, and it is what stops a token intended for an
unrelated integration being replayed against this platform.

`alg` is asserted to be RS256 in `TokenIssuerTest`. `alg: none` is the classic JWT forgery — a token
with no signature that a lenient verifier accepts — and asserting the algorithm is cheap insurance
against ever emitting one.

---

## 7. Rate limiting

Redis token bucket at the gateway, keyed by **authenticated account**.

| Route | Sustained | Burst |
|---|---|---|
| `/api/v1/orders/**` | 20/s | 40 |
| `/api/v1/positions/**`, `/pnl` | 50/s | 100 |
| `/api/v1/instruments/**`, `/quotes/**` | 100/s | 200 |
| `/api/v1/books/**` | 20/s | 40 |

Orders get the tightest limit because an order is a write that reaches PostgreSQL and Kafka — a
runaway client there does real damage, unlike a runaway reader that only wastes CPU.

Why account and not IP: a whole office behind one NAT would share a bucket, while an attacker with a
/24 would get 256 of them. The account comes from a signed claim, so the caller cannot change its own
key. Redis rather than in-memory counters because three gateway replicas with local counters give
each client three times the limit — and the limit then changes whenever the deployment is scaled.

The token endpoint is keyed by remote address, because there is no principal yet. That is a weak key
and it is acknowledged as one: it raises the cost of credential stuffing from a single machine and no
more. It is paired with a far tighter limit, because each attempt costs a deliberately slow BCrypt
comparison.

---

## 8. What is deliberately not covered

Stated here rather than left to be discovered.

**No revocation.** A stateless JWT is valid until it expires, so **the 15-minute expiry *is* the
revocation window**. A `jti` deny-list in Redis would close it for the cases that matter — a sacked
employee, a leaked token — and is the natural next step. It is not built.

**Tokens do not survive a gateway restart.** The signing key is generated at startup, because no
private key is committed to this repository — a private key in version control is a real finding, not
a style issue. In production the key is mounted from a secret, or the issuer is replaced entirely
(see below).

**The user store is a config file.** Passwords are hashed and compared safely, but it is not an
identity provider. Because the gateway is already a standard OAuth2 resource server pointing at a
JWKS URI, swapping in Keycloak, Auth0 or Cognito means changing one URI and deleting four classes —
`AuthController`, `UserStore`, `TokenIssuer`, `SigningKeys`. **The four services need no change at
all.** That is why the design uses standard mechanisms rather than something bespoke.

**Kafka is not authenticated.** The matching engine's real input arrives over Kafka, not HTTP, and
this chapter's filter chains do nothing about it. Broker-level SASL plus topic ACLs are the control
there, and they belong with the deployment configuration in Phase 6.

**No mTLS between services.** It solves a different problem — authenticating the *service* rather
than the *user* — and both are wanted eventually. It is service-mesh configuration rather than
application code.

**Authorisation rules are duplicated** between the gateway and each service. That duplication *is*
the defence in depth, but it is two places to keep in step, and a rule tightened at the gateway and
forgotten in a service is a real risk. Mitigated by keeping the rules in one file per service and by
slice tests that assert the matrix.

---

## 9. Tests

| Test | What it establishes |
|---|---|
| `TokenIssuerTest` | RS256 not HS256, verifies with the **public** key only, `kid` present for rotation, claim set correct, roles unprefixed, JWK set contains no private exponent, a token from another key does not verify |
| `UserStoreTest` | Hashed passwords, indistinguishable failure modes, and that an unknown username still costs a hash comparison |
| `GatewayAuthorizationTest` | The full matrix through the real filter chain: public endpoints, forged tokens, garbage tokens, RISK cannot trade, book depth is ADMIN-only, metrics are not public |
| `RateLimitConfigTest` | Keyed by account; two callers from one address get separate buckets; unauthenticated falls back to address |
| `OrderControllerTest` | 401 vs 403 distinction, and that a **spoofed `X-Account-Id` header is ignored** in favour of the claim |
| `PositionControllerTest` | Rows scoped to the claim, RISK read-only, unknown role is 403 |

What is *not* tested here is signature verification itself — that is Spring Security's code, tested
by Spring Security. The tests target what happens after a token is accepted, which is where this
project's decisions live.

---

## 10. Summary for an interview

1. **RS256, not a shared secret**, because with a symmetric key every service that can verify a token
   can also mint one — a compromised read-only service becomes a token factory.
2. **Every service validates independently.** Trusting a gateway-injected header means one network
   misconfiguration is a total authorisation bypass. The security boundary is the service, not the
   topology.
3. **Validation is expiry with skew, issuer *and audience*.** The audience check is the one people
   leave out, and it is what stops a token minted for another system being replayed here.
4. **The role authorises the endpoint; the claim scopes the rows.** Getting only the first half right
   is how every trader ends up able to read every other trader's book.
5. **There is no revocation, and the 15-minute expiry is the revocation window.** A `jti` deny-list is
   the next step, and it is not built — which is a better answer than pretending the problem does not
   exist.
