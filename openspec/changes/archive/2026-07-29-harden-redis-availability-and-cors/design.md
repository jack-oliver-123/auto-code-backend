## Context

Redis is shared by Spring Session, chat-memory snapshots and invalidation, and application processing leases. The architecture intentionally forbids a process-local fallback because requests may move between backend instances and generation/deletion exclusion must remain distributed. Boot currently creates Redis infrastructure lazily, so the application reports started even when `localhost:6379` is unavailable. Login can authenticate against MySQL, write a response, and then fail when `SessionRepositoryFilter` saves the new Session.

`SessionRepositoryFilter` is ordered at `Integer.MIN_VALUE + 50`, wraps the response, and invokes `commitSession()` both immediately before response commit and when its filter chain exits. A Redis exception from that boundary is outside MVC controller advice. Re-dispatching to `/error` repeats the same Session save and can produce a second exception. The backend currently has no dependency-unavailable error code or readiness semantics.

CORS is configured through an MVC configurer using a raw string split on commas. The environment variable can technically contain multiple origins, but whitespace is retained, empty/unsafe values are not rejected, and both tracked and ignored local defaults contain only `http://localhost:5173`. Credentialed requests from the common Vite fallback port 5174 therefore receive 403.

## Goals / Non-Goals

**Goals:**

- Refuse normal runtime startup clearly when the required Redis service cannot be reached.
- Return one stable HTTP 503 JSON response for recoverable runtime Redis connection outages when the response is still writable.
- Prevent recursive `/error` dispatch and incompatible rewrites after a response is committed.
- Report Redis readiness and recovery without exposing credentials.
- Preserve Redis-backed Session and lease correctness with no local fallback.
- Provide a validated explicit credentialed CORS allowlist with both standard local Vite ports enabled by default.
- Keep normal unit tests independent of a live Redis process while retaining an opt-in real Redis integration suite.

**Non-Goals:**

- Adding Redis clustering, Sentinel, a circuit-breaker library, or a second Redis client.
- Retrying non-idempotent HTTP requests automatically after a Session persistence failure.
- Falling back to in-memory Session, conversation snapshots, invalidation, or application locks.
- Allowing arbitrary localhost ports, wildcard origins, opaque `null` origins, or dynamic origin reflection.
- Replacing the existing Session serialization format or Redis key namespaces.

## Decisions

### 1. Fail normal runtime startup with an explicit Redis probe

Add a small Redis dependency probe using the existing `RedisConnectionFactory`. An `ApplicationRunner` executes `PING` before the application is reported ready. A missing connection, non-`PONG` result, or Redis connection exception throws a dedicated startup exception whose message identifies only the dependency, configured non-secret endpoint/database, and remediation category. Connection credentials are never logged.

The live probe is enabled by default for normal runtime. Test application contexts explicitly replace or disable only the startup probe so `mvn clean test` still requires no infrastructure; the opt-in Redis smoke profile keeps exercising the real connection. A documented environment switch may support test/tooling contexts, but production guidance requires the probe.

Lazy startup was rejected because it moves a deterministic deployment error into user login. An in-memory fallback was rejected because it would create instance-local authentication and lock semantics that contradict the distributed-session and application-processing contracts.

### 2. Track Redis readiness separately from startup success

The probe publishes a small application-owned availability state and Spring readiness transitions. Startup success marks Redis available. Confirmed runtime connection failure marks readiness refusing traffic. The health endpoint performs or delegates to a bounded probe so an orchestrator sees HTTP 503 while Redis is unavailable and can observe recovery after a successful `PING`.

Readiness state is advisory for traffic management; each Session/lease operation still relies on its real Redis result. Cached healthy state never permits local fallback or suppresses an operation error. A scheduled high-frequency poll was rejected because it adds continuous Redis load; health probes and real operations provide sufficient transitions.

### 3. Translate Session connection failures outside `SessionRepositoryFilter`

Register a highest-precedence servlet `OncePerRequestFilter` before `SessionRepositoryFilter.DEFAULT_ORDER`. It wraps the inner chain and classifies only a bounded cause chain rooted in Spring Data/Lettuce connection-availability exceptions. Serialization failures, programming errors, and arbitrary runtime exceptions continue through their existing handlers.

When a classified Redis failure occurs and the response is not committed, the filter marks Redis unavailable, clears only the buffered response body, preserves already-applied CORS headers, sets HTTP 503 and `application/json`, writes the standard dependency-unavailable `BaseResponse`, and returns without rethrowing. It does not dispatch to `/error`, access/create a Session, or retry the request. This covers Session lookup and save-on-commit failures, including login.

When the response is already committed, including a long-running SSE response, the filter cannot change status or media type. It marks readiness unavailable, logs one content-free diagnostic, and lets the container terminate the response without attempting JSON serialization or error redispatch. Generation's own Redis lease-loss/SSE error handling remains responsible for its business terminal state.

Handling this in `@RestControllerAdvice` was rejected because Session save executes outside the DispatcherServlet exception boundary. Replacing the Session repository was rejected because it would duplicate Spring Session behavior and risk inconsistent save semantics.

### 4. Introduce a stable dependency-unavailable error

Add `DEPENDENCY_UNAVAILABLE` with a project API code in the 503xx range and a concise Chinese message such as `依赖服务暂不可用`. Map it to HTTP 503 in normal JSON exception resolution and use the same serialized body in the outer Session filter. Internal logs include dependency and operation category but no user credentials, cookies, Session data, Redis password, prompt, or generated content.

The public response does not name Redis, which avoids exposing infrastructure and allows the code to cover another required dependency later. Operational logs and readiness details may identify Redis.

### 5. Bind CORS origins as a validated list

Replace `@Value` plus `String.split` with typed `app.cors` properties. Bind the environment-backed comma-separated value to a list, trim entries, remove exact duplicates while preserving order, and validate every item as an absolute HTTP or HTTPS origin containing scheme, host, and optional valid port but no user info, non-root path, query, or fragment. Reject blank lists, `*`, origin patterns, and literal `null` because `allowCredentials(true)` is retained.

The development default is exactly:

```text
http://localhost:5173,http://localhost:5174
```

Production supplies the complete allowlist through `CORS_ALLOWED_ORIGINS`. The ignored local configuration should not shadow the tracked default with a narrower hard-coded value; it will use the same environment placeholder or omit the duplicate property. Explicit lists were preferred to `http://localhost:*` because generated content and unrelated local services must not automatically receive credentialed API access.

### 6. Keep CORS observable on dependency errors

MVC CORS processing normally installs allow headers before authenticated controller logic accesses the Session. The outer Redis filter uses `resetBuffer`, not `reset`, so an allowed cross-origin login can read the 503 response. Tests cover both allowed origins, rejected origins, and the Redis failure path. If a future pre-MVC component starts accessing Session, CORS must move to an ordered `CorsFilter` ahead of it rather than reflecting request origins in the Redis filter.

## Risks / Trade-offs

- [Fail-fast startup reduces partial availability] -> Redis is already required for authentication and distributed processing correctness; rely on orchestrator restart/backoff and provide a clear probe failure.
- [A transient startup outage prevents automatic in-process recovery] -> Treat startup as failed and let deployment supervision retry; runtime outages after successful startup recover through real operations and health probes.
- [Filter exception classification may be too broad or narrow] -> Walk a bounded cause chain, enumerate supported connection exception families, and test connection, serialization, and unrelated failures separately.
- [The response may already be committed] -> Never attempt status/media rewrite or `/error` dispatch; terminate cleanly and rely on protocol-specific business handling such as generation SSE errors.
- [Resetting an uncommitted body could remove useful output] -> Apply only to confirmed Redis availability failures and preserve headers with `resetBuffer`.
- [Adding 5174 increases development credentialed origins] -> Limit defaults to explicit loopback host/ports and retain complete production override plus strict URI validation.
- [Ignored local configuration can continue shadowing tracked values] -> Remove its duplicate CORS block or align its placeholder during local rollout and document how to inspect the effective property.

## Migration Plan

1. Provision and verify Redis before deploying because normal startup will become fail-fast; keep the existing host, port, credentials, TLS, database, and namespace variables.
2. Deploy the error code, probe, readiness state, and outer filter together so a runtime outage has a consistent 503 contract.
3. Align the ignored local CORS override and set `CORS_ALLOWED_ORIGINS` explicitly in shared development and production environments.
4. Verify startup failure with Redis stopped, successful startup after Redis returns, login/session persistence, runtime outage 503, recovery health, and no `/error` recursion.
5. Roll back by deploying the previous backend; Redis data and Session serialization remain compatible. Restore the previous readiness expectation only if the orchestrator was configured specifically for the new 503 behavior.

## Open Questions

None. Redis remains mandatory, startup failure is intentional, and CORS remains an explicit credentialed allowlist.
