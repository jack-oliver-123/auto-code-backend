## ADDED Requirements

### Requirement: Applications expose a durable latest-generation lifecycle
The system SHALL persist the latest generation lifecycle for every active application using `PENDING`, `GENERATING`, `SUCCEEDED`, or `FAILED`. Application creation MUST initialize `PENDING`. After acquiring the application processing lease and completing request, application, ownership, generation-type, and effective-message validation, the system SHALL create an opaque attempt identifier and transition the application to `GENERATING` before writing current-turn history or invoking the provider.

Every terminal transition MUST match the application, owner, opaque attempt identifier, and current `GENERATING` state. Success SHALL clear prior failure details and record the finish time only after every required generation, validation, publication, preview, application, and history operation succeeds. Failure or cancellation after an attempt starts SHALL record `FAILED`, a bounded application-owned safe failure code and message, and the finish time. Stored failure details MUST NOT include raw provider payloads, generated source, stack traces, credentials, or unrestricted exception messages.

The lifecycle describes the latest attempt independently of an older valid project. A failed first attempt SHALL remain retryable without a generation type or stable partial output. A failed later attempt SHALL preserve the prior complete project, preview, and generation type. Owner and administrator responses SHALL expose the latest status and safe failure details needed to diagnose or retry the application; public responses MUST NOT expose the attempt identifier or failure details.

#### Scenario: Application is created before generation
- **WHEN** an authenticated user creates a valid application
- **THEN** the application is persisted as `PENDING` with no attempt id, failure details, or generation timestamps

#### Scenario: A validated generation attempt starts
- **WHEN** the owner holds the application lease and all pre-attempt validation succeeds
- **THEN** one new attempt id is stored with `GENERATING` and a start time before history or provider work begins

#### Scenario: Complete generation succeeds
- **WHEN** the matching attempt completes all required provider, parsing, build, publication, preview, and persistence work
- **THEN** the application conditionally becomes `SUCCEEDED`, prior failure details are cleared, and a finish time is stored

#### Scenario: First generation fails
- **WHEN** the matching first attempt times out, is cancelled, or fails required generation work
- **THEN** the application conditionally becomes `FAILED`, stores only safe bounded failure details, retains a null generation type, and has no stable partial project

#### Scenario: Regeneration fails
- **WHEN** a matching later attempt fails after an earlier complete version exists
- **THEN** the latest status becomes `FAILED` while the earlier generation type, stable project, and preview remain usable

#### Scenario: A stale callback arrives after retry
- **WHEN** a callback attempts a terminal update using an attempt id that is no longer current
- **THEN** the conditional update changes no lifecycle, project, type, or successful-history state belonging to the newer attempt

#### Scenario: An abandoned attempt is reconciled
- **WHEN** a `GENERATING` attempt is older than the configured stale age and Redis confirms that its application lease is absent
- **THEN** the system conditionally marks that exact attempt `FAILED` without changing a newer or actively leased attempt

#### Scenario: Lease absence cannot be confirmed
- **WHEN** stale-attempt reconciliation cannot query Redis reliably
- **THEN** it leaves the database lifecycle unchanged rather than failing potentially active work

### Requirement: Generation SSE reports one explicit terminal outcome
The generation response SHALL continue to use `text/event-stream` and preserve every AI content chunk byte-for-byte in JSON data shaped as `{"d":"<chunk>"}`. The system MAY interleave SSE comments as heartbeats, but heartbeat text MUST NOT become a content event, parser input, persisted history, generated source, or response-limit input.

A successful attempt SHALL emit exactly one named `done` event only after its durable `SUCCEEDED` transition and all existing completion requirements. An asynchronous failure after the reactive response begins SHALL finalize the matching attempt when possible, emit exactly one named `error` event containing a stable numeric API code, safe message, and `FAILED` status, emit no `done`, and then complete the Flux without delegating that failure to JSON controller advice. EOF or cancellation without `done` remains unsuccessful even when no error event can reach the client.

Synchronous request decoding, request-level validation, and authentication failures raised before SSE begins SHALL retain the normal JSON error response and HTTP status.

#### Scenario: Upstream streaming request times out
- **WHEN** the provider stream reaches its configured deadline after SSE has started
- **THEN** the matching attempt is finalized as `FAILED`, one named `error` event is emitted when writable, no `done` is emitted, and no JSON body is written under `text/event-stream`

#### Scenario: Parsing or build fails after content events
- **WHEN** exact content chunks were emitted but required parsing, validation, build, or publication fails
- **THEN** the stream ends with one named `error`, the chunks remain unchanged, no `done` is emitted, and clients must treat those chunks as provisional

#### Scenario: Generation completes successfully with heartbeats
- **WHEN** an attempt emits content and heartbeat comments before all completion work succeeds
- **THEN** heartbeat comments do not alter accumulated or persisted AI content and exactly one `done` follows successful finalization

#### Scenario: Client cancels an active stream
- **WHEN** the client disconnects after the attempt entered `GENERATING`
- **THEN** cancellation finalizes the matching attempt as `FAILED` when persistence remains available, emits no `done`, and does not require an error event on the closed connection

#### Scenario: Request fails before SSE starts
- **WHEN** request JSON, positive application id, or authentication validation fails synchronously
- **THEN** the system returns the applicable JSON HTTP error and creates no generation attempt

#### Scenario: Unexpected reactive error reaches the controller boundary
- **WHEN** an unclassified service error escapes after the SSE response has begun
- **THEN** the controller logs content-free diagnostics and emits a generic named `error` when writable instead of invoking JSON exception serialization

### Requirement: Generation time limits are coherent and externalized
Provider streaming timeout, complete-attempt timeout, servlet asynchronous-response timeout, heartbeat interval, and stale-attempt age SHALL have explicit finite positive defaults and SHALL be configurable without source changes. Configuration MUST enforce that heartbeat interval is below the provider timeout, provider timeout is below the complete-attempt timeout, servlet asynchronous timeout exceeds the complete-attempt timeout, and stale-attempt age exceeds the maximum live-attempt duration. The complete-attempt limit MUST account for the configured Vue build timeout plus bounded finalization overhead.

Timeout diagnostics SHALL identify application id, attempt id, phase, configured limit, and duration without logging prompts, generated source, provider payloads, or credentials.

#### Scenario: A valid Vue stream exceeds the former default
- **WHEN** an active provider stream runs longer than 60 or 120 seconds but remains within the configured provider and complete-attempt limits
- **THEN** the backend keeps processing it and may complete normally rather than cancelling it at the former implicit deadline

#### Scenario: Complete attempt exceeds its deadline
- **WHEN** provider, parsing, build, and finalization work exceed the configured complete-attempt limit
- **THEN** the system cancels active work, rolls back unresolved publication, finalizes the matching attempt as `FAILED`, and emits no `done`

#### Scenario: Generation timeout configuration is inconsistent
- **WHEN** a duration is non-positive or the required timeout ordering is violated
- **THEN** application startup fails with a configuration error instead of applying ambiguous or unbounded behavior
