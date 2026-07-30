## Context

The backend already persists exact user and terminal AI chat-history rows in MySQL, serializes one generation per application inside a JVM, preserves raw AI chunks through SSE, publishes generated files reversibly, and emits `done` only after required publication and database work. Follow-up requests currently query the latest 10 MySQL rows and embed a bounded JSON history block in the model prompt.

The partial Redis work in the current working tree adds Spring Session, Caffeine, the LangChain4j community Redis starter, a `RedisChatMemoryStore` bean, and `@MemoryId` to one non-streaming method. It does not compile because callers still use the old method signature, it does not affect the streaming methods used by the application, and `AiServices` has no `ChatMemoryProvider`. The community store also defaults to RedisJSON, has no configured key prefix, opens its own Jedis client, and does not share Spring Boot's Redis connection infrastructure.

More importantly, LangChain4j AI Services add the user message to `ChatMemory` before provider invocation and add the AI message when the provider stream completes. Application success occurs later, after parsing, staged publication, preview issuance, chat-history persistence, and required application updates. The Reactor adapter does not propagate downstream cancellation to the provider. Automatic provider-level memory can therefore retain a user-only turn on provider failure, retain an AI success after application parsing fails, or accept a late AI message after the browser has cancelled. That lifecycle is incompatible with the existing application invariants.

Spring Session is also incomplete. The direct `spring-session-data-redis` module does not provide Spring Boot 4's complete Redis Session auto-configuration, `spring.session.store-type` is not a supported Boot 4 property, and the current session stores the non-serializable `User` entity including its encoded password.

This change crosses AI generation, history, deletion, authentication, Redis infrastructure, and reactive resource ownership. It must preserve all existing API, authorization, SSE, file publication, and MySQL history behavior while allowing multiple backend instances.

## Goals / Non-Goals

**Goals:**

- Make bounded application conversation context available through a recoverable Caffeine/Redis/MySQL hierarchy.
- Keep MySQL chat history authoritative and commit derived model memory only from persisted terminal state.
- Prevent current-message duplication and preserve the existing first-generation prompt decision.
- Share authenticated sessions safely across instances without serializing the persistence entity or encoded password.
- Coordinate generation and deletion of one application across instances with a renewable, non-thread-affine Redis lease.
- Remove Redis keys when an application is deleted and keep all Redis key families namespaced.
- Degrade memory reads to MySQL without changing required SSE completion semantics.
- Keep normal tests deterministic and independent of a running Redis server or live AI provider.

**Non-Goals:**

- Replacing MySQL chat history with Redis or changing history pagination/moderation APIs.
- Persisting hidden model reasoning, provider metadata, tool calls, or partial streaming fragments as memory.
- Summarization, semantic/vector memory, cross-application memory, or public memory access.
- Changing HTTP request DTOs, SSE content framing, generated-code formats, or preview contracts.
- Guaranteeing authenticated operation while Redis Session itself is unavailable.
- Adding a new chat-history status column or otherwise migrating the existing history schema.

## Decisions

### 1. Keep model memory under application lifecycle control

The AI service remains stateless from LangChain4j's perspective. The incomplete `@MemoryId` change and `RedisChatMemoryStore`/community starter wiring will be removed. `AppServiceImpl` will continue to own effective-message selection and terminal orchestration, while a new `ChatMemoryService` owns bounded context retrieval, refresh, and purge.

For a later generation, the application service first persists the exact current user message and receives its history id. It then asks `ChatMemoryService` for prior context with `beforeId = currentUserMessageId` and sends the returned structured context plus the exact current message through the existing facade. Initial generation bypasses follow-up context. The existing prompt builder and truncation behavior move behind the memory service rather than being duplicated in `AppServiceImpl`.

After success, safe failure, or cancellation history has reached its terminal persisted state, the memory service reloads the latest bounded MySQL rows and refreshes the derived snapshot. It never promotes a provider response merely because the provider completed. A cache refresh is best-effort: failure evicts local state and is recorded without content, while the original SSE outcome remains unchanged.

Automatic LangChain4j `ChatMemoryProvider` was rejected because its write timing precedes application commit and its streaming adapter cannot reconcile downstream cancellation or later parser/publication failure. Snapshot-and-rollback around automatic memory was also rejected because a cancelled provider can complete after rollback and reintroduce a late message.

### 2. Store a bounded, versioned snapshot instead of an unbounded transcript

The internal representation will be an immutable `ChatMemorySnapshot` containing `appId`, `lastHistoryId`, and ordered `ChatMemoryMessage` values with history id, role, and content. It contains only the latest records that fit the configured limits. Defaults preserve the current behavior: 10 prior messages, 6,000 characters per message, and 24,000 characters total. Truncation retains deterministic head/tail portions separated by the existing explicit marker. Durable rows are never changed.

Redis uses a versioned key such as `auto-code:chat-memory:v1:{appId}` with a hash or equivalent atomic value containing the terminal history version and JSON payload. An atomic Redis script writes payload/version and expiration together. The JSON shape is application-owned and does not require RedisJSON. The Redis TTL defaults independently from HTTP Session TTL and is refreshed only on terminal snapshot writes.

Using LangChain4j `ChatMessage` polymorphic serialization directly was rejected. A small application-owned DTO avoids unsafe polymorphic deserialization, makes schema/version checks explicit, and stores only the fields needed to rebuild the bounded prompt.

### 3. Use Caffeine as a coherent near cache, not as the source of truth

Caffeine holds immutable snapshots by `Long appId`, with configurable maximum capacity and a short expiry. Each entry includes the terminal `lastHistoryId`. Redis change notifications evict the corresponding entry on every active instance. Before trusting a retained local candidate, the service may compare its version with lightweight Redis metadata; when Redis cannot validate a local candidate, that candidate is evicted and MySQL is used instead.

The read path is:

```text
current user row persisted
          |
          v
Caffeine candidate --version/invalidation--> Redis snapshot
          | miss/invalid                   | miss/invalid/error
          +--------------------------------+---------->
                                                     MySQL rows with id < beforeId
                                                               |
                                                               v
                                                  bounded snapshot + cache refill
```

This design uses local memory to avoid repeatedly transferring and deserializing a large snapshot while retaining a distributed version check and a short staleness bound. Caffeine's programmatic API is preferred over `@Cacheable` because the service needs exclusive `beforeId` semantics, defensive copies, targeted cross-instance invalidation, and explicit failure fallback.

An unvalidated local-cache fallback during Redis errors was rejected: it could send stale context after a turn completed on another instance. MySQL is the safe fallback.

### 4. Share Spring Boot Redis infrastructure

Use `spring-boot-starter-session-data-redis`, which supplies Boot's Redis Session and Spring Data Redis infrastructure, and use the resulting `RedisConnectionFactory`, `StringRedisTemplate`, serializers, listener container, and script execution for application Redis operations. Keep the direct Caffeine dependency for the programmatic near cache. Remove `langchain4j-community-redis-spring-boot-starter`, the custom `RedisChatMemoryStoreConfig`, and the application-level exclusion of Redis embedding auto-configuration.

Standard connection properties remain under `spring.data.redis`. Application settings move to typed properties such as `app.chat-memory.*` and `app.processing-lock.*`; custom `ttl` is not placed under Spring's connection namespace. Tracked YAML uses environment placeholders for Redis address, database, username, password, timeouts, and TLS and contains no production secret.

Keys are separated by stable prefixes:

- `auto-code:session:*` for Spring Session.
- `auto-code:chat-memory:v1:*` for conversation snapshots.
- `auto-code:chat-memory:invalidate` for invalidation notifications.
- `auto-code:app-processing-lock:*` for generation/deletion leases.

Using separate Jedis and Lettuce clients was rejected because it duplicates pools, authentication/TLS configuration, shutdown behavior, and health diagnosis.

### 5. Use a minimal authenticated-session snapshot

Replace the session's `User` attribute with a dedicated serializable `AuthenticatedSession` value containing `userId` and `credentialFingerprint`. The fingerprint is a one-way digest of the stored encoded credential; the encoded password itself is not stored in Redis. A named `springSessionDefaultRedisSerializer` uses an explicitly configured JSON serializer restricted to the supported session value rather than relying on default JDK serialization of arbitrary entities.

`getLoginUser` validates the snapshot, loads the current user from MySQL, computes the current fingerprint, and compares bytes with a timing-safe equality operation. It invalidates malformed sessions, missing/deleted users, and credential mismatches. Current role/profile/initial-password decisions therefore continue to come from MySQL. Login and successful password change rotate the session id; password change also replaces the fingerprint. An administrator reset naturally invalidates older snapshots on their next request.

Making `User` implement `Serializable` was rejected because it would persist profile, database, logical-deletion, timestamp, and encoded-password fields and would couple sessions to the persistence model. Storing only `userId` was rejected because it would weaken the current behavior that invalidates sessions after password changes.

### 6. Coordinate reactive processing with an owner-token Redis lease

The current `ConcurrentHashMap` set is replaced or wrapped by an `AppProcessingLeaseManager`. Acquisition uses Redis `SET key ownerToken NX PX leaseDuration`. The resource contains the positive `Long appId`, an unpredictable owner token, and lease state. A scheduler renews the TTL with an atomic script only when the stored token still matches. Release uses a compare-and-delete script, so an expired owner cannot delete a newer owner's lease. Process death leaves a bounded lease that expires automatically.

The lease is not a Java `Lock` and is not thread-affine. This matters because Reactor callbacks, provider threads, and bounded-elastic parsing can execute on different threads. The generation stream owns the lease as a reactive resource, merges a lease-loss signal into its error path, and releases/cancels renewal on completion, error, or cancellation. If renewal fails and ownership cannot be confirmed, final publication is aborted and the existing safe failure path runs when a user history row already exists.

Deletion acquires the same lease before undeployment preparation, so generation and deletion remain mutually exclusive across instances. Acquisition is non-blocking for the API: a busy application receives the existing operation-error behavior instead of keeping an HTTP/SSE request waiting indefinitely.

A thread-bound Redis lock registry was considered but rejected for this stream lifecycle. Handing a traditional `Lock` across asynchronous callback threads complicates ownership and unlock guarantees. The owner-token lease uses the established Redis atomic-lock pattern while remaining compatible with Reactor; its scripts and renewal/loss behavior require focused tests.

### 7. Treat ordinary memory refresh as derived, but deletion purge as required

Generation success depends on generated output and required database persistence, not on a derived cache. A failed terminal refresh logs a content-free warning/counter, evicts Caffeine, and allows `done`; a later turn recovers from MySQL. This avoids turning a completed user-visible generation into an error because an optimization failed.

Deletion has a stronger privacy/data-lifecycle requirement. While holding the processing lease and after deployment preparation, it deletes the Redis snapshot, publishes invalidation, and evicts the local entry before the application/history database transaction. If Redis deletion cannot be confirmed, deletion fails and existing undeployment reconciliation restores the site. If the later database transaction fails, the application and history remain authoritative and memory can be lazily reconstructed.

### 8. Keep diagnostics content-free and tests infrastructure-independent

Record cache tier, hit/miss/recovery/refresh/invalidation outcome, lease acquisition/loss/release, and session validation failure category without logging message bodies, generated code, cookies, Redis credentials, or provider payloads. Existing logging is sufficient initially; metrics can be added through the same events without changing behavior.

Unit tests use mocked/fake memory repositories, Redis script operations, clocks/schedulers, and AI streams. Full `mvn clean test` must not require Redis or make live AI calls. Optional Redis integration verification can use an explicit profile/environment, but it is not part of the normal suite.

## Risks / Trade-offs

- [Redis becomes required for authenticated requests and processing leases] -> Document deployment prerequisites, validate typed properties, provide a local Redis setup outside tracked secrets, and fail explicitly rather than using inconsistent local sessions or locks.
- [Caffeine can briefly retain an entry after a missed invalidation] -> Validate versions when needed, use short local expiry, never trust unvalidated local state during Redis failure, and recover from MySQL.
- [A lease can be lost during a long generation] -> Renew well before expiry, surface lease loss into the reactive error path, use token-checked scripts, roll back unresolved publication, and never emit `done` after uncertain ownership.
- [Redis refresh and MySQL terminal persistence are not one transaction] -> MySQL remains authoritative; refresh from MySQL after terminal persistence and treat cache write failure as recoverable.
- [MySQL recovery adds a query on cold/expired cache] -> Keep cursor-style bounded reads and repopulate both tiers; correctness is preferred over stale context.
- [Existing failure/cancellation rows have no explicit status column] -> Preserve the current latest-history reconstruction semantics in this change; adding typed terminal status or summarization remains a separate capability.
- [Session serialization format changes invalidate existing process-local sessions] -> Treat deployment as a one-time reauthentication boundary and use a versioned, minimal snapshot format going forward.
- [The same Redis deployment contains user sessions and conversation content] -> Use distinct namespaces, least-privilege network access, TLS/credentials in production, bounded TTLs, and content-free diagnostics; separate Redis databases/clusters can be configured later if operational isolation requires it.

## Migration Plan

1. Complete/sync the existing `add-chat-history` change before archiving this change so the merged `app-management` requirement retains both durable-history and memory behavior.
2. Provision Redis with persistence, authentication/TLS as applicable, memory limits, and connectivity from every backend instance. Configure distinct namespaces and environment-based values.
3. Replace dependencies and configuration, add typed properties, and verify application context construction without requiring a live connection during ordinary unit tests.
4. Introduce the minimal session snapshot and serializer. Existing local sessions will require users to log in again after deployment.
5. Add the Redis snapshot repository, Caffeine near cache, MySQL recovery path, and terminal refresh/invalidation integration while retaining current prompt bounds.
6. Add the owner-token processing lease and route generation plus owner/admin deletion through the shared lease abstraction.
7. Deploy one backend instance first, verify login/session persistence, memory warmup/fallback, deletion purge, and lease renewal, then add additional instances and verify cross-instance continuity and exclusion.
8. Roll back by deploying the previous backend. New Redis keys are namespaced and may be deleted explicitly or allowed to expire; MySQL history remains intact. Users may need to authenticate again after either direction of the session-format transition.

## Open Questions

None. Defaults are intentionally configurable, MySQL recovery is required, and cluster-wide generation/deletion exclusion is included because distributed sessions allow requests to move between instances.
