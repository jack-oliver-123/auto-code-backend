## 1. Dependency Error And Availability Model

- [x] 1.1 Add a stable dependency-unavailable `ErrorCode`, JSON response mapping, and HTTP 503 resolution without changing existing 4xx/5xx mappings.
- [x] 1.2 Implement a bounded Redis dependency probe on the shared `RedisConnectionFactory` that accepts only `PONG`, records content-free availability state, and never logs credentials or Session/application content.
- [x] 1.3 Add the default-enabled normal-runtime startup runner so Redis probe failure aborts startup before readiness, with an explicit test-context override that keeps ordinary unit tests infrastructure-independent.
- [x] 1.4 Separate liveness and Redis-dependent readiness behavior, return HTTP 503 for unavailable readiness without creating an HTTP Session, and restore readiness after a later successful bounded probe.
- [x] 1.5 Add probe, startup success/failure, readiness transition/recovery, health-without-Session, and secret-free diagnostic tests using mocks/fakes rather than live Redis.

## 2. Spring Session Runtime Failure Translation

- [x] 2.1 Implement a bounded cause-chain classifier for supported Redis connection-availability exceptions that excludes malformed serialization, application validation, and unrelated runtime failures.
- [x] 2.2 Register an outer `OncePerRequestFilter` ahead of `SessionRepositoryFilter.DEFAULT_ORDER`; on an uncommitted classified failure preserve headers, reset only the body, write one HTTP 503 JSON response, and return without `/error` redispatch, Session access, or retry.
- [x] 2.3 Handle classified failures after response commit by marking readiness unavailable and terminating/logging without status, media-type, JSON-body, or error-dispatch rewrites.
- [x] 2.4 Add filter-order and servlet tests for Session read failure, login save-on-commit failure, no cookie on failed login, single response/no recursive save, committed SSE behavior, malformed Session data, unrelated errors, and allowed-origin CORS header preservation.

## 3. Validated CORS Policy

- [x] 3.1 Add typed `app.cors` properties that trim and deduplicate a non-empty origin list and reject wildcards, patterns, `null`, non-HTTP schemes, missing hosts, user info, non-root paths, queries, fragments, and invalid ports.
- [x] 3.2 Refactor `CorsConfig` to consume the validated list while retaining credentials, allowed methods, and headers; set the tracked development default to exact localhost ports 5173 and 5174.
- [x] 3.3 Align or remove the ignored `application-local.yml` CORS override so it cannot shadow the tracked multi-origin default, without changing or exposing its database/model credentials.
- [x] 3.4 Expand configuration and MockMvc tests for both default Vite ports, spaced/deduplicated environment lists, complete override behavior, actual and preflight requests, attacker and `null` rejection, credential headers, and every invalid startup value.

## 4. Runtime Documentation And Verification

- [x] 4.1 Update Redis runtime documentation with fail-fast provisioning, startup and runtime outage behavior, readiness/recovery, no-fallback semantics, the 503 API contract, and test-profile expectations.
- [x] 4.2 Document `CORS_ALLOWED_ORIGINS` as the complete credentialed allowlist, list the two development defaults, and state that production must use exact trusted origins rather than wildcards.
- [x] 4.3 Run focused Redis configuration/filter/Session/health/CORS tests, then run `mvn clean test` without requiring live Redis.
- [x] 4.4 Run the opt-in Redis smoke profile against the configured local Redis when available and verify login Session persistence plus health recovery without stopping or deleting unrelated containers.
- [x] 4.5 Run `openspec validate harden-redis-availability-and-cors --type change --strict`, `git diff --check`, and `git status --short`; inspect the final diff so unrelated existing worktree changes remain untouched.
