# Notification Management Service

A Spring Boot service that accepts notification requests from upstream
systems, routes them to the right channels per recipient, delivers them
asynchronously, and exposes status and audit history.

**Status:**
- **Phase 1 (Greenfield)** — submission, recipient/channel routing,
  asynchronous processing, delivery attempts, status retrieval,
  deduplication/idempotency, and audit history.
- **Phase 2 (Brownfield)** — bounded retry with exponential backoff, a
  third channel (`WEBHOOK`, a real outbound HTTP call, not a simulation),
  and an extracted shared component removing the duplicated
  failure-simulation logic between the Email and SMS providers.
- **Phase 3 (Ambiguous Requirement)** — resolved the undefined channel
  routing precedence between requested channel, severity, and recipient
  preference: a `CRITICAL` notification now overrides a recipient's
  channel opt-outs; anything else still respects them.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full design rationale
(ADRs), control-flow diagram, and package structure explanation.

## Tech Stack

- Java 21
- Spring Boot 4.1.1 (Spring Web MVC, Spring Data JPA, Spring Validation)
- H2 in-memory database
- Lombok
- Maven Wrapper

No external message broker, cache, or third-party provider account is
required — asynchronous delivery is implemented as a DB-backed outbox
processed by a `@Scheduled` worker (see ARCHITECTURE.md ADR-002). Email
and SMS providers are deterministic in-process simulations (ADR-007);
the webhook provider makes a real outbound HTTP call (ADR-012).

## Project Structure

```
com/nms/
├── common/       shared enums (Channel, Severity, Priority, statuses, FailureType)
├── notification/ submission + status API, aggregate root (+ dto/)
├── routing/      channel routing decision
├── delivery/     async delivery outbox, worker, mock providers (+ providers/)
├── audit/        audit trail
├── exception/    centralized error handling
└── config/       scheduling configuration
```

Packages are organized by business capability, not technical layer — see
ARCHITECTURE.md §3 / ADR-001 for the rationale.

## Prerequisites

- Java 21
- Maven (optional — the project includes the Maven Wrapper)

## Setup & Run

```bash
git clone <repository-url>
cd notification-management-service
./mvnw clean install
./mvnw spring-boot:run
```

On Windows: `mvnw.cmd clean install` / `mvnw.cmd spring-boot:run`

The app starts on `http://localhost:8080`. No environment variables or
external services are required — H2 runs in-memory and resets on restart.

## Configuration

`src/main/resources/application.yaml`:

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:notifications;DB_CLOSE_DELAY=-1
  h2:
    console:
      enabled: true
      path: /h2-console
  jpa:
    hibernate:
      ddl-auto: create-drop   # schema recreated on every startup

notification:
  delivery:
    poll-interval-ms: 2000          # DeliveryWorker poll cadence
    retry-base-delay-ms: 2000       # first retry delay for a retryable failure
    retry-max-delay-ms: 30000       # backoff cap
    webhook-connect-timeout-ms: 2000
    webhook-read-timeout-ms: 3000
```

**H2 console:** `http://localhost:8080/h2-console` — JDBC URL
`jdbc:h2:mem:notifications`, user `sa`, empty password.

## API Reference

### 1. Submit a notification

```
POST /api/v1/notifications
```

| Field | Required | Notes |
|---|---|---|
| `idempotencyKey` | yes | Dedup boundary is `(sourceSystem, idempotencyKey)` — see ADR-003 |
| `sourceSystem`, `eventId`, `notificationType` | yes | Free-text identifiers |
| `severity` | yes | `INFO` \| `WARNING` \| `CRITICAL` |
| `priority` | yes | `LOW` \| `MEDIUM` \| `HIGH` \| `URGENT` |
| `subject` | no | |
| `message` | yes | |
| `recipients` | yes | Array of `{ "recipientId": "..." }`, at least one. For `WEBHOOK`, `recipientId` is the target URL itself (see ADR-012) |
| `requestedChannels` | yes | Array of `EMAIL` \| `SMS` \| `WEBHOOK`, at least one |
| `scheduledAt`, `expiresAt` | no | ISO-8601 instants |

```bash
curl -s -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "evt-001",
    "sourceSystem": "trading-alerts",
    "eventId": "corr-123",
    "notificationType": "TRADE_FAILURE",
    "severity": "CRITICAL",
    "priority": "URGENT",
    "subject": "Trade failed",
    "message": "Order 55 failed to execute",
    "recipients": [{"recipientId": "alice@example.com"}],
    "requestedChannels": ["EMAIL", "SMS"]
  }'
```

Returns `202 Accepted` for a new notification:
```json
{"notificationId":"<uuid>","status":"ROUTED","duplicate":false,"createdAt":"..."}
```

Repeating the same `(sourceSystem, idempotencyKey)` returns `200 OK` with
`"duplicate": true` and the **same** `notificationId` — no second
notification is created (requirement 4.4).

### 2. Get notification status

```
GET /api/v1/notifications/{id}
```

Overall status is *derived* live from delivery attempts (ADR-004):
`PROCESSING` while any attempt is in flight (including `RETRY_SCHEDULED`),
`DELIVERED` if every attempt succeeded, `FAILED` if none did (this
includes attempts that exhausted their retries — see below),
`PARTIALLY_DELIVERED` for a mix.

Per-channel `status` can be `QUEUED`, `SENDING`, `SUCCEEDED`,
`RETRY_SCHEDULED` (a retryable failure — see `nextAttemptAt` — ADR-011),
`FAILED` (a non-retryable failure, terminal after one try), `EXHAUSTED`
(a retryable failure that used up all `maxAttempts`), or `SKIPPED` (the
notification expired before this attempt ran).

```bash
curl -s http://localhost:8080/api/v1/notifications/{id}
```

```json
{
  "notificationId": "...",
  "overallStatus": "PARTIALLY_DELIVERED",
  "requestedChannels": ["EMAIL", "SMS"],
  "recipients": [
    {
      "recipientId": "alice@example.com",
      "channels": [
        {"channel": "EMAIL", "status": "SUCCEEDED", "attemptCount": 1, "maxAttempts": 3,
         "lastFailureType": "NONE", "lastFailureReason": null, "sentAt": "..."}
      ]
    }
  ],
  "createdAt": "...", "updatedAt": "..."
}
```

### 3. Get audit trail

```
GET /api/v1/notifications/{id}/audit
```

Returns the ordered event log (`NOTIFICATION_ACCEPTED`, `ROUTING_DECIDED`,
`DELIVERY_QUEUED`, `DELIVERY_ATTEMPTED`, `DELIVERY_SUCCEEDED` /
`DELIVERY_FAILED`, `NOTIFICATION_DEDUPLICATED`, ...). Entries only ever
hold short factual summaries — never the message body or credentials
(requirement 4.9).

```bash
curl -s http://localhost:8080/api/v1/notifications/{id}/audit
```

### Errors

| Status | Cause |
|---|---|
| `400 VALIDATION_FAILED` | Missing/invalid request fields — response includes per-field `details` |
| `400 INVALID_REQUEST` | e.g. `expiresAt` before `scheduledAt` / in the past |
| `404 NOT_FOUND` | Unknown `notificationId` |

## Provider behavior

### Email / SMS — simulated (ADR-007)

No real Email/SMS provider is integrated. `EmailChannelProvider` and
`SmsChannelProvider` both delegate to `SimulatedFailureRules`
(extracted in Phase 2 — ADR-009), which decides the outcome
deterministically from a substring marker in `recipientId`, so every
failure path in requirement 4.5 is reproducible on demand:

| Marker in `recipientId` | Result | Retryable? |
|---|---|---|
| *(none of the below)* | `SUCCEEDED` | — |
| `invalid-` | `INVALID_RECIPIENT` | no |
| `ratelimit-` | `RATE_LIMITED` | yes |
| `timeout-` | `TIMEOUT` | yes |
| `authfail-` | `AUTH_ERROR` | no |
| `failtransient-` | `TRANSIENT_PROVIDER_ERROR` | yes |
| `failpermanent-` | `PERMANENT_PROVIDER_REJECTION` | no |

e.g. `{"recipientId": "invalid-bob@example.com"}` always fails with
`INVALID_RECIPIENT` on every channel. A "Retryable" failure gets picked up
again by `DeliveryWorker` per `RetryBackoffPolicy` (ADR-011) until
`maxAttempts` (3) is reached, then moves to `EXHAUSTED`; a non-retryable
one moves straight to `FAILED`.

### Webhook — real HTTP call (ADR-012)

Unlike Email/SMS, `WebhookChannelProvider` makes a **real** outbound HTTP
POST and classifies the outcome from the actual response, not a marker:

| Response | Result |
|---|---|
| `2xx` | `SUCCEEDED` |
| `401` / `403` | `AUTH_ERROR` |
| `404` / `410` | `INVALID_RECIPIENT` |
| `429` | `RATE_LIMITED` |
| other `4xx` | `PERMANENT_PROVIDER_REJECTION` |
| `5xx` | `TRANSIENT_PROVIDER_ERROR` |
| connect/read timeout | `TIMEOUT` |
| malformed/non-http(s) `recipientId` | `INVALID_RECIPIENT` |

For `WEBHOOK`, `recipientId` **is the target URL** — any real `http(s)`
endpoint works. For deterministic local demos/tests without an external
dependency, `WebhookSinkController` exposes a local, non-public stand-in
receiver at `/internal/webhook-sink/{scenario}`:

| Scenario | Response |
|---|---|
| `ok` | `200` |
| `rate-limit` | `429` |
| `server-error` | `500` |
| `bad-request` | `400` |
| `unauthorized` | `401` |
| `not-found` | `404` |
| `timeout` | sleeps 1s (triggers a real client read-timeout with the default 3s config) |

```bash
curl -s -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "webhook-demo-1",
    "sourceSystem": "ops-monitor",
    "eventId": "corr-wh-1",
    "notificationType": "SERVICE_DOWN",
    "severity": "CRITICAL",
    "priority": "URGENT",
    "message": "payments-service is down",
    "recipients": [{"recipientId": "http://localhost:8080/internal/webhook-sink/ok"}],
    "requestedChannels": ["WEBHOOK"]
  }'
```

`WebhookSinkController` is scaffolding for this prototype, not part of the
public API — it exists purely so the real HTTP path can be demonstrated
and tested without depending on an external service being reachable.

### Recipient preferences & routing precedence (ADR-010)

Demo recipient preferences are seeded in `src/main/resources/data.sql`:
`alice@example.com` is opted out of `SMS`, `bob@example.com` out of
`EMAIL`.

- Any severity **below** `CRITICAL` respects the opt-out — submit a
  `WARNING` notification to `alice@example.com` requesting `EMAIL` +
  `SMS`, and only `EMAIL` is routed (audited under `ROUTING_DECIDED`).
- A `CRITICAL` notification **overrides** the opt-out — the same request
  with `"severity": "CRITICAL"` reaches `alice@example.com` on both
  `EMAIL` and `SMS`, and the audit entry says so explicitly
  (`...severity=CRITICAL overrides opt-out...`).

⚠️ `severity` is caller-supplied with no authentication behind it in this
prototype — see `ARCHITECTURE.md`'s Production Readiness Backlog. This
rule is only safe to rely on in a real deployment once the caller
asserting `CRITICAL` is itself verified.

## Testing

```bash
./mvnw test
```

| Test | Type | Covers |
|---|---|---|
| `RoutingServiceTest` | Unit | Channel selection with/without recipient opt-outs, plus `CRITICAL` override (5 cases) |
| `NotificationStatusCalculatorTest` | Unit | Overall-status derivation rules (all 6 branches) |
| `RetryBackoffPolicyTest` | Unit | Exponential backoff math + max-delay cap |
| `NotificationFlowIntegrationTest` | Integration (`@SpringBootTest` + `MockMvc`) | Full submit → async worker → `DELIVERED` status; idempotent replay creates no second notification |
| `DeliveryRetryIntegrationTest` | Integration | Retryable failure → 2x `RETRY_SCHEDULED` → `EXHAUSTED`, verified via both the status API and the audit trail |
| `WebhookChannelIntegrationTest` | Integration (`webEnvironment = RANDOM_PORT`) | Webhook success, `404`→`INVALID_RECIPIENT` (no retry), `429`→retries→`EXHAUSTED`, slow endpoint→`TIMEOUT` — all against a real HTTP call on the embedded server's actual port |
| `RoutingPrecedenceIntegrationTest` | Integration | End-to-end: `WARNING` to an opted-out recipient skips the channel; `CRITICAL` to the same recipient still reaches it, with the override visible in the audit trail |

Integration tests override `notification.delivery.poll-interval-ms` and
the retry-delay properties to small values so they don't wait on
production cadences.

## Known limitations

- **`severity` is caller-supplied with no authentication** — since ADR-010,
  a `CRITICAL` claim bypasses recipient opt-outs, and nothing currently
  verifies who's asserting it. See the Production Readiness Backlog below.
- **Routing override is binary, not a graduated escalation policy** — a
  real system might try the preferred channel first and escalate to a
  forced channel after N minutes, rather than an immediate blanket
  override (ADR-010).
- **No distinction between a "soft" preference and a "hard," legally
  binding opt-out** (e.g. an SMS `STOP` request) — `CRITICAL` currently
  overrides both the same way (ADR-010).
- **Single-node delivery worker**, processed sequentially — no
  `SKIP LOCKED`/partitioning across nodes, and no concurrency within a
  node either; fine at prototype scale (ADR-002, Production Readiness
  Backlog).
- **Idempotency key retention is unbounded** — no TTL/archival job yet
  (ADR-003).
- **Retry policy is global, not severity-aware** — `maxAttempts` and
  backoff bounds are the same for a `CRITICAL` alert and an `INFO` one
  (ADR-011).
- **`WebhookSinkController` is demo/test scaffolding shipped in `main`**,
  not gated behind a profile — it's clearly marked non-public, but a real
  deployment would remove or profile-gate it (ADR-012).
- **No outbound URL allow-listing/SSRF protection** on the webhook
  provider — acceptable for a prototype where the caller is a trusted
  upstream system, called out as a production hardening gap (ADR-012).

See `ARCHITECTURE.md`'s **Production Readiness Backlog** for the full,
severity-ranked list (including authentication, schema migrations,
observability, and circuit breakers) — deliberately not fixed as part of
these three scenarios, reviewed as a batch once all phases are complete.

## License

This project does not specify a license yet.
