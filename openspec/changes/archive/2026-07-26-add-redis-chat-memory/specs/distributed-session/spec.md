## ADDED Requirements

### Requirement: Authenticated HTTP sessions are shared through Redis
The system SHALL persist servlet sessions through Spring Boot's Redis Session integration so an authenticated request can be served by any backend instance. Session keys MUST use an application-specific namespace distinct from conversation-memory, generation-lock, and unrelated Redis keys. The session inactivity timeout and cookie maximum age SHALL both default to 30 days, SHALL use explicit duration values, and MUST be configurable without source changes.

The session cookie SHALL retain the configured `/api` path, SHALL be `HttpOnly`, and SHALL support environment-specific `Secure` and `SameSite` settings. Redis Session availability MUST NOT silently fall back to process-local sessions because that would produce inconsistent authentication across instances.

#### Scenario: Request moves between backend instances
- **WHEN** a user logs in through one instance and a later request with the same valid session cookie reaches another instance
- **THEN** the second instance resolves the same authenticated session from Redis

#### Scenario: Session reaches its inactivity timeout
- **WHEN** no request refreshes a session before the configured timeout expires
- **THEN** Redis Session expires it and later requests receive the not-logged-in response

#### Scenario: Session namespaces share one Redis deployment
- **WHEN** HTTP sessions, chat memory, and generation leases use the same Redis service
- **THEN** their configured key namespaces do not collide

#### Scenario: Redis Session is unavailable
- **WHEN** the session repository cannot read or persist authentication state
- **THEN** the request fails without creating an unshared local authenticated session

### Requirement: Session authentication state is minimal and safely serializable
The system SHALL store a dedicated serializable authenticated-session snapshot rather than the `User` persistence entity. The snapshot SHALL contain only the positive user id and a non-reversible credential fingerprint needed to detect credential changes. It MUST NOT contain the encoded password, profile fields, logical-deletion state, database timestamps, provider credentials, or other persistence-only data.

Session serialization SHALL use an explicitly configured serializer compatible with the snapshot across backend instances. Deserialization MUST reject data that cannot be interpreted as the expected snapshot, invalidate that session, and return the not-logged-in response rather than trusting arbitrary attribute types.

#### Scenario: Login creates distributed authentication state
- **WHEN** valid credentials are accepted
- **THEN** the system rotates or creates the session id and stores only the minimal authenticated-session snapshot

#### Scenario: Session data is inspected
- **WHEN** a serialized login-session attribute is examined in Redis
- **THEN** it contains neither the complete `User` entity nor the encoded password

#### Scenario: Unexpected session attribute is loaded
- **WHEN** the login-state attribute is missing, malformed, or has an unsupported type
- **THEN** the system invalidates the session and returns the not-logged-in response

### Requirement: Session validation reflects current user and credential state
For every protected request, the system SHALL validate the session snapshot, load the active user from MySQL by its stored id, derive the current credential fingerprint, and compare it using a timing-safe equality operation. A missing/deleted user or fingerprint mismatch MUST invalidate the session. Authorization and initial-password checks SHALL use the current database user rather than stale profile or role data from Redis.

Successful password change SHALL rotate the session id and replace the snapshot with one containing the new fingerprint. An administrator password reset SHALL cause existing sessions for that user to fail validation on their next protected request. Logout SHALL invalidate the session and remove its Redis-backed state.

#### Scenario: User profile or role changes
- **WHEN** an authenticated user's non-credential fields change in MySQL
- **THEN** the next protected request uses the current database values without requiring session replication of those fields

#### Scenario: User changes their password
- **WHEN** a password change succeeds
- **THEN** the current session id is rotated and its credential fingerprint is refreshed so the user remains authenticated with the new credential state

#### Scenario: Administrator resets a user's password
- **WHEN** an administrator replaces the user's credential while an older session exists
- **THEN** the older snapshot no longer matches and is invalidated on its next protected request

#### Scenario: User is logically deleted
- **WHEN** a session references a user that is no longer active
- **THEN** the session is invalidated and the request returns the not-logged-in response

#### Scenario: User logs out
- **WHEN** an authenticated user invokes logout
- **THEN** the Redis-backed session is invalidated and cannot authenticate a later request

### Requirement: Redis connection and session settings are deployment-safe
Redis host, port, database, username, password, connection timeout, command timeout, TLS, session namespace, and expiry settings SHALL be externalizable through environment variables or ignored local configuration. Tracked configuration MUST NOT contain a production Redis credential. Application-specific chat-memory TTL MUST NOT be placed under the standard `spring.data.redis` connection-property namespace.

Startup configuration SHALL use the Spring Boot 4 Redis Session starter and supported property names. Obsolete store-selection properties and unrelated Redis embedding-store auto-configuration MUST NOT be required for session startup.

#### Scenario: Deployment supplies Redis credentials
- **WHEN** Redis connection values are provided by environment or ignored local configuration
- **THEN** the shared connection infrastructure applies them to Session and application Redis operations without duplicating secrets in tracked files

#### Scenario: No Redis credential is required locally
- **WHEN** the configured local Redis server allows unauthenticated connections
- **THEN** the application connects without manufacturing a username or sending a blank password

#### Scenario: Production enables TLS
- **WHEN** Redis TLS is enabled through deployment configuration
- **THEN** Session, conversation memory, invalidation, and generation coordination use the secured connection settings

#### Scenario: Unsupported Session property is present
- **WHEN** configuration contains a property that Spring Boot 4 no longer supports
- **THEN** verification detects and removes it rather than treating it as an effective Session setting
