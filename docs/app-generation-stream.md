# Application Generation Stream Operations

Application generation is one bounded SSE request with a durable latest-attempt
lifecycle in MySQL. MySQL is authoritative for status and chat history. Redis
provides the cross-instance application processing lease; Redis chat memory is
derived state and is not part of generation success.

## Time Limits

| Property | Environment variable | Default |
| --- | --- | --- |
| `app.generation.heartbeat-interval` | `APP_GENERATION_HEARTBEAT_INTERVAL` | `15s` |
| `app.generation.provider-timeout` | `APP_GENERATION_PROVIDER_TIMEOUT` | `5m` |
| `app.generation.complete-attempt-timeout` | `APP_GENERATION_COMPLETE_ATTEMPT_TIMEOUT` | `8m` |
| `app.generation.servlet-async-timeout` | `APP_GENERATION_SERVLET_ASYNC_TIMEOUT` | `9m` |
| `app.generation.stale-attempt-age` | `APP_GENERATION_STALE_ATTEMPT_AGE` | `12m` |
| reconciliation initial delay | `APP_GENERATION_RECONCILIATION_INITIAL_DELAY_MS` | `60000` |
| reconciliation interval | `APP_GENERATION_RECONCILIATION_INTERVAL_MS` | `60000` |

Startup validates this strict ordering:

```text
heartbeat < provider < complete attempt < servlet async < stale attempt age
complete attempt > provider + Vue build timeout + 30 seconds
```

The provider limit is applied to both configured LangChain4j models. The
complete-attempt limit also covers parsing, validation, container build,
publication, preview preparation, and database finalization.

For a reverse proxy or ingress, disable SSE response buffering, set its idle
timeout above the heartbeat interval, and set its absolute request timeout
above `APP_GENERATION_SERVLET_ASYNC_TIMEOUT`. The model gateway timeout must be
at least `APP_GENERATION_PROVIDER_TIMEOUT`. A proxy must not transform SSE
comments or retry a partially committed generation request.

## SSE Contract

`POST /api/app/chat/gen/code` returns `text/event-stream` after synchronous
request and authentication validation succeeds.

- AI chunks are unnamed content events whose JSON data is exactly
  `{"d":"<chunk>"}`. Application code preserves chunk whitespace and newlines.
- Keep-alives are comment-only frames such as `:keep-alive`. They are transport
  data only and never enter source accumulation, parsing, response limits,
  history, or chat memory.
- Success ends with exactly one named `done` event containing the existing
  `previewUrl` and `expiresAt` JSON payload.
- Asynchronous failure ends with exactly one named `error` event containing
  `code`, a safe `message`, and `status: "FAILED"`, then the stream completes
  normally. It never emits `done`.
- Client cancellation or an unwritable connection may prevent an error event
  from reaching the browser. EOF without `done` is always unsuccessful.

Frontend consumers must treat every content chunk from the active request as a
provisional draft. Render it as progress if useful, but commit generated-source
state only after `done`. On `error`, cancellation, or EOF without `done`, discard
that request's provisional chunks and query the application detail again.

## Latest-Attempt Lifecycle

New applications start as `PENDING`. A validated owner request holding the
application lease atomically starts one opaque attempt as `GENERATING` before
current-turn history or provider work. The exact application, owner, attempt
id, and `GENERATING` state are required by every terminal update.

`SUCCEEDED` means the matching latest attempt completed provider output,
parsing/build, rollback-capable code publication, preview preparation, AI
history, initial generation type when applicable, and the success transaction.
`FAILED` describes the latest attempt and does not mean an older stable project
was deleted. A failed first attempt remains retryable with a null generation
type; a failed regeneration preserves the prior stable code, preview, and type.

Owner and administrator detail responses expose status, bounded application-
owned failure details, and lifecycle timestamps. Public responses expose only
status. No response exposes the opaque attempt id, provider payload, generated
source, stack trace, credentials, or raw exception message.

Cancellation, timeout, lease loss, provider failure, parsing/build failure, and
required persistence failure all try to write one matching `FAILED` transition
and a safe AI history outcome. A stale callback affects zero rows. The scheduled
reconciler scans at most 100 expired `GENERATING` rows per run and marks an exact
attempt failed only when Redis confirms its application lease is absent. Redis
errors or an unknown lease result leave the row unchanged.

## Existing Database Migration

This is a coordinated cutover, not a rolling migration. Drain every legacy
backend instance and stop application create/generate/deploy/delete traffic
before running the additive migration. Legacy instances use only process-local
generation locks and do not write lifecycle fields, so old and new binaries must
never share traffic. Deploy the new version only after the migration and
backfill complete. The canonical copy is retained beside the table definition
in `sql/init.sql`.

```sql
ALTER TABLE app
    ADD COLUMN generationStatus varchar(32) DEFAULT 'PENDING' NULL AFTER codeGenType,
    ADD COLUMN generationAttemptId varchar(64) NULL AFTER generationStatus,
    ADD COLUMN generationFailureCode varchar(64) NULL AFTER generationAttemptId,
    ADD COLUMN generationFailureMessage varchar(256) NULL AFTER generationFailureCode,
    ADD COLUMN generationStartedTime datetime(3) NULL AFTER generationFailureMessage,
    ADD COLUMN generationFinishedTime datetime(3) NULL AFTER generationStartedTime;

UPDATE app
SET generationStatus = CASE
    WHEN codeGenType IS NOT NULL THEN 'SUCCEEDED'
    ELSE 'PENDING'
END
WHERE generationStatus IS NULL;

ALTER TABLE app
    MODIFY COLUMN generationStatus varchar(32) DEFAULT 'PENDING' NOT NULL;

CREATE INDEX idx_generationStatus_startedTime_id
    ON app (generationStatus, generationStartedTime, id);
```

Rows with a persisted generation type are backfilled as `SUCCEEDED`; ambiguous
rows without one are `PENDING`. First-generation behavior remains based on the
persisted `codeGenType`, not the lifecycle status. Confirm the index does not
already exist before executing the final statement. Before routing traffic,
verify that no row has a null status and that no row with a non-null
`codeGenType`, null attempt id, and `PENDING` status remains from a legacy
completion.
