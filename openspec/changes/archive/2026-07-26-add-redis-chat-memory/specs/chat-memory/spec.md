## ADDED Requirements

### Requirement: Model memory is bounded and isolated by application
The system SHALL maintain a distinct conversation-memory snapshot for each positive `Long` application id. A snapshot SHALL contain only typed `user` and `ai` messages belonging to that application, SHALL retain at most the latest 10 persisted messages by default, SHALL bound each included message to 6,000 characters by default, and SHALL bound the complete prior-context payload to 24,000 characters by default. These limits MUST be configurable, positive, and enforced before a snapshot is stored or sent to the model.

Truncation SHALL be deterministic, SHALL preserve useful leading and trailing context with an explicit truncation marker, and SHALL NOT modify the durable MySQL chat-history record. Redis keys MUST use a versioned application namespace and the full `Long` application id; integer narrowing and unprefixed keys are prohibited.

#### Scenario: Follow-up generation loads bounded prior context
- **WHEN** an owner starts a later generation for an application with more history than the configured limits
- **THEN** the model receives the newest prior messages that fit within every configured bound, while MySQL retains the complete original records

#### Scenario: Applications have independent memory
- **WHEN** two applications have conversation history for the same user
- **THEN** each application's model context and cache keys contain only that application's messages

#### Scenario: Application id exceeds integer range
- **WHEN** memory is addressed by a valid application id greater than `Integer.MAX_VALUE`
- **THEN** the complete `Long` value is used without overflow, truncation, or collision

#### Scenario: Invalid memory bounds are configured
- **WHEN** a configured message count, message-length limit, total-length limit, TTL, or local-cache capacity is not positive
- **THEN** application startup fails with a configuration error instead of silently using an unbounded cache

### Requirement: Conversation memory uses a recoverable two-level cache
The system SHALL resolve prior model context in this order: a valid Caffeine local snapshot, the application's Redis snapshot, then the latest eligible MySQL chat-history records. A Redis miss SHALL be repopulated from MySQL, and a local miss SHALL be repopulated from the resolved distributed snapshot. Cache values MUST be immutable or defensively copied so one generation cannot mutate another subscriber's context.

Local entries SHALL have a bounded capacity and short configurable expiration. Redis entries SHALL have a separate configurable expiration and SHALL refresh that expiration when a terminal snapshot is written. Active backend instances MUST receive application-scoped invalidation after a distributed snapshot changes; a missed notification MUST be bounded by the local-entry expiration.

#### Scenario: Local snapshot is current
- **WHEN** the local cache contains a current, unexpired snapshot for the application
- **THEN** the system uses it without deserializing the larger Redis payload or querying MySQL

#### Scenario: Local snapshot is absent
- **WHEN** the local cache has no usable snapshot and Redis contains one
- **THEN** the system loads the Redis snapshot, validates its application id and bounds, populates the local cache, and uses it as prior context

#### Scenario: Distributed snapshot is absent or expired
- **WHEN** neither cache contains usable memory for an existing application
- **THEN** the system loads the latest bounded records from MySQL, stores the recovered snapshot in Redis and Caffeine when possible, and uses the recovered context

#### Scenario: Redis is unavailable during context loading
- **WHEN** Redis memory access fails but authorized MySQL history remains available
- **THEN** the system evicts any unverified local entry, recovers context from MySQL, and does not fail generation solely because the derived memory cache is unavailable

#### Scenario: MySQL recovery also fails
- **WHEN** no valid cache is available and the required MySQL history query fails
- **THEN** generation fails before invoking the AI provider and emits no `done` event

### Requirement: Current input is not duplicated in recovered memory
The application service SHALL persist the backend-selected current user message before AI invocation and SHALL use that record's id as an exclusive upper boundary when recovering prior context. The recovered snapshot MUST NOT contain the current record, while the exact current message SHALL be supplied to the model once through the current request. Initial generation SHALL continue to use the stored `initPrompt` without injecting follow-up memory.

#### Scenario: Cache miss occurs after current user persistence
- **WHEN** the current user row has been inserted and model memory must be recovered from MySQL
- **THEN** the recovery query selects only records whose ids are lower than the current user record id

#### Scenario: Cached prior snapshot is used
- **WHEN** a valid snapshot from the previous terminal turn is available
- **THEN** the model receives that prior snapshot followed by exactly one copy of the current user message

#### Scenario: First generation starts
- **WHEN** an application has no completed code generation and uses its stored initialization prompt
- **THEN** the model receives the initialization prompt without follow-up memory injection

### Requirement: Memory refresh follows the application generation terminal state
The system SHALL keep model memory under application lifecycle control rather than allowing AI-provider callbacks to commit distributed memory directly. After a terminal user/AI history outcome has been persisted, the system SHALL refresh the bounded snapshot from durable history and publish local-cache invalidation. Successful code generation, successful plain conversation, safe AI failure recording, and cancellation recording SHALL each produce a refresh attempt after their corresponding terminal history record exists.

A memory refresh failure SHALL be logged without message content, SHALL invalidate the local entry, and SHALL NOT replace the original generation result. In particular, a cache write failure after all required generation persistence succeeds MUST NOT suppress the `done` event, while a provider response that later fails parsing, publication, preview preparation, or required database persistence MUST NOT be committed as a successful memory outcome.

#### Scenario: Code generation completes successfully
- **WHEN** the generated code, preview, application updates, and successful AI history record have all completed
- **THEN** the latest terminal history is reflected in the distributed snapshot and local entries are refreshed or invalidated before the next turn

#### Scenario: Plain conversation completes successfully
- **WHEN** a later AI response is accepted as normal conversation without changing generated code
- **THEN** the persisted user and AI messages become available to subsequent model context while the current preview remains unchanged

#### Scenario: Provider completes but publication fails
- **WHEN** the provider supplies a complete response and later parsing or publication fails
- **THEN** the cache is refreshed from the persisted safe failure outcome rather than committing the provider response as a successful turn

#### Scenario: Client cancels generation
- **WHEN** cancellation history is persisted for an interrupted subscription
- **THEN** the cache refreshes from that terminal history and no late provider callback can overwrite it

#### Scenario: Terminal cache refresh fails
- **WHEN** required database state succeeds but Redis cannot accept the refreshed memory snapshot
- **THEN** the system records a content-free diagnostic, evicts the local snapshot, preserves the original SSE outcome, and recovers from MySQL on a later request

### Requirement: Conversation memory is removed with its application
Owner and administrator application deletion SHALL execute while holding the same application-scoped processing lease used by generation. Before the database deletion commits, the system MUST delete the application's Redis memory snapshot and invalidate the local entry. A Redis deletion failure MUST fail the deletion before the application or durable history is removed; an already-cleared local or Redis entry is a successful no-op. If later database deletion fails, memory MAY remain absent because it is recoverable from the retained MySQL history.

No memory operation or diagnostic SHALL log message bodies, Redis credentials, session identifiers, provider payloads, or generated source content. The system SHALL expose or log content-free cache hit, miss, recovery, invalidation, and failure signals sufficient for operational diagnosis.

#### Scenario: Owner deletes an application with cached memory
- **WHEN** the owner deletes an active application whose memory exists in Redis and Caffeine
- **THEN** both memory tiers are cleared before the application and its durable history are logically deleted

#### Scenario: Administrator deletes another user's application
- **WHEN** an administrator deletes an active application
- **THEN** the same application-scoped memory purge is applied regardless of ownership

#### Scenario: Redis memory purge fails
- **WHEN** the distributed memory key cannot be deleted or confirmed absent
- **THEN** application deletion fails, prior durable state and deployment availability are preserved or restored, and no successful deletion response is returned

#### Scenario: Database deletion fails after memory purge
- **WHEN** Redis memory was removed but the application/history transaction rolls back
- **THEN** the application remains active and its memory can be reconstructed from the retained MySQL history

#### Scenario: Cache diagnostics are emitted
- **WHEN** a cache hit, miss, fallback, invalidation, or failure is observed
- **THEN** diagnostics identify the operation and application id without including conversation content or credentials
