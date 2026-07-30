## ADDED Requirements

### Requirement: Later Vue turns receive stable source outside conversation memory
For every later `vue_project` generation or application-specific ordinary-conversation request, the system SHALL load the current complete model-owned source from the application's stable generated directory while holding its distributed processing lease and after persisting the exact current user message. Source context MUST include only validated permitted `src/` and optional `public/` text files in deterministic relative-path order. It MUST exclude backend-owned scaffold, lockfiles, `dist`, transient build content, hidden entries, and unrelated files.

The source loader MUST enforce the same containment, full-`Long` application identity, regular-tree, path, file-count, per-file, aggregate-content, and required-entry rules used by Vue publication, plus a positive configurable source-context bound. It MUST NOT silently truncate or partially omit a project. The provider request SHALL contain bounded prior conversation, one complete bounded source snapshot, and exactly one exact current user message in an explicit deterministic structure. Initial Vue generation SHALL contain neither follow-up conversation nor project source.

Project source SHALL remain independent of the Redis/Caffeine conversation snapshot and MUST NOT be stored as an additional unbounded conversation-cache value. MySQL chat history remains the conversation audit source, while the atomically published stable project remains the editable-source truth. Source-context operations and diagnostics MUST NOT log file contents, generated code, conversation bodies, credentials, provider payloads, or build output.

#### Scenario: Later Vue edit loads current source
- **WHEN** an owner submits a valid later modification for a Vue application with a complete safe stable project
- **THEN** the model receives bounded prior conversation, every current permitted source file in deterministic order, and one exact copy of the submitted message

#### Scenario: Later Vue question loads current source
- **WHEN** an owner asks a valid later application-specific question that may result in ordinary conversation
- **THEN** the model receives the same bounded current source context so it can answer about the actual stable project without causing that source to be written to Redis memory

#### Scenario: Initial Vue generation starts
- **WHEN** the application has not completed its first generation and uses stored `initPrompt`
- **THEN** the model receives no prior conversation or stable project-source snapshot

#### Scenario: Conversation cache is absent
- **WHEN** Redis and Caffeine conversation memory are unavailable but MySQL history and the stable Vue project are valid
- **THEN** the system recovers bounded conversation from MySQL and independently loads current source from the stable directory

#### Scenario: Stable Vue source is missing or incomplete
- **WHEN** a later Vue turn cannot find every required stable model-owned source entry
- **THEN** the request fails before AI invocation, preserves the prior project and preview, records a safe terminal failure when possible, and emits no `done`

#### Scenario: Stable Vue source is unsafe
- **WHEN** source-context loading encounters traversal, a symbolic link, a hidden or temporary entry, an unsupported file, a case-insensitive duplicate, or a path outside the expected project root
- **THEN** the request fails without following, logging, caching, or sending that content to the model

#### Scenario: Stable Vue source exceeds its context bound
- **WHEN** the complete valid source cannot fit within the configured source-context limit
- **THEN** the system fails before AI invocation rather than silently sending a truncated project that could produce destructive regeneration

#### Scenario: Current user input follows project context
- **WHEN** conversation and Vue source context are both present
- **THEN** the backend-selected current message appears exactly once after both prior-context sections and remains excluded from history selected below its exclusive id boundary

#### Scenario: Vue source context is cached as conversation
- **WHEN** terminal chat memory is refreshed after a Vue turn
- **THEN** Redis and Caffeine retain only the existing bounded typed conversation snapshot and do not receive a separate full project-source payload
