## Why

Long-running AI project generation is currently bounded by the OpenAI client's whole-request timeout, which aborts an otherwise active stream and surfaces as `LangChain4jException: closed`. Once SSE output has started, that error escapes into the JSON exception handler, producing secondary converter failures while clients retain an ambiguous partial response and the application has no durable generation status.

## What Changes

- Externalize coherent upstream AI streaming, overall generation, servlet asynchronous-response, heartbeat, and stale-attempt time limits with safe validated defaults suitable for complete Vue projects.
- Add durable latest-generation lifecycle data for each application, including `PENDING`, `GENERATING`, `SUCCEEDED`, and `FAILED`, a unique attempt identifier, bounded safe failure details, and start/finish timestamps.
- Transition lifecycle state conditionally by application and attempt so lease loss, cancellation, retries, or late callbacks cannot overwrite a newer attempt.
- Extend the generation event model with one named SSE `error` event carrying a stable JSON error contract; asynchronous failures are finalized inside the stream and no longer reach the JSON controller advice after an SSE response is committed.
- Preserve `done` as the only success signal and emit it only after provider completion, parsing, validation, build, publication, preview preparation, required persistence, and success-state finalization.
- Send SSE comment heartbeats without changing AI content chunks, accumulated source, or persisted history.
- Keep failed first generation retryable and preserve the prior complete project after failed regeneration; clients can query the latest generation status and must discard an unconfirmed streamed draft.
- Strengthen compensation and tests so interrupted streams and late finalization failures cannot leave successful metadata, history, or incomplete stable project output.
- Provide an explicit migration for existing application rows, backfilling completed applications as `SUCCEEDED` and ambiguous never-completed rows as `PENDING`.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `app-management`: Add durable latest-generation lifecycle state, bounded failure reporting, long-running SSE timeout/heartbeat behavior, and a named terminal error event.
- `vue-project-generation`: Make abnormal provider termination and downstream finalization failure explicit completeness failures that cannot publish or retain successful metadata.

## Impact

- Affects the application table and migration SQL, application domain/VO models, generation service orchestration, SSE controller events, AI and MVC timeout configuration, and generation recovery/compensation behavior.
- Requires frontend consumers to handle the named `error` event and continue treating absence of `done` as an unsuccessful attempt.
- Expands unit and integration coverage for exact timeout configuration, whitespace-preserving heartbeats, lifecycle transitions, stale callbacks, cancellation, stream failure, parser/build/finalization failure, and stable-directory preservation.
