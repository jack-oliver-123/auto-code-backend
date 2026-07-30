## ADDED Requirements

### Requirement: Redis availability failures are explicit at startup and runtime
The system SHALL treat the shared Redis service as a required runtime dependency for distributed Session and application coordination. Before a normal runtime instance reports ready, it MUST perform a bounded authenticated Redis connectivity probe using the configured shared connection infrastructure. Probe failure MUST fail startup with a clear content-free diagnostic and MUST NOT install a process-local Session, conversation-memory, invalidation, or application-lock fallback. Test contexts MAY explicitly replace the live startup probe without changing the production default.

After successful startup, a confirmed Redis connection failure during Session lookup, creation, id rotation, invalidation, or commit SHALL mark dependency readiness unavailable. If the HTTP response is not committed, the system MUST return one `application/json` response with HTTP 503 and the stable dependency-unavailable API code/message. It MUST NOT redispatch to `/error`, retry a non-idempotent request, access/create another Session while writing the error, or attempt a second Session save. If the response is already committed, the system MUST NOT rewrite its status, content type, or body as JSON and SHALL terminate it with content-free diagnostics.

A later successful bounded dependency probe SHALL restore readiness. Readiness state MUST NOT substitute for the result of an actual Session or lease operation and MUST NOT authorize local fallback. Public error bodies MUST NOT reveal the Redis endpoint, database, username, password, Session id, cookie, or stored Session data.

#### Scenario: Redis is absent during startup
- **WHEN** a normal runtime instance cannot obtain `PONG` from its configured Redis service within the connection/command bounds
- **THEN** startup fails before the instance reports ready with no local Session or coordination fallback

#### Scenario: Login Session commit loses Redis
- **WHEN** credentials are accepted but Redis becomes unavailable while Spring Session saves the new authenticated Session and the response is not committed
- **THEN** the caller receives one HTTP 503 JSON dependency-unavailable response, no authenticated cookie is issued, and no `/error` Session save is attempted

#### Scenario: Existing Session cannot be read
- **WHEN** an authenticated request cannot load its Redis-backed Session because of a confirmed connection failure
- **THEN** the request receives HTTP 503 rather than not-logged-in, generic 500, or a process-local Session

#### Scenario: Session data is malformed rather than unavailable
- **WHEN** Redis is reachable but a Session value cannot be deserialized into the supported authentication state
- **THEN** existing malformed-session invalidation and not-logged-in behavior applies instead of dependency-unavailable classification

#### Scenario: Unrelated application exception occurs
- **WHEN** a request throws a runtime exception that is not caused by Redis connection availability
- **THEN** the outer dependency filter does not translate it to HTTP 503

#### Scenario: Response is already committed
- **WHEN** Redis availability fails after an SSE or other response has committed its status and media type
- **THEN** the system performs no JSON rewrite or error redispatch and closes the response without a secondary message-converter failure

#### Scenario: Redis recovers after runtime outage
- **WHEN** a bounded health probe succeeds after readiness was marked unavailable
- **THEN** readiness becomes available and subsequent real Session operations may succeed through Redis

### Requirement: Redis dependency health is observable without sensitive data
The application health contract SHALL distinguish process liveness from Redis-dependent readiness. A liveness check MUST NOT require creating or loading an HTTP Session. A readiness check SHALL return success only after a bounded Redis probe succeeds and SHALL return HTTP 503 while Redis cannot be reached. Health and logs MAY identify Redis as the unavailable dependency and the non-secret operation category but MUST NOT include credentials, Session contents, cookies, prompts, or generated source.

#### Scenario: Backend process is alive while Redis is down
- **WHEN** Redis becomes unavailable after backend startup
- **THEN** liveness remains available while readiness returns HTTP 503

#### Scenario: Health request has no Session cookie
- **WHEN** an orchestrator invokes liveness or readiness without a browser Session
- **THEN** the check completes without creating a Redis-backed HTTP Session

#### Scenario: Redis health diagnostics are recorded
- **WHEN** startup, readiness, or a real request detects a Redis connection failure
- **THEN** diagnostics contain dependency and operation category without connection credentials or application/session content
