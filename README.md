# Notification Management Service

A Spring Boot service that accepts notification requests from upstream
systems, routes them to the right channels per recipient, delivers them
asynchronously, and exposes status and audit history.

**Documentation:** see [ARCHITECTURE.md](ARCHITECTURE.md) for design
rationale (ADRs), the scenario walkthrough, testing approach/limitations/
trade-offs, the Production Readiness Backlog, and a Cloud & Production
Infrastructure Roadmap (§8) covering what a real deployment would swap in
(managed queue, managed DB, real IdP, observability backend, etc.).

## Tech Stack

- Java 21
- Spring Boot 4.1.1 (Web MVC, Data JPA, Validation, Actuator)
- Resilience4j (circuit breaker + retry, webhook channel only)
- Micrometer Tracing + OpenTelemetry (logging exporter — no collector required)
- Flyway (baseline migration present, currently shipped disabled — see ARCHITECTURE.md ADR-019)
- springdoc-openapi (Swagger UI, generated from the controllers)
- H2 in-memory database
- Lombok
- Maven Wrapper

No external message broker, cache, provider account, or observability
backend is required to run this — see ARCHITECTURE.md for why.

## Project Structure

```
com/nms/
├── common/       shared enums (Channel, Severity, Priority, statuses, FailureType)
├── notification/ submission + status API, aggregate root (+ dto/)
├── routing/      channel routing decision
├── delivery/     async delivery outbox, worker, providers (+ providers/)
├── audit/        audit trail
├── exception/    centralized error handling
└── config/       scheduling, webhook client, resilience, observability config
```

Packages are organized by business capability, not technical layer — see
ARCHITECTURE.md ADR-001.

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
  jpa:
    hibernate:
      ddl-auto: create-drop   # schema recreated on every startup

notification:
  delivery:
    poll-interval-ms: 2000          # DeliveryWorker poll cadence
    retry-base-delay-ms: 2000       # outer, DB-backed retry loop
    retry-max-delay-ms: 30000
    webhook-connect-timeout-ms: 2000
    webhook-read-timeout-ms: 3000
    webhook:
      circuit-breaker:
        failure-rate-threshold: 50
        sliding-window-size: 10
        minimum-number-of-calls: 5
        wait-duration-in-open-state-seconds: 30
      retry:
        max-attempts: 2              # fast, inner retry — distinct from the outer loop above
        wait-duration-ms: 200

management:
  tracing:
    sampling:
      probability: 1.0
```

**H2 console:** `http://localhost:8080/h2-console` — JDBC URL
`jdbc:h2:mem:notifications`, user `sa`, empty password.

**API docs (Swagger UI):** `http://localhost:8080/swagger-ui/index.html`
(raw OpenAPI JSON at `/v3/api-docs`) — generated from the controllers, so it
can't drift from the code the way the hand-written reference below can.

**Actuator:** `/actuator/health` (H2 check + liveness/readiness),
`/actuator/info`, `/actuator/metrics`.

**Tracing:** every HTTP request is traced automatically; each async
delivery attempt gets its own span (tagged with notification id, channel,
attempt number, outcome), printed to the log via `LoggingSpanExporter`.

**Authentication (opt-in, off by default):** set
`notification.security.enabled=true` to require an `X-Api-Key` header on
`/api/v1/notifications/**`. Demo keys are seeded in `application.yaml`
(`notification.security.api-keys`). A valid key whose mapped
`sourceSystem` doesn't match the request body's `sourceSystem` field is
rejected with `403`. See ARCHITECTURE.md ADR-018 for why this defaults
off and what enabling it in production actually requires.

```bash
curl -s -X POST http://localhost:8080/api/v1/notifications \
  -H "Content-Type: application/json" \
  -H "X-Api-Key: demo-trading-alerts-key" \
  -d '{ ... "sourceSystem": "trading-alerts", ... }'
```

## API Reference

### 1. Submit a notification

```
POST /api/v1/notifications
```

| Field | Required | Notes |
|---|---|---|
| `idempotencyKey` | yes | Dedup boundary is `(sourceSystem, idempotencyKey)` |
| `sourceSystem`, `eventId`, `notificationType` | yes | Free-text identifiers |
| `severity` | yes | `INFO` \| `WARNING` \| `CRITICAL` — `CRITICAL` overrides recipient channel opt-outs |
| `priority` | yes | `LOW` \| `MEDIUM` \| `HIGH` \| `URGENT` — captured/stored only, not currently used by any logic |
| `subject` | no | |
| `message` | yes | |
| `recipients` | yes | Array of `{ "recipientId": "..." }`, at least one, no duplicates. For `WEBHOOK`, `recipientId` is the target URL |
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

Repeating the same `(sourceSystem, idempotencyKey)` with the **same**
payload returns `200 OK` with `"duplicate": true` and the same
`notificationId`. Repeating it with a **different** payload returns `409`
(see Errors below).

### 2. Get notification status

```
GET /api/v1/notifications/{id}
```

Overall status is derived live from delivery attempts: `PROCESSING` while
any attempt is in flight, `DELIVERED` if every attempt succeeded, `FAILED`
if none did, `PARTIALLY_DELIVERED` for a mix.

Per-channel `status`: `QUEUED`, `SENDING`, `SUCCEEDED`, `RETRY_SCHEDULED`,
`FAILED` (non-retryable, terminal), `EXHAUSTED` (retries used up), or
`SKIPPED` (notification expired before this attempt ran).

```bash
curl -s http://localhost:8080/api/v1/notifications/{id}
```

```json
{
  "notificationId": "...",
  "overallStatus": "PARTIALLY_DELIVERED",
  "requestedChannels": ["EMAIL", "SMS"],
  "selectedChannels": ["EMAIL"],
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

```bash
curl -s http://localhost:8080/api/v1/notifications/{id}/audit
```

Returns the ordered event log (accepted, routing decided, queued,
attempted, succeeded/failed, retry scheduled, exhausted, deduplicated,
expired, rejected). Entries hold only short factual summaries — never the
message body or credentials. A rejected request (failed validation, no
`Notification` ever created) also produces a `NOTIFICATION_REJECTED` row
with a null `notificationId`, so it isn't retrievable via this endpoint.

### Errors

Every error response shares one shape:
`{"error": "...", "message": "...", "details": [...], "timestamp": "..."}`

| Status | Cause |
|---|---|
| `400 VALIDATION_FAILED` | Missing/invalid request fields |
| `400 INVALID_REQUEST` | e.g. `expiresAt` before `scheduledAt`/in the past, duplicate `recipientId` |
| `400 MALFORMED_REQUEST` | Unparseable JSON, or an invalid enum value |
| `401 UNAUTHORIZED` | Missing/invalid `X-Api-Key` (only when `notification.security.enabled=true`) |
| `403 SOURCE_SYSTEM_MISMATCH` | Authenticated API key's `sourceSystem` doesn't match the request body |
| `404 NOT_FOUND` | Unknown `notificationId` |
| `409 IDEMPOTENCY_KEY_CONFLICT` | Same `(sourceSystem, idempotencyKey)` reused with a different payload |
| `409 DATA_CONFLICT` | Defense-in-depth for any other data conflict |
| `500 INTERNAL_ERROR` | Unexpected server error (logged server-side) |

## Provider behavior

### Email / SMS — simulated

No real Email/SMS provider is integrated. Outcome is deterministic from a
substring marker in `recipientId`:

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
`INVALID_RECIPIENT`. A retryable failure gets picked up again until
`maxAttempts` (3), then moves to `EXHAUSTED`.

### Webhook — real HTTP call

`recipientId` **is the target URL**. Outcome is classified from the real
HTTP response:

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

For local demos/tests without an external dependency, a non-public local
stand-in receiver is available at `/internal/webhook-sink/{scenario}`
(`ok`, `rate-limit`, `server-error`, `bad-request`, `unauthorized`,
`not-found`, `timeout`):

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

### Recipient preferences

Demo opt-outs are seeded in `src/main/resources/data.sql`:
`alice@example.com` is opted out of `SMS`, `bob@example.com` out of
`EMAIL`. Any severity below `CRITICAL` respects the opt-out; `CRITICAL`
overrides it (see ARCHITECTURE.md ADR-010 for why, and the authentication
caveat that goes with it).

## Testing

```bash
./mvnw test
```

41 tests (unit + integration), 0 failures. See ARCHITECTURE.md for the
full test inventory, testing approach, known limitations, and trade-offs.

## License

This project does not specify a license yet.
