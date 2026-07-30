## MODIFIED Requirements

### Requirement: Authenticated owners generate application code through POST SSE
The system SHALL expose `POST /app/chat/gen/code` with an `application/json` request body containing a positive `appId` and a conditionally required `message`. Only the authenticated owner MAY generate an application. The system SHALL acquire a distributed processing lease keyed by the complete `Long appId` before starting application processing, SHALL renew it while the subscription is active, and SHALL release it on success, error, or cancellation. Generation, project building, deployment, and deletion of the same application MUST be mutually exclusive across backend instances.

The first generation SHALL use the stored `initPrompt`, ignore the submitted message, select `vue_project`, and SHALL NOT inject follow-up conversation or project-source context. Every later generation SHALL require a non-blank submitted message and SHALL use the application's exact persisted generation type; existing `html` and `multi_file` applications MUST NOT be converted to Vue implicitly. After authentication, active-application, ownership, state, and effective-message validation, the system MUST persist the exact backend-selected user message before preview, builder, or provider preflight. Later generation SHALL recover bounded prior application memory using the current user-history id as an exclusive boundary and SHALL provide the current message to the model exactly once. A later Vue generation SHALL additionally include the complete bounded current stable model-owned source according to the chat-memory source-context contract.

The system SHALL accumulate the AI reply without trimming, normalization, or inserted separators while enforcing the configured response bound. Successful code output SHALL be parsed and published according to its persisted type. Vue code output additionally requires safe source materialization, isolated build completion, validated `dist`, and rollback-capable project publication. A later response containing ordinary conversation instead of generated code MAY complete successfully without changing the prior source, built output, preview publication, or code-generation type. Generation failure and cancellation SHALL persist their existing safe AI outcomes. After terminal history persistence, the system SHALL refresh derived conversation memory; memory-cache refresh failure MUST NOT replace the original generation result.

The response SHALL use `text/event-stream`. Each content event SHALL contain JSON data shaped as `{"d":"<chunk>"}` while preserving every generated chunk byte-for-byte at the application layer. The system SHALL emit exactly one named `done` event only after provider completion, parsing when required, Vue build and built-output validation when required, rollback-capable file publication when required, preview preparation, required database updates, and successful AI-history persistence all succeed. A memory-cache refresh is recoverable and is not a required `done` dependency. An error, cancellation, output-limit violation, or lost processing lease SHALL terminate the stream without `done`.

#### Scenario: Owner starts first generation
- **WHEN** the owner posts a positive `appId` for an application that has not completed a prior generation
- **THEN** the system stores and generates with the normalized `initPrompt` as `vue_project`, injects no follow-up conversation or source context, builds and validates the complete project, persists the complete AI reply and initial type on success, preserves streamed chunks, and emits `done` only after required completion work

#### Scenario: First-generation request contains a different message
- **WHEN** the owner submits a client message during first generation
- **THEN** the system ignores it for generation, history, memory, and project context and uses the stored `initPrompt`

#### Scenario: Existing application retains its generation type
- **WHEN** a later generation starts for an application whose persisted type is `html`, `multi_file`, or `vue_project`
- **THEN** the system uses that exact type's prompt, parser, publication, preview, and deployment behavior without implicit conversion

#### Scenario: Owner continues Vue generation with cached memory
- **WHEN** the owner posts a valid later message for a Vue application and current conversation memory and stable source are available
- **THEN** the system supplies bounded prior messages, the complete bounded model-owned source, and one exact copy of the submitted message, then persists the terminal outcome and refreshes memory

#### Scenario: Owner continues generation after conversation-cache expiry
- **WHEN** a valid later generation has no usable Caffeine or Redis conversation snapshot
- **THEN** prior messages are recovered from MySQL below the current user-history id and a Vue application independently loads source from its stable project directory before AI invocation

#### Scenario: Significant whitespace is present
- **WHEN** a valid later user message or AI reply contains leading, trailing, repeated spaces, or newlines
- **THEN** current input, persisted history, and emitted chunks preserve that content, while only prior cached conversation copies are deterministically truncated at configured bounds

#### Scenario: Vue follow-up is ordinary conversation
- **WHEN** the model returns a valid non-project answer without a project opening marker to a later informational question
- **THEN** the system preserves the current Vue source, `dist`, generated type, and preview, persists the exact AI answer, refreshes memory, and emits `done`

#### Scenario: Malformed Vue project resembles conversation
- **WHEN** a later response contains a Vue project opening marker but violates the versioned project contract
- **THEN** the system treats it as generation failure rather than ordinary conversation, preserves the prior project, and emits no `done`

#### Scenario: Later generation omits its message
- **WHEN** the owner posts no message or a blank message for an application that completed prior generation
- **THEN** the system returns the parameter-error response, writes no history, loads no source, invokes no AI provider or builder, and emits no `done`

#### Scenario: Request fails authorization or application validation
- **WHEN** the application is missing, the caller is not its owner, or generation state or persisted type is invalid
- **THEN** the system returns the applicable error, writes no history, reads no generated source, invokes no AI provider or builder, and releases any acquired lease

#### Scenario: Another instance is processing the application
- **WHEN** a generation, build, deployment, or deletion request arrives while another instance holds the application's processing lease
- **THEN** the request returns an operation-error response without writing history, invoking the provider or builder, or modifying memory or generated files

#### Scenario: Distributed lease cannot be acquired
- **WHEN** Redis coordination is unavailable before generation starts
- **THEN** generation fails without falling back to a JVM-only lock, writes no history, invokes no provider or builder, and emits no `done`

#### Scenario: Processing lease is lost during Vue generation
- **WHEN** lease renewal fails or ownership is lost while the provider stream or project build is active
- **THEN** the system aborts application finalization, terminates active builder work, records a safe terminal failure after a user row exists, rolls back unresolved publication, and emits no `done`

#### Scenario: User-history persistence fails
- **WHEN** the effective user message cannot be persisted
- **THEN** the system fails before conversation or project-source recovery, AI invocation, or build, releases the processing lease, and emits no content or `done`

#### Scenario: Vue response exceeds the backend limit
- **WHEN** accepting an exact provider chunk would exceed the configured Vue response bound
- **THEN** the system preserves the original error semantics, publishes no candidate project, records a safe AI failure when possible, releases the lease, and emits no `done`

#### Scenario: Generation fails after the user message is stored
- **WHEN** conversation recovery, Vue source-context loading, preview preflight, provider invocation, streaming, parsing, source materialization, isolated build, built-output validation, publication, preview preparation, required application update, or successful AI-history persistence fails
- **THEN** the system preserves the original error, records one safe AI failure when possible, terminates and cleans builder work, rolls back unresolved publication, refreshes memory from the terminal durable state when possible, releases the lease, and emits no `done`

#### Scenario: Generation is cancelled after the user message is stored
- **WHEN** the client cancels before successful finalization, including while a Vue build is active
- **THEN** the system records one safe AI cancellation, terminates and cleans builder work, rolls back unresolved publication, refreshes memory from durable terminal history when possible, releases the lease, and emits no `done`

#### Scenario: Failure-history persistence also fails
- **WHEN** generation fails and its safe terminal history cannot be persisted
- **THEN** the system preserves the original generation error, logs the secondary persistence failure without source or sensitive content, invalidates local memory, releases the lease, and emits no `done`

#### Scenario: Terminal memory refresh fails after successful persistence
- **WHEN** all required generation, Vue build, preview, application, and history updates succeed but Redis memory refresh fails
- **THEN** the system invalidates local memory, emits the normal successful `done` event, and allows a later request to recover conversation from MySQL and Vue source from the stable project

#### Scenario: Caller uses the legacy GET route
- **WHEN** a caller invokes `GET /app/chat/gen/code`
- **THEN** the system rejects the unsupported method, writes no history, acquires no processing lease, and invokes no provider or builder
