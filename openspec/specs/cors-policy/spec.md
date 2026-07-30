# CORS Policy Specification

## Purpose

Define the validated credentialed cross-origin policy and preserve its response semantics during dependency failures.

## Requirements

### Requirement: Credentialed CORS uses a validated explicit origin allowlist
The system SHALL externalize the complete credentialed CORS origin allowlist through `app.cors.allowed-origins` and the `CORS_ALLOWED_ORIGINS` environment variable. Configuration SHALL trim entries, remove exact duplicates, and require at least one absolute HTTP or HTTPS origin containing a host and optional valid port but no user information, non-root path, query, or fragment. Because credentials are enabled, the list MUST reject blank entries, wildcard values or patterns, and the opaque `null` origin. Invalid configuration MUST fail startup.

For an allowed origin, preflight and actual responses SHALL echo that exact origin and include `Access-Control-Allow-Credentials: true`. The system MUST NOT reflect or authorize an origin absent from the configured list. The default development list SHALL include exactly `http://localhost:5173` and `http://localhost:5174`; deployments MAY replace the complete list without source changes.

#### Scenario: Vite uses its primary development port
- **WHEN** a preflight request supplies `Origin: http://localhost:5173`
- **THEN** the response allows that exact origin with credentials

#### Scenario: Vite falls back to port 5174
- **WHEN** a preflight or actual request supplies `Origin: http://localhost:5174` under the default development configuration
- **THEN** the response allows that exact origin with credentials instead of returning 403

#### Scenario: Environment supplies multiple spaced origins
- **WHEN** `CORS_ALLOWED_ORIGINS` contains comma-separated valid origins with surrounding whitespace and duplicates
- **THEN** the system trims and deduplicates them and allows every distinct configured origin

#### Scenario: Origin is not configured
- **WHEN** a preflight or actual request supplies an origin absent from the allowlist
- **THEN** the system rejects it and does not add an allow-origin header

#### Scenario: Sandboxed generated content supplies an opaque origin
- **WHEN** a request supplies `Origin: null`
- **THEN** the system rejects it and does not add credentialed CORS headers

#### Scenario: Credentialed wildcard is configured
- **WHEN** configuration contains `*`, an origin wildcard pattern, an empty entry, or only blank values
- **THEN** application startup fails instead of enabling broad credentialed access

#### Scenario: Configured value is not an origin
- **WHEN** an entry contains a non-HTTP scheme, missing host, user information, non-root path, query, fragment, or invalid port
- **THEN** application startup fails with a configuration error

### Requirement: Dependency-unavailable responses retain allowed CORS headers
An uncommitted HTTP 503 response produced for a Redis Session availability failure SHALL retain the CORS headers already established for an allowed origin. The dependency failure handler MUST clear only an incompatible response body and MUST NOT dynamically authorize the request origin itself.

#### Scenario: Cross-origin login encounters Redis outage
- **WHEN** an allowed development or deployment origin submits login and Session persistence fails before response commit
- **THEN** the caller receives a readable HTTP 503 JSON response with its configured allow-origin and credential headers

#### Scenario: Rejected origin encounters Redis outage
- **WHEN** an unconfigured origin submits a request that also encounters Redis unavailability
- **THEN** the dependency handler does not add an allow-origin header or otherwise bypass the CORS allowlist
