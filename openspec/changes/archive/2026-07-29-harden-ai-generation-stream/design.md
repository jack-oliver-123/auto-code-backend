## Context

The generation endpoint is a Spring MVC `POST` SSE response backed by a Reactor stream from LangChain4j. The selected LangChain4j Spring Boot 4 HTTP client applies the model `timeout` as both connect and read timeout; with the JDK request factory the read timeout bounds the lifetime of the streaming HTTP request. Environments without an override therefore close around the library default of 60 seconds, while the current local override reproduces closure at 120 seconds. The resulting `LangChain4jException: closed` escapes the reactive pipeline after SSE has started, and the JSON controller advice then attempts to serialize `BaseResponse` under `text/event-stream`.

The existing Vue pipeline already accumulates only normally completed provider output, requires the versioned closing marker and required entries, builds in an isolated container, validates `dist/index.html`, and publishes with a rollback-capable directory replacement. It correctly left no stable project for the observed interrupted first generation. However, the application row has no explicit generation lifecycle, streamed partial content has no structured failure terminator, and success/history metadata needs a single transaction plus clear compensation boundaries.

Applications are created before generation and every later conversation can regenerate the same application. The current Redis processing lease remains the cross-instance exclusion mechanism and MySQL remains authoritative for application and history state.

## Goals / Non-Goals

**Goals:**

- Allow complete Vue streams to run beyond the current 60/120-second whole-request limit while retaining bounded deadlines.
- Give every application a durable, queryable latest-generation state and safe failure reason.
- Give SSE clients an unambiguous terminal `done` or `error` event without changing AI content chunks.
- Ensure timeout, cancellation, lease loss, parser/build failure, and downstream finalization failure never publish an incomplete project or leave successful metadata.
- Recover abandoned `GENERATING` state after process death without overwriting a live or newer attempt.
- Keep normal tests deterministic and independent of a live model provider.

**Non-Goals:**

- Persisting a historical row for every generation attempt or introducing an asynchronous job queue.
- Resuming an interrupted provider stream from the last token.
- Changing the Vue project response protocol, scaffold, dependency allowlist, or build sandbox.
- Treating derived Redis/Caffeine conversation memory as required success state.
- Implementing frontend UI changes in this backend repository.

## Decisions

### 1. Use coherent bounded deadlines and downstream heartbeats

Introduce validated `app.generation.*` properties for provider timeout, overall attempt timeout, servlet async timeout, heartbeat interval, and stale-attempt age. Reference the provider and servlet values from the LangChain4j and Spring MVC configuration so one typed configuration owns the intended hierarchy. Defaults will permit a multi-minute Vue response and satisfy:

```text
heartbeat interval < provider timeout < overall attempt timeout < servlet async timeout
```

The overall deadline includes post-provider parsing and build work and must also exceed the configured Vue build deadline with bounded overhead. The provider timeout remains finite because the current client treats it as a whole-request deadline; it will be raised from the unsuitable default rather than disabled. A Reactor deadline covers the complete application attempt so a provider or finalization path cannot run forever.

The controller will interleave SSE comment heartbeats, not data events. Comments keep downstream proxies and browsers active but never enter the byte-for-byte AI chunk stream, parser input, history, or response-size accounting. Heartbeats stop before either terminal event.

Using unbounded timeouts was rejected because leaked provider requests and builds would retain threads, leases, and temporary directories indefinitely. Heartbeats alone were rejected because they cannot prevent the upstream JDK HTTP request timeout that caused the observed failure.

### 2. Store the latest attempt lifecycle on `app`

Add an `AppGenerationStatusEnum` with persisted values `PENDING`, `GENERATING`, `SUCCEEDED`, and `FAILED`. Add application columns for status, opaque attempt id, safe failure code and message, start time, and finish time. `POST /app/add` creates `PENDING`. After lease acquisition and request/ownership/type validation, but before user-history persistence or provider invocation, generation creates a new unpredictable attempt id and conditionally transitions to `GENERATING` while clearing prior failure details.

Every terminal update includes `id`, owner, attempt id, and current `GENERATING` status in its predicate. This compare-and-set rule prevents a late provider callback or a lease-losing instance from changing a newer attempt. `SUCCEEDED` is written only as part of required successful persistence. Every attempted generation failure after the transition, including user-history failure and cancellation, writes `FAILED` with a bounded application-owned code/message and finish time. Provider payloads, generated source, stack traces, credentials, and raw exception messages are never stored.

The status describes the latest attempt, not whether an older stable version exists. A failed first attempt remains retryable with null `codeGenType`; a failed regeneration retains its prior `codeGenType`, stable project, and preview while reporting `FAILED`. Owner and administrator views expose status and safe failure details. Public views may expose only status when needed and never expose attempt ids or failure details.

A separate generation-task table was rejected for this change because attempt history, queueing, and background execution are not requested. The opaque current attempt id provides the required stale-callback protection without adding a second lifecycle aggregate.

### 3. Reconcile abandoned attempts against the distributed lease

The overall Reactor deadline finalizes a live process. Process termination can still leave `GENERATING`, so a bounded reconciler examines attempts older than the configured stale age. It marks an attempt `FAILED` only when its exact attempt id is still current and the application processing lease is confirmed absent. If Redis cannot confirm lease absence, reconciliation makes no database change. Acquiring a new application lease also permits a new attempt to replace an abandoned state after validation.

The stale age must exceed the maximum configured live-attempt deadline. An index on status and start time bounds reconciliation queries. This is preferred to changing every old `GENERATING` row at startup, which could incorrectly fail work owned by another live instance.

### 4. Model failure as an SSE event inside the reactive boundary

Extend `AppGenerationEvent` with terminal failure data and an internal heartbeat representation. Content remains `data: {"d":"<chunk>"}` and `done` remains the sole success event. An asynchronous failure is finalized in the application service and becomes exactly one named `error` event with stable JSON fields such as API code, safe message, and `FAILED` status. The Flux then completes normally and never reaches `GlobalExceptionHandler` after the SSE response is committed.

The controller also applies a defensive terminal mapper for an unexpected service error so it logs once and emits a generic SSE error when the connection remains writable. Synchronous request-body and authentication errors raised before the reactive response starts continue to use the existing JSON status response. Client cancellation records cancellation and failure state when an attempt had started, but no event is promised because the client connection no longer exists.

Returning an HTTP error after the first content chunk was rejected because HTTP status and content type are already committed. Abruptly closing the connection was rejected because clients cannot distinguish a backend failure from a transient transport interruption and the current JSON advice produces secondary failures.

### 5. Make database success finalization atomic and resource commits non-failing

After provider completion, parsing, Vue validation/build, rollback-capable code publication, and preview preparation, one `TransactionTemplate` callback inserts the exact successful AI history row, persists the initial `codeGenType` when needed, and conditionally changes the matching attempt to `SUCCEEDED`. A transaction failure rolls back all three database effects and leaves both code and preview publications unresolved so their close paths restore the previous version.

Directory publication commit already treats old-backup cleanup as best effort. Preview commit will adopt the same rule: installation and rollback-sensitive work happens during preparation, while deletion of an obsolete preview snapshot after database success is diagnostic cleanup and cannot invalidate committed success. This removes a failure-producing step after `SUCCEEDED` is stored. Only after database success and both non-failing resource commits does the service emit `done`.

Failure finalization writes the safe AI outcome, when a user row exists, and the matching `FAILED` state in one separate transaction. If this secondary transaction fails, the original generation error remains primary, derived memory is invalidated, and diagnostics identify only application/attempt and failure category.

### 6. Treat client content as provisional until `done`

The backend cannot retract chunks already delivered over SSE. The protocol therefore defines all content from the current attempt as provisional. A client may render it as progress, but it must commit generated-source state only after `done`; `error`, cancellation, or EOF without `done` invalidates that draft. Queryable application status provides reconnect recovery without replaying or trusting partial transport data.

## Risks / Trade-offs

- [A higher provider timeout retains an HTTP worker longer] -> Keep provider and overall deadlines finite, retain the distributed lease, and cover cancellation and timeout cleanup.
- [SSE heartbeats can be mistaken for model content] -> Emit only SSE comments and test that parser input, history, response bounds, and content events remain byte-for-byte unchanged.
- [Latest-attempt status does not provide audit history] -> Keep durable chat terminal outcomes and defer an attempt-history table until queueing or audit requirements exist.
- [A process can die between external publication preparation and database finalization] -> Rollback-capable sibling artifacts remain recoverable, stale attempts become `FAILED`, and startup/reconciliation cleanup handles abandoned temporary paths according to existing publication rules.
- [Stale reconciliation races a retry] -> Require both confirmed lease absence and an exact attempt-id/status conditional update.
- [Failure-state persistence can itself fail] -> Preserve the original exception, emit only a generic safe error when possible, invalidate derived memory, and log content-free secondary diagnostics.
- [Existing clients ignore named `error`] -> Preserve the existing invariant that no `done` means no success and document the additive event before frontend rollout.

## Migration Plan

1. Add nullable lifecycle columns, then backfill rows with non-null `codeGenType` as `SUCCEEDED` and ambiguous null-type rows as `PENDING`; add the status/start-time index and finally enforce the status default/non-null constraint.
2. Deploy code that reads and writes the new columns, exposes safe status views, and still treats null `codeGenType` as first-generation behavior.
3. Enable the explicit timeout hierarchy and heartbeat defaults, ensuring reverse-proxy idle and maximum request limits exceed the servlet async timeout in each deployment.
4. Roll out frontend handling for `error` and provisional chunks; older clients remain protected by the absence of `done`.
5. Monitor timeout, cancellation, stale reconciliation, failure category, and attempt duration without logging prompts or generated code.
6. Roll back application code only after restoring compatibility for new columns; additive columns and status values can remain because older code ignores them.

## Open Questions

None. The exact duration defaults remain configurable, but their ordering and finite-bound requirements are part of the contract.
