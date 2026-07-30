## 1. Redis Infrastructure And Configuration

- [x] 1.1 Replace direct `spring-session-data-redis` and the LangChain4j community Redis starter with Spring Boot's Session Data Redis starter, retain programmatic Caffeine, and verify the resolved Spring Data Redis client/auto-configuration dependency tree.
- [x] 1.2 Remove `RedisChatMemoryStoreConfig`, the partial `@MemoryId` wiring, and the Redis embedding auto-configuration exclusion so the project compiles with a stateless LangChain4j AI service.
- [x] 1.3 Add validated typed properties for chat-memory bounds/TTLs/cache capacity and application-processing lease duration/renewal, rejecting every non-positive or internally inconsistent value at startup.
- [x] 1.4 Replace hard-coded/custom Redis YAML entries with Boot 4 supported, environment-driven connection properties plus distinct Session, chat-memory, invalidation, and lock namespaces; remove `spring.session.store-type` and `spring.data.redis.ttl`.
- [x] 1.5 Add configuration tests that verify property defaults, invalid values, secret-free tracked configuration, Boot Redis Session activation, and absence of unrelated embedding-store auto-configuration without connecting to a live Redis server.

## 2. Distributed Authentication Session

- [x] 2.1 Add a minimal serializable `AuthenticatedSession` model containing only positive `userId` and a non-reversible credential fingerprint, together with validated construction and timing-safe fingerprint comparison.
- [x] 2.2 Configure the named Spring Session Redis serializer for the supported snapshot format and reject malformed or unexpected login-state attributes without enabling arbitrary persistence-entity deserialization.
- [x] 2.3 Update login and password-change flows to rotate/create the session id and store the minimal snapshot instead of `User`; update logout to invalidate Redis-backed state.
- [x] 2.4 Update `getLoginUser` to load the current active user from MySQL, compare the current credential fingerprint, invalidate mismatched/deleted/malformed sessions, and continue using current role/profile/initial-password data.
- [x] 2.5 Extend user-service tests for login fixation protection, snapshot field safety, cross-instance-compatible serialization, malformed attributes, password change, administrator reset invalidation, deleted users, logout, and mapper failures.

## 3. Bounded Conversation Memory Core

- [x] 3.1 Add immutable internal memory message/snapshot models using `Long appId`, terminal `lastHistoryId`, validated `user`/`ai` roles, defensive copies, and an explicit schema version.
- [x] 3.2 Extract deterministic prior-message truncation and structured prompt construction from `AppServiceImpl`, preserving defaults of 10 records, 6,000 characters per record, 24,000 total characters, chronological roles, and the explicit truncation marker.
- [x] 3.3 Add an internal bounded ChatHistory query that selects the latest active records for one app with exclusive `id < beforeId` semantics and does not weaken the existing owner/administrator history API authorization.
- [x] 3.4 Implement a Spring Data Redis snapshot repository using application-owned JSON, versioned prefixed keys, atomic payload/version/TTL writes, bounded deserialization, version reads, idempotent deletion, and no RedisJSON requirement.
- [x] 3.5 Implement the programmatic Caffeine near cache with immutable values, configured capacity/expiry, application-scoped eviction, Redis-version validation, and no use of unverified local state during Redis failure.
- [x] 3.6 Implement cross-instance invalidation publication/listening for snapshot refresh and deletion, with application-id validation and content-free messages.
- [x] 3.7 Implement `ChatMemoryService` read-through behavior across Caffeine, Redis, and MySQL, including empty snapshots, cache refill, Redis-error MySQL fallback, current-record exclusion, and first-generation bypass.
- [x] 3.8 Implement terminal refresh and purge operations: rebuild from durable history after success/failure/cancellation, treat refresh failure as recoverable with local eviction, and treat deletion purge failure as a required deletion failure.
- [x] 3.9 Add focused memory tests for application isolation, ids above integer range, ordering, every size bound, deterministic truncation, defensive copies, cache hits/misses/expiry/version mismatch, malformed Redis data, Redis fallback, MySQL failure, invalidation, refresh, and purge.

## 4. Distributed Application Processing Lease

- [x] 4.1 Implement `AppProcessingLeaseManager` acquisition with `SET NX PX`, unpredictable owner tokens, full `Long appId` namespaced keys, immediate busy response, and a reactive resource object that is not thread-affine.
- [x] 4.2 Add token-checked atomic Redis scripts for lease renewal and release so an expired owner cannot renew or delete a newer owner's lease.
- [x] 4.3 Add scheduled renewal, lease-loss signaling, deterministic scheduler cleanup, and expiry-based crash recovery; validate renewal interval is safely below lease duration.
- [x] 4.4 Add focused lease tests for concurrent instances, busy keys, Redis acquisition failure, successful renewal, token mismatch, expiry, loss signaling, release from a different callback thread, double cleanup, and ids above integer range without a live Redis dependency.

## 5. Generation And Deletion Integration

- [x] 5.1 Route `chatToGenCode` through the distributed processing lease and preserve immediate same-app conflict behavior, lazy per-subscription state, and release on validation failure, success, error, or cancellation.
- [x] 5.2 Replace direct MySQL prompt assembly with `ChatMemoryService`: persist the current user row first, pass its id as `beforeId`, bypass memory for initial generation, and send the exact later message to the model once.
- [x] 5.3 Refresh memory after successful code generation and successful plain conversation without changing emitted chunks, preview/code publication, AI history ordering, `codeGenType`, or the successful `done` event when refresh fails.
- [x] 5.4 Refresh or invalidate memory after safe failure/cancellation persistence, ensure late provider completion cannot commit memory, and route lease loss through the existing failure/rollback path with no `done` event.
- [x] 5.5 Route owner and administrator deletion through the same distributed lease; purge Redis/Caffeine memory after undeployment preparation and before the application/history transaction, preserving existing rollback/reconciliation behavior.
- [x] 5.6 Extend AppService generation tests for cached and recovered context, exclusive `beforeId`, no duplicated current message, initial bypass, significant whitespace/chunks, normal conversation, refresh failure success semantics, Redis/MySQL errors, lease contention/loss, cancellation, and exact `done` ordering.
- [x] 5.7 Extend owner/admin deletion tests for lease contention, absent memory, successful purge ordering, purge failure, database rollback after purge, deployment restoration, unauthorized/missing apps, and lock release.

## 6. Operational Safety And Documentation

- [x] 6.1 Add content-free diagnostics for cache tier outcomes, MySQL recovery, invalidation, refresh/purge failure, lease acquisition/renewal/loss/release, and session validation categories without logging messages, generated code, cookies, credentials, or provider payloads.
- [x] 6.2 Document Redis runtime prerequisites and every supported environment property for local and deployed environments, including TLS, timeouts, namespaces, Session duration, memory TTL, local cache bounds, and lease timing without adding tracked secrets.
- [x] 6.3 Verify one-time session migration behavior and document that existing process-local sessions require reauthentication; verify Redis memory cold-start recovery requires no data backfill.
- [x] 6.4 Add an explicitly opt-in Redis integration smoke test or verification command for Session persistence, snapshot TTL/round-trip/purge, invalidation, and lease scripts while keeping it outside the normal unit-test suite.

## 7. Verification

- [x] 7.1 Run focused user-session, memory repository/service, lease, AI facade, ChatHistory, AppService generation/deletion, and application-context tests without live AI calls or mandatory Redis access.
- [x] 7.2 Run `mvn clean test` and resolve all failures, including exact streaming whitespace, success/error/cancellation ordering, no `done` on required failures, and no cache-only failure suppressing successful `done`.
- [x] 7.3 With an explicitly configured Redis service, verify login survives a backend restart/second instance, conversation memory recovers across instances, same-app concurrent processing is rejected, and deletion removes memory keys.
- [x] 7.4 Run `openspec validate add-redis-chat-memory --type change --strict`, `git diff --check`, and `git status --short`; confirm unrelated preview/user work remains untouched and plan to sync/archive `add-chat-history` before archiving this overlapping app-management delta.
