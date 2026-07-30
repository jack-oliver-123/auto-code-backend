## 1. Generation Lifecycle Schema And Views

- [x] 1.1 Add `AppGenerationStatusEnum` and the latest-attempt fields to `App`; update `sql/init.sql` with safe defaults, the reconciliation index, and an explicit existing-database backfill/ALTER sequence.
- [x] 1.2 Initialize new applications as `PENDING`, backfill completed rows as `SUCCEEDED` and ambiguous null-type rows as `PENDING`, and verify existing first-generation detection remains based on the persisted generation type.
- [x] 1.3 Expose generation status and bounded safe failure information through owner and administrator VOs while excluding attempt ids and failure details from public responses; update allowed administrator filters/sorts only where required.
- [x] 1.4 Add model, mapping, query, visibility, enum-value, and migration-shape tests for the lifecycle fields.

## 2. Typed Generation Timing Configuration

- [x] 2.1 Add validated `app.generation.*` properties for provider, complete-attempt, servlet async, heartbeat, and stale-attempt durations, including cross-property checks against the Vue build timeout.
- [x] 2.2 Wire the typed values into LangChain4j streaming-model and Spring MVC asynchronous-response configuration through environment-backed tracked defaults; remove fixed local timeout behavior without exposing model credentials.
- [x] 2.3 Add configuration-binding tests for defaults, environment overrides, non-positive values, invalid ordering, and insufficient build/finalization allowance.

## 3. Attempt State Transitions And Recovery

- [x] 3.1 Implement application/owner/attempt/status conditional transition helpers for starting, succeeding, failing, and cancelling a generation attempt with bounded application-owned failure codes/messages.
- [x] 3.2 Integrate `GENERATING` before current-turn history/provider work and finalize every timeout, provider, parser, build, publication, preview, persistence, lease-loss, and cancellation path without allowing stale callbacks to change a newer attempt.
- [x] 3.3 Move successful AI history, initial `codeGenType`, and matching `SUCCEEDED` transition into one `TransactionTemplate` transaction; move safe failure history and matching `FAILED` transition into a separate terminal transaction while preserving the original error on secondary failure.
- [x] 3.4 Extend the processing-lease abstraction with a safe ownership-presence check and add bounded stale-attempt reconciliation that changes only an expired exact attempt after Redis confirms the lease is absent.
- [x] 3.5 Add focused service tests for every lifecycle transition, invalid/pre-attempt requests, mapper failure, transaction rollback, failed first generation retry, failed regeneration preservation, stale callbacks, stale reconciliation, lease uncertainty, and cancellation.

## 4. SSE Terminal Events And Deadlines

- [x] 4.1 Extend `AppGenerationEvent` with terminal failure and heartbeat representations and define a stable JSON `error` payload without changing the existing content or `done` payloads.
- [x] 4.2 Convert asynchronous generation failures to exactly one named SSE `error` event inside the reactive boundary, complete normally afterward, and retain JSON HTTP errors only for synchronous pre-stream failures.
- [x] 4.3 Add comment-only periodic heartbeats that stop at `done`, `error`, or cancellation and never enter AI accumulation, parser input, persisted history, response bounds, or chat memory.
- [x] 4.4 Apply the configured complete-attempt Reactor deadline, cancel provider/build work on expiry, release the processing lease, roll back unresolved resources, and record content-free timeout diagnostics.
- [x] 4.5 Add controller and virtual-time reactive tests for exact chunk whitespace, heartbeat isolation, one terminal event, no `done` on failure, no JSON converter cascade, synchronous JSON errors, timeout, lease loss, and cancellation.

## 5. Completion And Publication Integrity

- [x] 5.1 Make post-success code/preview backup cleanup non-failing while retaining failure and rollback semantics for all preparation work before the success transaction.
- [x] 5.2 Verify abnormal upstream closure never reaches Vue parse/build/publication even if received text resembles a complete envelope, and never persists partial content as a successful AI reply or initial generation type.
- [x] 5.3 Add first-generation and regeneration tests for upstream closure before/after apparent markers, response-limit failure, parser/build failure, success-transaction failure, preview preparation failure, cleanup failure, and preservation of the prior stable directory.

## 6. Documentation And Verification

- [x] 6.1 Document the timeout environment variables, required ordering, heartbeat/error/done wire contract, latest-attempt semantics, frontend provisional-chunk rule, migration/backfill, and reverse-proxy timeout requirement.
- [x] 6.2 Run the narrow generation/config/controller/Vue test suites, then run `mvn clean test` without live AI calls.
- [x] 6.3 Run `openspec validate harden-ai-generation-stream --type change --strict`, `git diff --check`, and `git status --short`; inspect the final diff so unrelated existing worktree changes remain untouched.
