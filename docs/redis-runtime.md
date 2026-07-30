# Redis Runtime Configuration

Redis is required for distributed HTTP sessions, bounded conversation-memory
snapshots, cross-instance cache invalidation, and application-processing leases.
MySQL `chat_history` remains the source of truth for conversation history.
There is no process-local fallback for any Redis-backed capability because it
would break authentication and coordination consistency across instances.

## Local Prerequisite

Start a Redis server reachable by the backend. A disposable local instance can
be started with Docker:

```shell
docker run --name auto-code-redis -p 6379:6379 redis:7-alpine
```

Do not commit Redis passwords or TLS key material. Supply secrets through the
environment or the ignored `application-local.yml`.

## Environment Properties

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `REDIS_DATABASE` | `0` | Redis logical database |
| `REDIS_USERNAME` | empty | ACL username |
| `REDIS_PASSWORD` | empty | ACL password |
| `REDIS_CONNECT_TIMEOUT` | `2s` | Connection timeout |
| `REDIS_READ_TIMEOUT` | `2s` | Command timeout |
| `REDIS_SSL_ENABLED` | `false` | Enable TLS; custom CAs use the JVM truststore |
| `REDIS_STARTUP_CHECK_ENABLED` | `true` | Require a successful Redis `PING` before readiness |
| `SESSION_TIMEOUT` | `30d` | Inactive HTTP Session lifetime |
| `SESSION_COOKIE_MAX_AGE` | `30d` | Browser Session cookie lifetime |
| `SESSION_COOKIE_SECURE` | `false` | Send the Session cookie only over HTTPS |
| `SESSION_COOKIE_SAME_SITE` | `lax` | Session cookie `SameSite` policy |
| `SESSION_REDIS_NAMESPACE` | `auto-code:session` | Session key namespace |
| `CHAT_MEMORY_HISTORY_LIMIT` | `10` | Maximum cached history records (1-100) |
| `CHAT_MEMORY_MESSAGE_MAX_CHARS` | `6000` | Maximum characters per cached record |
| `CHAT_MEMORY_TOTAL_MAX_CHARS` | `24000` | Maximum characters plus framing per snapshot |
| `CHAT_MEMORY_PAYLOAD_MAX_BYTES` | `262144` | Maximum Redis JSON payload size |
| `CHAT_MEMORY_SNAPSHOT_TTL` | `7d` | Redis snapshot lifetime |
| `CHAT_MEMORY_CACHE_MAXIMUM_SIZE` | `1000` | Per-instance Caffeine entry capacity |
| `CHAT_MEMORY_CACHE_TTL` | `10m` | Per-instance Caffeine entry lifetime |
| `CHAT_MEMORY_REDIS_KEY_PREFIX` | `auto-code:chat-memory:v1:` | Snapshot key namespace |
| `CHAT_MEMORY_INVALIDATION_CHANNEL` | `auto-code:chat-memory:invalidation:v1` | Pub/Sub channel |
| `APP_PROCESSING_LEASE_DURATION` | `30s` | Distributed lease lifetime |
| `APP_PROCESSING_LEASE_RENEWAL_INTERVAL` | `10s` | Lease renewal interval; must be below half the lifetime |
| `APP_PROCESSING_LEASE_RENEWAL_PARALLELISM` | `4` | Concurrent lease renewal workers (1-64) |
| `APP_PROCESSING_LEASE_REDIS_KEY_PREFIX` | `auto-code:processing-lease:v1:` | Lease key namespace |

All sizes and durations must be positive. The total memory character limit must
be at least the per-message limit. Namespace changes intentionally isolate old
keys from a new deployment.

## Startup And Outage Behavior

Provision Redis before starting a normal backend instance. The default-enabled
startup check uses the configured shared connection factory and accepts only a
`PONG` response. A connection failure or unexpected response aborts startup;
the diagnostic identifies only Redis, the operation category, port, and logical
database. It never logs credentials, cookies, Session data, prompts, or generated
source.

`REDIS_STARTUP_CHECK_ENABLED=false` is reserved for infrastructure-independent
test contexts and narrowly scoped tooling. Do not use it to make a production
instance appear ready without Redis. Disabling the probe does not install a
fallback and does not make Session or lease operations work without Redis.

After a successful startup, a confirmed Redis connection failure during HTTP
Session handling marks readiness unavailable. If the response is still
writable, the API returns HTTP 503 and this stable JSON contract:

```json
{"code":50300,"data":null,"message":"依赖服务暂不可用"}
```

The handler does not retry the request, redispatch to `/error`, create another
Session, or attempt a second Session save. If an SSE or other response is already
committed, its status, content type, and body are left unchanged and the stream
is terminated without a JSON rewrite.

## Liveness And Readiness

- `GET /api/health/live` reports process liveness and never accesses an HTTP
  Session. `GET /api/health/check` remains a compatibility alias.
- `GET /api/health/ready` performs a bounded Redis `PING`, then checks the
  configured Vue Builder runtime and trusted local image. It returns HTTP 503
  with `data: "redis"` or `data: "vue-builder"` for the failed dependency and
  restores readiness after the dependency recovers. Builder results use the
  short cache documented in `app-deployment.md`.

Readiness is advisory for traffic routing. Every real Session, memory, and lease
operation still uses Redis and relies on that operation's result.

## Migration And Recovery

Switching from process-local sessions and locks requires a coordinated cutover.
Drain every legacy backend instance before routing any traffic to the new
version; mixed-version routing is unsupported. Legacy instances neither honor
the Redis application lease nor write the new Session attribute format, so a
mixed fleet can concurrently mutate one app and can alternate users between
authenticated and unauthenticated responses. Existing users must authenticate
again because old in-memory sessions are not copied to Redis. Only a user ID and
a non-reversible credential fingerprint are stored in the new Session attribute.

After legacy instances are drained, run the lifecycle migration and backfill in
`app-generation-stream.md`, verify completed legacy rows are `SUCCEEDED`, deploy
the new version, and only then restore traffic.

Conversation memory needs no backfill. A cache miss or cold Redis start rebuilds
the bounded snapshot from active MySQL `chat_history` rows. Deleting an app
requires Redis memory purge before the application/history transaction proceeds.

## Opt-in Smoke Test

With Redis configured through the variables above, run:

```shell
mvn -Predis-smoke-tests verify
```

This profile performs real Redis checks for Session persistence, snapshot
round-trip/TTL/purge, Pub/Sub invalidation, and token-checked lease scripts. It
also verifies that a real `PING` restores readiness. It does not run live AI
tests and is not part of the normal `mvn test` suite. Ordinary Spring test
contexts explicitly disable only the startup probe and therefore require no live
Redis process.
