## Why

Application follow-up generation currently rebuilds model context directly from MySQL for every request, while the partially added Redis chat-memory wiring is not connected to the streaming generation path and cannot preserve the application's success, failure, cancellation, and publication ordering. Redis-backed HTTP sessions are also only partially configured, so multi-instance authentication and application-scoped conversation continuity are not yet reliable.

## What Changes

- Add application-managed conversation memory keyed by `Long appId`, using bounded Caffeine local snapshots, Redis distributed snapshots, and MySQL chat history as the recovery source.
- Load only prior messages before the current persisted user record, preserving application isolation and preventing the current message from being sent to the model twice.
- Refresh conversation memory only from terminal history that has been persisted by the existing generation lifecycle; provider completion alone does not commit memory.
- Preserve generation availability when the derived memory cache is unavailable by falling back to MySQL, without changing SSE chunks, `done` ordering, or persisted history semantics.
- Purge local and Redis conversation memory when an application is deleted, and prevent stale entries on failure or cancellation.
- Replace the incomplete Redis Session dependency/configuration with Spring Boot's Redis Session integration, namespaced keys, explicit duration-based expiry, and environment-driven Redis connection settings.
- Store a minimal serializable authenticated-user snapshot in the HTTP session instead of the full persistence entity while preserving password-change session invalidation.
- Replace the JVM-only per-application generation guard with Redis-coordinated exclusion for multi-instance deployments while retaining deterministic release on success, error, and cancellation.
- Remove the unused LangChain4j automatic Redis-memory wiring; model memory remains controlled by the application lifecycle rather than provider callback timing.
- Introduce no breaking HTTP endpoint, DTO, SSE framing, or chat-history response changes.

## Capabilities

### New Capabilities

- `chat-memory`: Application-scoped, bounded AI conversation context with Caffeine/Redis caching, MySQL recovery, lifecycle-controlled refresh, invalidation, and observability.
- `distributed-session`: Redis-backed HTTP sessions with safe authentication-state serialization, key isolation, expiration, and password-change invalidation.

### Modified Capabilities

- `app-management`: Follow-up generation consumes recovered application memory, generation exclusion works across backend instances, and application deletion removes derived conversation memory.

## Impact

- Affects AI service/facade signatures, application generation orchestration, chat-history internal reads, application deletion, user login/session handling, Redis configuration, and focused tests.
- Replaces the direct Spring Session and LangChain4j Redis starter wiring with Spring Boot-managed Redis infrastructure shared by sessions, memory storage, invalidation, and generation coordination.
- Adds programmatic Caffeine caching and typed chat-memory/session configuration; Redis becomes a required runtime dependency for authenticated deployments.
- Requires isolated Redis key namespaces and deployment configuration for host, port, credentials, timeouts, TLS, session duration, memory TTL, and local-cache bounds.
- Leaves MySQL `chat_history` as the durable audit and recovery source and does not change its public cursor or administrator APIs.
