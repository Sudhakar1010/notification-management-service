# Architecture

Notification Management Service — architecture overview and Architecture
Decision Records (ADRs) covering the greenfield (Phase 1), brownfield
(Phase 2), and ambiguous-requirement (Phase 3) builds. See `README.md`
for setup/run instructions.

## 1. System Overview

A Spring Boot 4 / Java 21 service that accepts notification requests from
upstream systems, decides delivery channels per recipient, dispatches
delivery attempts asynchronously with bounded retry/backoff, and exposes
status and audit history. Persistence is H2 (in-memory); scheduling and
persistence are the only infrastructure dependencies — no external broker,
cache, or message queue. Email and SMS are simulated; WEBHOOK makes a real
outbound HTTP call (see ADR-012).

**Components:**

| Component | Package | Responsibility |
|---|---|---|
| `NotificationController` | `notification` | HTTP surface: submit, get status, get audit trail |
| `NotificationService` | `notification` | Validation, idempotency check, orchestrates routing + queuing |
| `NotificationStatusCalculator` | `notification` | Pure function deriving overall status from delivery attempts |
| `RoutingService` | `routing` | Decides eligible channels per recipient |
| `DeliveryQueue` / `DeliveryAttemptQueue` | `delivery` | Port + adapter for enqueuing a delivery attempt |
| `DeliveryWorker` | `delivery` | `@Scheduled` poller — the async processing loop |
| `DeliveryAttemptProcessor` | `delivery` | Processes one delivery attempt per transaction, including retry/backoff decisions |
| `RetryBackoffPolicy` | `delivery` | Pure function: attempt count → exponential backoff delay |
| `ChannelProviderRegistry` + `EmailChannelProvider` / `SmsChannelProvider` / `WebhookChannelProvider` | `delivery` (+ `.providers`) | Channel adapters — Email/SMS simulated, Webhook real HTTP |
| `SimulatedFailureRules` | `delivery.providers` | Shared marker-based outcome rules for the simulated providers |
| `WebhookSinkController` | `delivery` | Local, non-public stand-in webhook receiver for deterministic demos/tests (see ADR-012) |
| `AuditService` | `audit` | Records audit trail events |

## 2. Control Flow

```mermaid
flowchart TB
    Client([Client])

    subgraph Request thread
        Controller[NotificationController]
        NS[NotificationService]
        RS[RoutingService]
        DQ[DeliveryQueue port]
        AS1[AuditService]
    end

    subgraph "Async worker (scheduled, separate thread)"
        DW["DeliveryWorker (poll every 2s)"]
        DAP[DeliveryAttemptProcessor]
        RBP[RetryBackoffPolicy]
        CPR[ChannelProviderRegistry]
        EP[EmailChannelProvider]
        SP[SmsChannelProvider]
        WP["WebhookChannelProvider (real HTTP)"]
        AS2[AuditService]
    end

    Sink["WebhookSinkController (local, demo/test only)"]

    DB[(H2: notification, notification_recipient,
    delivery_attempt, audit_event,
    recipient_preference)]

    Client -->|"POST /notifications"| Controller --> NS
    NS -->|"1 idempotency check + save"| DB
    NS -->|"2 decide channels"| RS --> DB
    NS -->|"3 enqueue per recipient x channel"| DQ --> DB
    NS -->|"4 record events"| AS1 --> DB
    Controller -->|"202 Accepted"| Client

    DW -->|"poll QUEUED / RETRY_SCHEDULED"| DB
    DW --> DAP
    DAP -->|"claim row (optimistic lock)"| DB
    DAP --> CPR --> EP & SP & WP
    WP -.->|"real HTTP POST (demo/test target)"| Sink
    DAP -->|"retryable failure: schedule next attempt"| RBP
    DAP -->|"record outcome"| DB
    DAP --> AS2 --> DB

    Client -->|"GET /notifications/id"| Controller
    Client -->|"GET /notifications/id/audit"| Controller
    Controller -->|"read + derive status"| DB
```

**Narrative:**
1. `POST /api/v1/notifications` validates the request, checks the
   `(sourceSystem, idempotencyKey)` dedup boundary, persists the
   `Notification` + `NotificationRecipient` rows, and records
   `NOTIFICATION_ACCEPTED`.
2. For each recipient, `RoutingService` decides eligible channels;
   `DeliveryQueue.enqueue(...)` persists one `DeliveryAttempt` row per
   recipient × channel; each step is audited (`ROUTING_DECIDED`,
   `DELIVERY_QUEUED`).
3. The request returns **202 Accepted** here — no delivery has happened yet.
   This is the asynchronous boundary: nothing past this point runs on the
   request thread.
4. `DeliveryWorker` polls for due rows every 2s (configurable), and hands
   each to `DeliveryAttemptProcessor`, which claims the row under optimistic
   locking, calls the resolved `ChannelProvider`, and records the outcome
   (`DELIVERY_ATTEMPTED` → `DELIVERY_SUCCEEDED` / `RETRY_SCHEDULED` (with
   backoff, see ADR-011) / `DELIVERY_FAILED` / `DELIVERY_EXHAUSTED`).
5. `GET /notifications/{id}` derives the overall status live from the
   `DeliveryAttempt` rows (see ADR-004) rather than trusting a
   separately-maintained flag.

## 3. Project Structure

```
com/nms/
├── common/       shared enums (Channel, Severity, Priority, statuses, FailureType)
├── notification/ aggregate root: submission + status API (+ dto/)
├── routing/      channel routing decision
├── delivery/     async delivery outbox, worker, providers (+ providers/)
├── audit/        audit trail
├── exception/    cross-cutting error handling
└── config/       scheduling + webhook HTTP client configuration
```

Packages are organized **by business capability** (`notification`,
`routing`, `delivery`, `audit`), not by technical layer
(`controller/service/repository/entity`). Full rationale in **ADR-001**
below; in short:

- A feature = a folder. Everything needed to understand "how delivery
  works" is in `delivery/`, not scattered across four parallel layer
  folders.
- Import direction is visible and reviewable: `notification` depending on
  `delivery` internals is a line you can see in a diff, not something only
  a folder-naming convention discourages.
- Each of the three assignment scenarios (greenfield, brownfield,
  ambiguous-requirement) maps to touching one package almost exclusively
  (`delivery/` for the brownfield channel+refactor work, `routing/` for the
  ambiguous routing-precedence resolution) — the structure was chosen with
  that evolution already in mind, not just for Phase 1's shape.
- Matches current Spring team guidance (see Spring Modulith) for how a
  Spring Boot application should be modularized as it grows past a toy
  size.

## 4. Architecture Decision Records

### ADR-001 — Package-by-feature module structure
**Status:** Accepted

**Context:** Need a package layout supporting a growing set of concerns
(intake, routing, async delivery, audit) across three build phases without
churning shared folders on every change.

**Decision:** Organize by business capability rather than technical layer
(see §3 above).

**Alternatives considered:**
- *Package-by-layer* (`controller/`, `service/`, `repository/`, `entity/`,
  `dto/`) — the classic Spring-tutorial layout. Rejected: a single feature
  change touches N unrelated folders, and nothing stops any controller from
  calling any repository directly — boundaries exist only by convention.
- *Multi-module Maven build* (separate JARs per capability) — rejected as
  disproportionate ceremony for a 6-hour prototype.

**Consequences:**
- \+ Feature-local reasoning; new capability = new package, not new files
  scattered across five existing folders.
- \+ Dependency direction between modules is visible in imports (enabled
  ADR-006's port to be introduced cleanly).
- \+ Scales toward a real modular monolith without a rewrite.
- − Less familiar to engineers who only know the layered-tutorial style.
- − No compiler-enforced boundaries (Spring Modulith's verification tests
  would add that; out of scope here — documented limitation).

---

### ADR-002 — DB-backed outbox + scheduled poller for async processing
**Status:** Accepted

**Context:** Requirement 4.1 needs asynchronous delivery processing;
requirement 4.4 requires that reprocessing a queued delivery not create
duplicate side effects. No message broker was present in the starter
project, and standing one up costs setup time disproportionate to a
6-hour budget.

**Decision:** Model delivery attempts as persisted `DeliveryAttempt` rows
(an outbox) with an explicit status state machine (`QUEUED → SENDING →
SUCCEEDED/FAILED/RETRY_SCHEDULED → EXHAUSTED`). A `@Scheduled` poller
(`DeliveryWorker`) finds due rows; `DeliveryAttemptProcessor` claims a row
(flips it to `SENDING`) under optimistic locking (`@Version`) before
calling the provider.

**Alternatives considered:**
- *Kafka/RabbitMQ + consumer* — most production-realistic; rejected here
  purely on time budget, documented as the natural swap-in for production
  (the `ChannelProvider`/`DeliveryQueue` seams don't change).
- *`@Async` in-memory queue* — simpler, but not durable across restarts and
  gives a materially weaker story for the "no duplicate reprocessing"
  requirement.

**Consequences:**
- \+ Zero extra infrastructure — runs on the same H2 instance.
- \+ Durable: survives an application restart because state is a row, not
  RAM.
- \+ The optimistic-lock claim step is what actually makes "no duplicate
  reprocessing" true under concurrent worker ticks, not just documented
  intent.
- \+ Every transition is already audit-loggable because it's already in the
  DB.
- − Single-node poller today; horizontal scale needs `SELECT ... FOR UPDATE
  SKIP LOCKED` or partitioning — called out as a production follow-up.
- − Polling interval (2s default) adds latency vs. a push-based broker.

---

### ADR-003 — Two-tier deduplication & idempotency boundary
**Status:** Accepted

**Context:** Requirement 4.4: a repeated submission with the same
idempotency key must not create a second logical notification; reprocessing
a queued delivery must not create uncontrolled duplicate side effects; the
boundary and retention policy must be documented.

**Decision:** Two independent unique constraints:
- **Submission boundary:** `UNIQUE(source_system, idempotency_key)` on
  `Notification`. A repeat returns the existing notification
  (`200`, `duplicate:true`) instead of erroring or silently duplicating.
- **Delivery boundary:** `UNIQUE(notification_id, recipient_id, channel)`
  on `DeliveryAttempt`, combined with the optimistic-lock claim from
  ADR-002.
- **Retention (documented, not yet automated):** rows are retained
  indefinitely in this prototype. A production deployment should TTL/archive
  idempotency keys after a bounded window (e.g. 7–30 days, matching how
  long a source system might realistically retry) — see
  `TESTING_AND_LIMITATIONS.md`.

**Alternatives considered:**
- *Global (non-scoped) idempotency key uniqueness* — rejected: two
  different upstream systems could legitimately reuse the same key value;
  scoping to `sourceSystem` is the realistic boundary.
- *Application-level cache (e.g. Caffeine) for dedup* — rejected: not
  durable across restarts, and duplicates a guarantee the database already
  gives for free via a unique index.

**Consequences:**
- \+ Both boundaries are enforced by the database itself, correct even
  under concurrent requests — not just application-level "check then act"
  logic, which would race.
- \+ Deduplication is visible in the audit trail (`NOTIFICATION_DEDUPLICATED`),
  satisfying 4.4's "must be reflected in status or audit history."
- − Retention policy is documented, not yet enforced — a known,
  intentional gap given the time budget.

---

### ADR-004 — Derived (not independently mutated) notification status
**Status:** Accepted

**Context:** Requirement 4.2 explicitly allows a custom state model "if
documented and defensible," and requires overall status, selected
channels, and per-recipient/per-channel delivery status.

**Decision:** `NotificationStatusCalculator` computes the overall
`NotificationStatus` purely from that notification's `DeliveryAttempt`
rows: any in-flight attempt → `PROCESSING`; zero deliverable units or zero
successes → `FAILED`; every unit succeeded → `DELIVERED`; a mix →
`PARTIALLY_DELIVERED`. The stored `Notification.status` field is refreshed
opportunistically from this calculation on read/write, but is never treated
as authoritative on its own.

**Alternatives considered:**
- *Imperative state machine* — manually transition `Notification.status` at
  each step. Rejected: easy for the flag to drift from the underlying
  facts (e.g. forgetting to flip to `PARTIALLY_DELIVERED` when a second
  recipient fails); a derived value structurally cannot drift.

**Consequences:**
- \+ Status can never be inconsistent with the actual delivery attempts.
- \+ The derivation rule is a single, pure, directly unit-tested function
  (`NotificationStatusCalculatorTest`).
- − Recomputed on every status read — O(recipients × channels); fine at
  prototype scale, would need a materialized projection at high read
  volume.

---

### ADR-005 — DTOs at every API boundary
**Status:** Accepted

**Decision:** `NotificationController` only ever sends/receives records
from `notification.dto` — never `Notification`, `NotificationRecipient`, or
`DeliveryAttempt` entities directly.

**Consequences:**
- \+ The wire contract is decoupled from the persistence model — a JPA
  schema change doesn't silently change the API.
- \+ Avoids Jackson serializing lazy-loaded proxies or triggering N+1
  queries from the web layer.
- \+ Request/response shape is self-documenting via Bean Validation
  annotations on the DTOs themselves.
- − Manual mapping code (no MapStruct) — acceptable at this size; would
  introduce a mapper if the DTO surface grew materially.

---

### ADR-006 — Explicit module port (`DeliveryQueue`) instead of cross-module repository access
**Status:** Accepted (refactored from the initial Phase 1 cut)

**Context:** `NotificationService` initially depended directly on
`DeliveryAttemptRepository` and constructed `DeliveryAttempt` entities to
enqueue deliveries — a write into another module's aggregate from outside
that module.

**Decision:** Introduced `DeliveryQueue` (interface, owned by `delivery/`)
exposing `enqueue(notificationId, recipientId, channel, firstAttemptAt)`;
`DeliveryAttemptQueue` implements it. `notification/` now depends only on
the interface for writes. Read-side status queries still query
`DeliveryAttemptRepository` directly — reading another module's data to
project a status view is treated as an acceptable allowance (it doesn't
mutate `delivery`'s aggregate), so no symmetric read port was introduced.

**Consequences:**
- \+ Write dependency direction is explicit and one-directional; `delivery`
  can change `DeliveryAttempt`'s internal shape without touching
  `notification`.
- \+ The module boundary is now something visible in the dependency graph,
  not just a folder-naming convention.
- − One extra interface + implementation file for a single method — small
  ceremony that only pays off as the codebase grows past prototype size.

---

### ADR-007 — Simulated channel providers with deterministic failure injection
**Status:** Accepted

**Context:** No real Email/SMS provider credentials (SES, Twilio, etc.) are
available for a take-home assignment; requirement 4.5 requires
distinguishing six failure categories (transient, permanent, invalid
recipient, rate-limit, timeout, auth error).

**Decision:** `EmailChannelProvider`/`SmsChannelProvider` are in-process
mocks whose outcome is decided by substring markers in `recipientId`
(`invalid-`, `ratelimit-`, `timeout-`, `authfail-`, `failtransient-`,
`failpermanent-`) — anything else succeeds.

**Consequences:**
- \+ Every failure path in 4.5 is reproducible on demand for demos and
  tests, with no flaky network dependency or mocking framework required.
- − Doesn't validate real integration concerns (token refresh, actual
  rate-limit headers, payload limits) — an explicit, documented prototype
  limitation. `ChannelProvider` is the seam a real integration implements.

---

### ADR-008 — Client-generated UUID primary keys
**Status:** Accepted

**Decision:** Entities set `private UUID id = UUID.randomUUID()` as a field
initializer rather than a DB-generated strategy.

**Alternatives considered:** `@GeneratedValue(strategy =
GenerationType.UUID)` (Jakarta Persistence 3.2) — works, but ties
correctness to the exact Hibernate/JPA provider version; a manually
initialized field has no such framework-version dependency and an
identical practical outcome.

**Consequences:**
- \+ Entity has real identity before the first `save()` — useful for
  audit/log correlation before a flush.
- \+ No DB round-trip needed for ID assignment; portable across dialects.
- − Random UUIDs are less index-friendly than sequential IDs at very large
  scale (B-tree page-split churn) — accepted trade-off; a production
  follow-up would consider a time-ordered ID (ULID/UUIDv7).

---

### ADR-009 — Extract shared failure-simulation logic out of the providers
**Status:** Accepted (Phase 2 — Brownfield)

**Context:** `EmailChannelProvider` and `SmsChannelProvider` each contained
an identical if/else chain matching markers in `recipientId` to a
`FailureType` — flagged in Phase 1 as deliberate duplication reserved for
this refactor, since adding a third provider (`WEBHOOK`) on top of two
already-duplicated copies would make a third.

**Decision:** Extracted the shared rules into
`SimulatedFailureRules.evaluate(recipientId, channelLabel)`; both providers
now call it instead of repeating the chain. `WebhookChannelProvider` does
**not** use it — it classifies from real HTTP outcomes instead (ADR-012),
which is the point: only genuinely duplicated *simulation* logic was
extracted, not forced into providers whose failure detection is real.

**Consequences:**
- \+ One place to add a new simulated marker/failure type instead of two.
- \+ Behavior-preserving — verified via the existing provider-backed
  integration tests before and after the extraction.
- − `SimulatedFailureRules` is prototype-only scaffolding; it has no place
  in a production build once real provider integrations replace the mocks.

---

### ADR-010 — Channel routing precedence
**Status:** Accepted (Phase 3 — Ambiguous Requirement)

**Context:** Requirement 4.3 lists requested channel, severity, recipient
preference, and routing policy as routing inputs without defining how
conflicts between them resolve. This is the requirement's genuinely
ambiguous part — the inputs are well-defined, their precedence is not.

**Interpretations considered:**
1. *Recipient preference is absolute* — an opt-out can never be overridden.
   Simplest and most privacy-respecting, but means a `CRITICAL`
   "trading system is down" alert can be silently dropped because a
   recipient opted out of SMS months ago, unrelated to this specific
   incident.
2. *Severity overrides preference for `CRITICAL`* (chosen) — mirrors how
   real incident-alerting tools (PagerDuty/Opsgenie-style escalation)
   treat informational notifications differently from P1 pages: routine
   notifications respect stated preference, but a `CRITICAL` alert reaches
   every requested channel regardless.
3. *Fully configurable routing policy* (a policy table per source system
   or notification type) — most "production-shaped," but disproportionate
   scope: it's a new entity, a new admin surface, and arguably just moves
   the ambiguity into "what does the policy config look like," rather than
   resolving it.

**Decision:** Option 2. `RoutingService.decide(...)` now takes `severity`;
when `severity == CRITICAL`, the recipient's channel opt-outs are ignored
and every requested channel is selected. Any other severity keeps the
Phase-1 behavior (opt-outs respected) unchanged. `priority` is
deliberately **not** part of this rule — 4.3 names severity as a routing
input, not priority, so the override isn't extended past what was
actually specified. The override is stated explicitly in `RoutingDecision`'s
`reason` (and therefore in the `ROUTING_DECIDED` audit event) whenever it
fires, so "we overrode this recipient's stated preference" is always a
visible, queryable fact, not a silent side effect.

This rule **is** the "routing policy" 4.3 asks for — made an explicit,
named, documented policy rather than left unaddressed.

**Consequences:**
- \+ A `CRITICAL` alert can't be silently dropped by a stale, unrelated
  opt-out — directly addresses the interpretation-1 failure mode.
- \+ The override is always audit-visible, satisfying 4.9's spirit even
  for a consent-bypassing decision.
- \+ Verified end-to-end (`RoutingPrecedenceIntegrationTest`): a `WARNING`
  notification to an opted-out recipient still skips that channel; a
  `CRITICAL` one to the same recipient still reaches it, with the audit
  trail recording why.
- − **Removes a recipient's ability to suppress even a hard opt-out once
  severity is `CRITICAL`.** This is a genuine consent/compliance trade-off,
  not a hidden one — a regulated real deployment (e.g. an SMS opt-out
  driven by a legal STOP request) would need a *separate*, non-overridable
  "hard opt-out" distinct from this preference-style opt-out, which this
  prototype does not model.
- − **`severity` is caller-supplied with no authentication on who is
  submitting it** (see "Production Readiness Backlog" below). This rule
  is only safe to rely on once the source system asserting `CRITICAL` is
  itself verified — right now, nothing stops any caller from declaring
  every notification `CRITICAL` to bypass every recipient's preferences.
  This is a real gap, deliberately not fixed as part of Phase 3, which is
  scoped to the routing-precedence question specifically.
- − Binary/global, not tiered — a real escalation-policy system (per the
  rejected option 3) would likely want graduated behavior (e.g. try the
  preferred channel first, escalate to a forced channel after N minutes)
  rather than an immediate blanket override.

---

### ADR-011 — Bounded retry with exponential backoff
**Status:** Accepted (Phase 2 — Brownfield)

**Context:** Requirement 4.5 requires a bounded retry strategy for
retryable delivery failures, distinguishing transient failures from
permanent ones. Phase 1 deliberately shipped single-shot delivery (every
failure was terminal) to keep the brownfield diff meaningful.

**Decision:** `DeliveryAttemptProcessor` now branches on
`FailureType#isRetryable()` (true for `TRANSIENT_PROVIDER_ERROR`,
`RATE_LIMITED`, `TIMEOUT`; false for `INVALID_RECIPIENT`,
`PERMANENT_PROVIDER_REJECTION`, `AUTH_ERROR`): a retryable failure with
attempts remaining reuses the *same* `DeliveryAttempt` row, moving it to
`RETRY_SCHEDULED` with `nextAttemptAt` pushed out by
`RetryBackoffPolicy.nextDelay(attemptCount)` — exponential
(`base * 2^(attemptCount-1)`, capped at `maxDelay`, both configurable). A
retryable failure with no attempts remaining moves to `EXHAUSTED`; a
non-retryable failure moves straight to `FAILED`.

**Alternatives considered:**
- *Fixed retry delay* — simpler, rejected because it doesn't back off
  under sustained provider trouble (e.g. an extended rate-limit window),
  which is exactly when backoff matters most.
- *Retry all failure types* — rejected: retrying `INVALID_RECIPIENT` or
  `PERMANENT_PROVIDER_REJECTION` can't ever succeed and just wastes worker
  cycles and delays the terminal audit signal.

**Consequences:**
- \+ Retries never create a second `DeliveryAttempt` row — the existing
  delivery-level dedup boundary (ADR-003) applies to retries automatically,
  no new mechanism needed.
- \+ `RetryBackoffPolicy` is a pure, directly unit-tested function
  (`RetryBackoffPolicyTest`); `DeliveryRetryIntegrationTest` proves the
  full loop end-to-end (2 retries, exponential delays visible in the audit
  trail, `EXHAUSTED` after `maxAttempts`).
- − `maxAttempts` (3) and backoff bounds are global configuration, not
  per-notification-severity — a `CRITICAL` alert retries on the same
  schedule as an `INFO` one. Acceptable for this prototype; a production
  system might vary retry budget by severity.

---

### ADR-012 — Webhook channel: `recipientId` as target URL, real HTTP-driven classification
**Status:** Accepted (Phase 2 — Brownfield)

**Context:** Adding a third channel needed to be a genuine multi-layer
change, not a fourth copy of the marker-based simulation (see the earlier
webhook-vs-push discussion). No real Email/SMS-style external account is
needed for a webhook — it's just an outbound HTTP call — so this channel
can be implemented for real.

**Decision:**
- For `WEBHOOK`, `recipientId` is interpreted as the **target URL**
  itself, not an address to look up — a deliberate departure from
  EMAIL/SMS's semantics, made explicit in `WebhookChannelProvider`'s
  Javadoc and the README.
- `WebhookChannelProvider` makes a real `RestClient` POST (via
  `WebhookClientConfig`'s configurable connect/read timeouts) and
  classifies the outcome from the **actual** HTTP response: `401`/`403` →
  `AUTH_ERROR`, `404`/`410` → `INVALID_RECIPIENT`, `429` → `RATE_LIMITED`,
  other `4xx` → `PERMANENT_PROVIDER_REJECTION`, `5xx` →
  `TRANSIENT_PROVIDER_ERROR`, a read/connect timeout → `TIMEOUT`.
- `WebhookSinkController` (`/internal/webhook-sink/{scenario}`) is a
  **local, non-public** stand-in receiver so this real HTTP path can be
  demoed and tested deterministically without depending on an external
  service being reachable from a grading/CI sandbox. It is explicitly
  documented (Javadoc + README) as demo/test-only — `WebhookChannelProvider`
  itself has no dependency on it and works unmodified against any real
  http(s) URL.

**Alternatives considered:**
- *Simulated webhook provider (marker-based, like Email/SMS)* — rejected:
  would add a channel with zero new engineering substance over copy-pasting
  SMS's shape (see the earlier webhook-vs-push-notification discussion for
  the full reasoning).
- *No local sink, require a real external URL for every demo/test* —
  rejected: makes the webhook path flaky/unusable offline and in CI; the
  sink keeps the *provider* real while keeping the *target* deterministic.

**Consequences:**
- \+ Genuinely exercises HTTP-status-driven failure classification, not a
  simulated stand-in for it — the most realistic channel in the system.
- \+ `WebhookChannelIntegrationTest` runs against the embedded server's
  real random port (`@SpringBootTest(webEnvironment = RANDOM_PORT)`),
  proving success, `404`→`INVALID_RECIPIENT`, `429`→retry→`EXHAUSTED`, and
  a real client-side timeout — all over an actual socket, not a mock.
- − `WebhookSinkController` is test/demo scaffolding shipped in `main`
  (not `test`) source so it's reachable at runtime for live demos; it's
  clearly marked non-public and would be deleted (or gated behind a
  profile) before any real deployment.
- − No outbound URL allow-listing/SSRF protection — acceptable for a
  prototype where the caller is a trusted upstream system, called out
  explicitly as a production hardening gap.

## 5. Production Readiness Backlog

A deliberate architecture review against production standards, done
before Phase 3, so these gaps are on record rather than discovered later.
Nothing here is fixed yet — the plan is to revisit this list once all
three scenarios are complete, and address what's still worth doing then.
Each item notes why it matters and what closing it would look like.

### Critical

| Gap | Why it matters | Close it by |
|---|---|---|
| No authentication/authorization on the API | `sourceSystem` is a self-asserted string; the dedup boundary (ADR-003), audit trail, and now the CRITICAL routing override (ADR-010) all trust it with no verification of caller identity | Authenticate the caller (API key, mTLS, or OAuth2 client-credentials per source system) and derive `sourceSystem` from the verified identity, not the request body |
| `severity` is unauthenticated and drives a consent-bypassing decision | Since ADR-010, any caller can declare `CRITICAL` and bypass every recipient's channel opt-outs — there is currently nothing stopping abuse of this | Same fix as above; the routing override should only be trusted once the caller declaring `CRITICAL` is itself verified |
| No schema migration tool (Flyway/Liquibase) | `ddl-auto: create-drop` regenerates the schema from JPA annotations on every boot — no repeatable, reviewable, versioned schema history | Introduce Flyway (or Liquibase), generate an initial baseline migration from the current entities, switch `ddl-auto` to `validate` |

### High

| Gap | Why it matters | Close it by |
|---|---|---|
| Sequential, single-threaded delivery processing | `DeliveryWorker.poll()` processes due attempts one at a time with no executor; a slow provider call (up to the configured timeout) blocks every other due attempt behind it in that tick | Dispatch each attempt via a bounded `@Async` executor (or `ThreadPoolTaskScheduler` with >1 pool size) so attempts process concurrently within a node |
| No circuit breaker on the outbound webhook call | A consistently-failing target gets hit again on every retry with no breaker to stop hammering it | Wrap `WebhookChannelProvider`'s call with Resilience4j's circuit breaker, keyed by target host |
| No observability stack | No Actuator (`/health`, `/readiness`, `/liveness`, metrics), no distributed tracing — can't follow one notification across submit → route → queue → worker → provider in a trace view | Add `spring-boot-starter-actuator`; add Micrometer Tracing / OpenTelemetry with the notification ID propagated as a trace attribute |

### Medium

| Gap | Why it matters | Close it by |
|---|---|---|
| No OpenAPI/Swagger contract | The API reference in `README.md` is hand-written and not guaranteed to match the code | Add `springdoc-openapi-starter-webmvc-ui` |
| Blanket `DEBUG` logging for the whole package | `logging.level.com.nms: DEBUG` is fine for local dev, but a production profile should default narrower so a future log line can't casually leak content | Add a `prod` profile with `INFO` (or higher) as the package default |
| No concurrency test proving the optimistic-lock claim | ADR-002/ADR-006 assert that two concurrent claims on the same `DeliveryAttempt` row can't both succeed, but no test actually exercises two threads racing on one row | Add a test that fires two concurrent `DeliveryAttemptProcessor.process()` calls at the same attempt ID and asserts exactly one succeeds |

### Low (worth naming, not planned)

- **No secrets management story** — moot today since providers are mocked/local and H2 has no real password, but a real Email/SMS/webhook credential shouldn't live in `application.yaml`.
- **No CORS/CSRF posture defined** — there's no Spring Security dependency at all, so this is "wide open by omission," not a deliberate choice; would need to be decided alongside the authentication work above.
