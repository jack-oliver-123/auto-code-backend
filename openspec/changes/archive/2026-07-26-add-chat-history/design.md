## Context

The application service currently owns generation authorization, selects the effective prompt, prevents concurrent work for one application, preserves raw SSE chunks, and emits `done` only after parsing, file publication, preview preparation, and required database updates succeed. Conversation content is not persisted. The repository already uses MyBatis-Plus logical deletion, exact camel-case database column names, synchronous MySQL access, Spring MVC with Reactor streams, and service-layer ownership checks.

The draft `chat_history` schema uses a second-precision creation timestamp as its cursor key. A user message and its AI response can share that timestamp, so an exclusive timestamp cursor can skip or duplicate records. Application deletion is logical, which also means a database foreign-key cascade would not run.

## Goals / Non-Goals

**Goals:**

- Persist every authorized generation attempt's backend-selected user message and its successful, failed, or cancelled AI outcome.
- Preserve AI chunks exactly while accumulating the successful reply for storage.
- Provide stable newest-first cursor retrieval for an application's chat UI and deterministic time-descending administrator moderation.
- Enforce owner-or-administrator visibility in the service layer.
- Remove associated active history atomically with application logical deletion while retaining existing deployment rollback behavior.
- Match the repository's domain, DTO, VO, mapper, service, controller, exception, OpenAPI, and testing conventions.

**Non-Goals:**

- Feeding stored history back into the AI model or introducing multi-turn model memory.
- Public history access, per-message editing/deletion, search indexing, retention policies, or history export.
- Changing the existing generation request body, SSE content shape, or `done` event payload.
- Adding a distributed application lock or a new external dependency.
- Automatically retrying a failed initial generation when a page is reopened.

## Decisions

### 1. Use one application-scoped history table and a two-value message type

`ChatHistory` will contain `id`, `message`, `messageType`, `appId`, `userId`, `createTime`, `updateTime`, and `isDelete`. `ChatHistoryMessageTypeEnum` will expose `USER("user")` and `AI("ai")` plus a value lookup method consistent with `UserRoleEnum`. `userId` identifies the authenticated owner who initiated the turn for both user and AI rows.

`message` will use `MEDIUMTEXT`; generated multi-file output can exceed the 64 KiB capacity of `TEXT`. History remains logically deleted to match the repository. Errors and cancellation are represented as safe AI messages rather than a third message type: controlled `BusinessException` messages may be summarized, unknown exceptions use a generic failure message, and stack traces, provider payloads, credentials, and filesystem paths are never stored.

Adding a separate status column was considered. It would make error styling easier, but the requested contract only requires user/AI differentiation and error retention. A status can be added later without changing cursor or ownership behavior.

### 2. Persist around the existing generation lifecycle

After validating the authenticated user, active application, ownership, generation state, and effective message, `AppServiceImpl` will persist the user row before preview/provider preflight or AI invocation. For first generation it stores the application's normalized `initPrompt`, never the ignored client message; later generations store the submitted message without trimming or normalizing it.

The generation stream uses a per-subscription accumulator that appends every non-null chunk exactly as emitted. It never trims, joins with separators, or changes the chunk sent through SSE. The complete AI reply is persisted during successful finalization before the `Completed` event can be emitted. A history insert failure is therefore a required database-update failure: it prevents `done` and allows the unresolved code publication to roll back.

An outer error path records one safe AI failure entry after the user row exists and rethrows the original error so existing HTTP/SSE semantics remain unchanged. Failure-history persistence must not replace the original exception; a secondary persistence failure is logged and attached as suppressed where possible. An outer cancellation finalizer records one fixed cancellation message synchronously because cancellation cannot await an asynchronous database publisher. Per-subscription state prevents duplicate error/cancellation entries and skips terminal recording when validation, ownership, concurrency acquisition, or the initial user-history insert failed.

Successful model output may already have been streamed when preview or publication finalization fails. In that case the persisted terminal record is the safe failure outcome, not a falsely successful completed turn. The existing `codeGenType == null` check remains the backend source of truth for first generation.

### 3. Use an exclusive ID cursor for application history

`POST /chatHistory/list/page/vo` accepts `appId`, optional `beforeId`, and `pageSize`, defaulting to 10 and capped at 20. The service verifies that the active application belongs to the caller or that the caller is an administrator. It queries active records with `appId = ?`, optional `id < beforeId`, `ORDER BY id DESC`, and `LIMIT pageSize + 1`, without a count query.

The extra row determines `hasMore`. Returned records are reversed into chronological order for direct chat rendering; when more records exist, `nextCursor` is the oldest returned record's ID. An ID cursor avoids gaps from equal timestamps and remains stable when new messages arrive. The supporting index is `(appId, isDelete, id)`.

A timestamp-only cursor and offset pagination were rejected. The former is ambiguous at the schema's timestamp precision, while the latter shifts under concurrent inserts and performs progressively worse for deep history.

### 4. Use standard pagination for administrator moderation

`POST /chatHistory/admin/list/page/vo` is protected with the administrator role and accepts positive `pageNum`, `pageSize` up to 100, and optional exact filters for `appId`, `userId`, and `messageType`. Results are always ordered by `createTime DESC, id DESC`; client-controlled sort fields are not accepted. The index `(isDelete, createTime, id)` supports active-row moderation ordering.

The owner cursor endpoint and administrator endpoint return `ChatHistoryVO`, containing only `id`, `message`, `messageType`, `appId`, `userId`, and `createTime`. Persistence-only fields are not exposed.

### 5. Keep authorization in the chat-history service without a service cycle

`ChatHistoryServiceImpl` will use `AppMapper` for the small active-application ownership lookup and `UserRoleEnum` for the administrator bypass. `AppServiceImpl` can then depend on `ChatHistoryService` for recording and deletion without creating an `AppService`/`ChatHistoryService` dependency cycle. Controllers only validate transport inputs, resolve the login user, and delegate.

Both endpoints use `@AuthCheck`, so authentication and the initial-password restriction remain consistent with other protected APIs. A foreign application's history returns no-authority, while a missing or logically deleted application returns not-found. Controllers must not translate either response into an empty history result.

### 6. Delete application and history in one database transaction

The existing filesystem undeployment preparation remains outside the database transaction. Inside the existing deletion callback, a `TransactionTemplate` will logically delete the application and all active history rows for its `appId`. No history rows is a valid condition. A false application delete marks the transaction rollback-only; mapper exceptions abort and roll back both logical deletions.

The transaction commits before permanent undeployment cleanup. A transaction failure therefore flows through the existing deletion-failure reconciliation and restores the prepared deployment. This approach works for both owner and administrator deletion. A foreign-key cascade was rejected because logical deletion executes `UPDATE`, not physical `DELETE`.

### 7. Preserve frontend initialization semantics explicitly

An authorized empty cursor page returns `records: []`, `hasMore: false`, and `nextCursor: null`; the frontend may use only this successful empty response to auto-submit initialization. Authorization or not-found errors are not empty history. A failed first generation creates user and AI failure records, so reopening does not auto-retry indefinitely; a later explicit retry still uses `initPrompt` because `codeGenType` remains null.

## Risks / Trade-offs

- [Cross-resource atomicity between MySQL and generated files is not absolute] -> Persist successful AI history inside the existing reversible publication window, use the established rollback path, and test every database/file failure ordering that can be controlled in-process.
- [Synchronous JDBC work in Reactor terminal callbacks can briefly block an emission thread] -> Keep writes small and bounded; cancellation uses synchronous best-effort persistence because detached asynchronous work would be less reliable. Revisit a dedicated bounded scheduler only if profiling shows contention.
- [Failure-history persistence can itself fail during a database outage] -> Preserve and propagate the original generation error, log the secondary failure, and never emit `done`; no design can guarantee a database record while the database is unavailable.
- [Large AI replies increase database and administrator response size] -> Use `MEDIUMTEXT`, cap ordinary pages at 20 and administrator pages at 100, and avoid count queries for chat scrolling.
- [ID order is insertion order rather than an external event timestamp] -> The in-process per-app lock serializes generation records for one application; `createTime` remains available for display and administrator ordering.

## Migration Plan

1. Update the canonical initialization schema with `CREATE TABLE IF NOT EXISTS chat_history`, `MEDIUMTEXT`, logical-delete fields, and the composite cursor/moderation indexes.
2. For any environment where the current draft table was already created, apply an equivalent `ALTER TABLE` before deploying the new backend because editing `CREATE TABLE IF NOT EXISTS` does not alter an existing table.
3. Deploy the backend after the schema is ready. No data backfill is required because no prior conversation store exists.
4. Roll back by deploying the previous backend; the unused history table can remain without affecting prior code. Preserve collected data unless an explicit destructive rollback is approved.

## Open Questions

None. A structured message-status field and retention policy are intentionally deferred capabilities.
