## Why

Redis is a correctness dependency for distributed HTTP sessions, conversation memory coordination, and application processing leases, but the backend currently starts without verifying connectivity and later turns Session commit failures into generic 500/error-dispatch cascades. Development CORS configuration is also only partially externalized: comma-separated values work, but the effective default allows only port 5173 and unvalidated whitespace-sensitive input rejects the common 5174 frontend origin.

## What Changes

- Add an explicit Redis startup connectivity check with a clear content-free failure message and dependency readiness state; normal runtime profiles do not silently start as healthy when the required Redis service is unreachable.
- Add a stable dependency-unavailable API error mapped to HTTP 503 for Redis failures that occur after startup.
- Handle Spring Session read/write/commit connection failures outside the Session repository filter so an uncommitted response becomes one JSON 503 without `/error` redispatch or a second Session save attempt.
- End an already committed response without attempting an incompatible JSON rewrite, while retaining content-free diagnostics.
- Preserve Redis-backed Session and distributed lease semantics without falling back to process-local Session, memory, or locking.
- Replace raw comma splitting with validated, trimmed, deduplicated CORS origin configuration.
- Allow both `http://localhost:5173` and `http://localhost:5174` by default in development, retain a complete environment-provided allowlist, reject wildcard/null origins when credentials are enabled, and test every configured/rejected origin.
- Update runtime documentation and health behavior for provisioning, startup, outage, recovery, and CORS configuration.

## Capabilities

### New Capabilities

- `cors-policy`: Define validated environment-driven credentialed CORS allowlists and development-origin behavior.

### Modified Capabilities

- `distributed-session`: Add Redis startup/readiness verification and a clear HTTP 503 contract for runtime Session repository outages without local fallback or recursive error handling.

## Impact

- Affects Redis infrastructure configuration, application startup/readiness, servlet filter ordering, API error codes, health responses, Spring Session failure handling, CORS properties/configuration, tracked defaults, local runtime documentation, and Web MVC tests.
- Normal backend startup now requires reachable Redis; tests must explicitly replace or disable the live connectivity probe while continuing to construct Redis Session beans.
- No Redis, Session, chat-memory, or lease key formats change, and existing authenticated sessions remain compatible.
