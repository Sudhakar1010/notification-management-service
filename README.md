# Notification Management Service

A Spring Boot service that accepts notification requests from upstream
systems, routes them to the right channels per recipient, delivers them
asynchronously, and exposes status and audit history.

**Status:** Phase 1 (Greenfield) is implemented and tested — submission,
recipient/channel routing, asynchronous processing, delivery attempts,
status retrieval, deduplication/idempotency, and audit history. See
[ARCHITECTURE.md](ARCHITECTURE.md) for the full design rationale (ADRs),
control-flow diagram, and package structure explanation.

## Tech Stack

- Java 21
- Spring Boot 4.1.1 (Spring Web MVC, Spring Data JPA, Spring Validation)
- H2 in-memory database
- Lombok
- Maven Wrapper

No external message broker, cache, or third-party provider account is
required — asynchronous delivery is implemented as a DB-backed outbox
processed by a `@Scheduled` worker (see ARCHITECTURE.md ADR-002), and
Email/SMS providers are deterministic in-process simulations (ADR-007).

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
    poll-interval-ms: 2000    # DeliveryWorker poll cadence
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
| `recipients` | yes | Array of `{ "recipientId": "..." }`, at least one |
| `requestedChannels` | yes | Array of `EMAIL` \| `SMS`, at least one |
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
`PROCESSING` while any attempt is in flight, `DELIVERED` if every attempt
succeeded, `FAILED` if none did, `PARTIALLY_DELIVERED` for a mix.

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

## Mock provider simulation rules

No real Email/SMS provider is integrated (see ARCHITECTURE.md ADR-007).
`EmailChannelProvider`/`SmsChannelProvider` decide the outcome
deterministically from a substring marker in `recipientId`, so every
failure path in requirement 4.5 is reproducible on demand:

| Marker in `recipientId` | Result |
|---|---|
| *(none of the below)* | `SUCCEEDED` |
| `invalid-` | `FAILED` — `INVALID_RECIPIENT` |
| `ratelimit-` | `FAILED` — `RATE_LIMITED` |
| `timeout-` | `FAILED` — `TIMEOUT` |
| `authfail-` | `FAILED` — `AUTH_ERROR` |
| `failtransient-` | `FAILED` — `TRANSIENT_PROVIDER_ERROR` |
| `failpermanent-` | `FAILED` — `PERMANENT_PROVIDER_REJECTION` |

e.g. `{"recipientId": "invalid-bob@example.com"}` always fails with
`INVALID_RECIPIENT` on every channel.

Demo recipient preferences are seeded in `src/main/resources/data.sql`:
`alice@example.com` is opted out of `SMS`, `bob@example.com` out of
`EMAIL` — submit a request with both channels requested for either to see
routing filter the opted-out channel (audited under `ROUTING_DECIDED`).

## Testing

```bash
./mvnw test
```

| Test | Type | Covers |
|---|---|---|
| `RoutingServiceTest` | Unit | Channel selection with/without recipient opt-outs |
| `NotificationStatusCalculatorTest` | Unit | Overall-status derivation rules (all 6 branches) |
| `NotificationFlowIntegrationTest` | Integration (`@SpringBootTest` + `MockMvc`) | Full submit → async worker → `DELIVERED` status; idempotent replay creates no second notification |

The integration test overrides `notification.delivery.poll-interval-ms` to
`200` so it doesn't wait on the production 2s cadence.

## Known limitations (Phase 1 scope)

- **No retry/backoff yet** — a failed delivery attempt is marked `FAILED`
  after a single try; bounded retry with backoff is Phase 2 scope.
- **Two channels only** (`EMAIL`, `SMS`) — a third channel (`WEBHOOK`) is
  Phase 2 scope.
- **Provider failure-simulation logic is duplicated** between
  `EmailChannelProvider` and `SmsChannelProvider` — intentional, flagged
  as the Phase 2 refactor target (ADR-009).
- **Routing precedence is undecided** — severity/policy vs. recipient
  opt-out conflicts aren't resolved yet; current routing only applies
  opt-outs (ADR-010, Phase 3 ambiguous-requirement scenario).
- **Single-node delivery worker** — no `SKIP LOCKED`/partitioning; fine at
  prototype scale, called out as a production follow-up (ADR-002).
- **Idempotency key retention is unbounded** — no TTL/archival job yet
  (ADR-003).

## License

This project does not specify a license yet.
