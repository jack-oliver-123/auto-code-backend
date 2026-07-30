# App Management Specification

## Purpose

Define the application lifecycle, access-control, pagination, filtering, and response-safety requirements for ordinary users, public callers, and administrators.

## Requirements

### Requirement: Authenticated users can create applications
The system SHALL expose `POST /app/add` for authenticated users. The request MUST contain a non-blank `initPrompt`; the system SHALL bind the new application to the current user, derive the initial application name from the first 12 characters of the normalized prompt, initialize its priority to `0`, persist it, and return its identifier.

#### Scenario: Application is created successfully
- **WHEN** an authenticated user submits a non-blank `initPrompt`
- **THEN** the system stores an active application owned by that user and returns the new application id

#### Scenario: Initialization prompt is missing
- **WHEN** an authenticated user submits a null, empty, or whitespace-only `initPrompt`
- **THEN** the system rejects the request with the parameter-error response

#### Scenario: Anonymous user attempts creation
- **WHEN** an unauthenticated caller invokes `POST /app/add`
- **THEN** the system rejects the request with the not-logged-in response

### Requirement: Users can update only their own application name
The system SHALL expose `POST /app/update` for authenticated users. A valid request MUST identify an active application owned by the current user and MUST provide a non-blank application name no longer than 256 characters. The ordinary-user update contract SHALL allow only `appName` to change.

#### Scenario: Owner updates application name
- **WHEN** the owner submits a valid application id and application name
- **THEN** the system updates only the application name and edit timestamp

#### Scenario: User attempts to update another user's application
- **WHEN** an authenticated user submits the id of an application owned by another user
- **THEN** the system rejects the request with the no-authority response and changes no fields

#### Scenario: User submits an invalid application name
- **WHEN** the owner submits a blank name or a name longer than 256 characters
- **THEN** the system rejects the request with the parameter-error response

#### Scenario: User submits unsupported update fields
- **WHEN** an ordinary-user update payload also contains fields such as `cover`, `priority`, `userId`, or `initPrompt`
- **THEN** the system does not apply those unsupported fields

### Requirement: Users can logically delete only their own applications
The system SHALL expose `POST /app/delete` for authenticated users. Deletion SHALL acquire the same application-scoped distributed processing lease used by generation and hold it through undeployment preparation, conversation-memory purge, database deletion, and reconciliation. A successful deletion SHALL make any deployed public directory for the owner's application unavailable, SHALL remove the application's Redis and local conversation memory, SHALL mark the application as logically deleted, and SHALL logically delete all active chat history associated with the application.

Application and history deletion MUST be one database transaction. Distributed memory MUST be deleted or confirmed absent before that transaction commits. If lease acquisition, public undeployment, distributed-memory purge, application deletion, or history deletion fails, the system MUST preserve or restore the prior active application, its active history, and deployed-site availability. Memory removed before a later database rollback MAY remain absent because it is recoverable from the retained MySQL history.

#### Scenario: Owner deletes an undeployed application
- **WHEN** the owner submits the valid id of an active application with no public deployment
- **THEN** the system acquires its processing lease, clears its conversation memory, logically deletes the application and all active history, and returns success

#### Scenario: Owner deletes a deployed application
- **WHEN** the owner submits an active application with a public deployment
- **THEN** the system makes the deployment URL unavailable, clears conversation memory, logically deletes the application and history, and returns success

#### Scenario: Owner deletes an application whose deployment directory is already missing
- **WHEN** the owner submits an active application with deployment metadata but no final public directory
- **THEN** the system treats the site as already undeployed and completes memory, application, and history deletion

#### Scenario: Application has no chat history or cached memory
- **WHEN** the owner deletes an active application without active history rows or memory entries
- **THEN** every absent resource is treated as a valid no-op and deletion succeeds

#### Scenario: Application is being generated on another instance
- **WHEN** the owner attempts deletion while another backend instance holds the application's processing lease
- **THEN** deletion returns the operation-error response and changes no deployment, application, history, or memory state

#### Scenario: Distributed memory purge fails
- **WHEN** undeployment was prepared but Redis memory cannot be deleted or confirmed absent
- **THEN** deletion fails, undeployment is rolled back, and the active application and history remain available

#### Scenario: Public undeployment fails
- **WHEN** the deployment directory cannot be moved out of public service before logical deletion
- **THEN** the system returns the operation-error response and retains the active application, history, memory, and prior deployment

#### Scenario: Application logical deletion fails after memory purge
- **WHEN** deployment was prepared and memory removed but application logical deletion fails
- **THEN** the database transaction rolls back, deployment is restored, active application and history remain, and memory can be reconstructed from MySQL

#### Scenario: History logical deletion fails after memory purge
- **WHEN** deployment was prepared and memory removed but associated history deletion fails
- **THEN** the database transaction rolls back, deployment is restored, active application and history remain, and memory can be reconstructed from MySQL

#### Scenario: User attempts to delete another user's application
- **WHEN** an authenticated user submits the id of an application owned by another user
- **THEN** the system returns the no-authority response and retains the application, history, memory, and deployment

#### Scenario: Application to delete does not exist
- **WHEN** the submitted id does not identify an active application
- **THEN** the system returns the not-found response and changes no history or memory

### Requirement: Users can retrieve only their own application details
The system SHALL expose `GET /app/get/vo` only to authenticated users. The endpoint SHALL return an active application only when the current user is its owner; administrator status SHALL NOT bypass ownership on this ordinary-user endpoint. The owner detail response MAY include owner-only business fields such as `initPrompt`, `userId`, and `deployKey`, but MUST NOT expose the logical-deletion field.

#### Scenario: Owner retrieves application details
- **WHEN** an authenticated owner supplies the positive id of their active application
- **THEN** the system returns the owner detail view

#### Scenario: Authenticated caller requests another user's application
- **WHEN** an authenticated caller, including an administrator, supplies the id of an application owned by another user
- **THEN** the system rejects the request with the no-authority response

#### Scenario: Anonymous caller requests owner details
- **WHEN** an unauthenticated caller invokes `GET /app/get/vo`
- **THEN** the system rejects the request with the not-logged-in response

#### Scenario: Initial-password user requests owner details
- **WHEN** an authenticated caller who must change the initial password invokes `GET /app/get/vo`
- **THEN** the system rejects the request with the initial-password restriction response

#### Scenario: Owner requests a missing or deleted application
- **WHEN** the id does not identify an active application
- **THEN** the system returns the not-found response

#### Scenario: Owner supplies an invalid detail id
- **WHEN** the id is missing, malformed, or not positive
- **THEN** the system returns the parameter-error response

### Requirement: Featured application details are publicly readable and sanitized
The system SHALL expose anonymous `GET /app/good/get/vo` for retrieving an active featured application by positive id. Only applications whose priority equals `99` SHALL be eligible. The public response MUST omit `initPrompt`, `userId`, `deployKey`, and internal persistence fields. A missing, deleted, or non-featured application SHALL be reported as not found so callers cannot distinguish those states.

#### Scenario: Anonymous caller retrieves a featured application
- **WHEN** any caller supplies the positive id of an active application whose priority is `99`
- **THEN** the system returns the sanitized public application detail without requiring authentication

#### Scenario: Caller requests a non-featured application
- **WHEN** the positive id identifies an active application whose priority is not `99`
- **THEN** the system returns the not-found response

#### Scenario: Caller requests a missing or deleted public application
- **WHEN** the id does not identify an active featured application
- **THEN** the system returns the not-found response

#### Scenario: Caller supplies an invalid public detail id
- **WHEN** the id is missing, malformed, or not positive
- **THEN** the system returns the parameter-error response

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

### Requirement: Applications expose a durable latest-generation lifecycle
The system SHALL persist the latest generation lifecycle for every active application using `PENDING`, `GENERATING`, `SUCCEEDED`, or `FAILED`. Application creation MUST initialize `PENDING`. After acquiring the application processing lease and completing request, application, ownership, generation-type, and effective-message validation, the system SHALL create an opaque attempt identifier and transition the application to `GENERATING` before writing current-turn history or invoking the provider.

Every terminal transition MUST match the application, owner, opaque attempt identifier, and current `GENERATING` state. Success SHALL clear prior failure details and record the finish time only after every required generation, validation, publication, preview, application, and history operation succeeds. Failure or cancellation after an attempt starts SHALL record `FAILED`, a bounded application-owned safe failure code and message, and the finish time. Stored failure details MUST NOT include raw provider payloads, generated source, stack traces, credentials, or unrestricted exception messages.

The lifecycle describes the latest attempt independently of an older valid project. A failed first attempt SHALL remain retryable without a generation type or stable partial output. A failed later attempt SHALL preserve the prior complete project, preview, and generation type. Owner and administrator responses SHALL expose the latest status and safe failure details needed to diagnose or retry the application; public responses MUST NOT expose the attempt identifier or failure details.

#### Scenario: Application is created before generation
- **WHEN** an authenticated user creates a valid application
- **THEN** the application is persisted as `PENDING` with no attempt id, failure details, or generation timestamps

#### Scenario: A validated generation attempt starts
- **WHEN** the owner holds the application lease and all pre-attempt validation succeeds
- **THEN** one new attempt id is stored with `GENERATING` and a start time before history or provider work begins

#### Scenario: Complete generation succeeds
- **WHEN** the matching attempt completes all required provider, parsing, build, publication, preview, and persistence work
- **THEN** the application conditionally becomes `SUCCEEDED`, prior failure details are cleared, and a finish time is stored

#### Scenario: First generation fails
- **WHEN** the matching first attempt times out, is cancelled, or fails required generation work
- **THEN** the application conditionally becomes `FAILED`, stores only safe bounded failure details, retains a null generation type, and has no stable partial project

#### Scenario: Regeneration fails
- **WHEN** a matching later attempt fails after an earlier complete version exists
- **THEN** the latest status becomes `FAILED` while the earlier generation type, stable project, and preview remain usable

#### Scenario: A stale callback arrives after retry
- **WHEN** a callback attempts a terminal update using an attempt id that is no longer current
- **THEN** the conditional update changes no lifecycle, project, type, or successful-history state belonging to the newer attempt

#### Scenario: An abandoned attempt is reconciled
- **WHEN** a `GENERATING` attempt is older than the configured stale age and Redis confirms that its application lease is absent
- **THEN** the system conditionally marks that exact attempt `FAILED` without changing a newer or actively leased attempt

#### Scenario: Lease absence cannot be confirmed
- **WHEN** stale-attempt reconciliation cannot query Redis reliably
- **THEN** it leaves the database lifecycle unchanged rather than failing potentially active work

### Requirement: Generation SSE reports one explicit terminal outcome
The generation response SHALL continue to use `text/event-stream` and preserve every AI content chunk byte-for-byte in JSON data shaped as `{"d":"<chunk>"}`. The system MAY interleave SSE comments as heartbeats, but heartbeat text MUST NOT become a content event, parser input, persisted history, generated source, or response-limit input.

A successful attempt SHALL emit exactly one named `done` event only after its durable `SUCCEEDED` transition and all existing completion requirements. An asynchronous failure after the reactive response begins SHALL finalize the matching attempt when possible, emit exactly one named `error` event containing a stable numeric API code, safe message, and `FAILED` status, emit no `done`, and then complete the Flux without delegating that failure to JSON controller advice. EOF or cancellation without `done` remains unsuccessful even when no error event can reach the client.

Synchronous request decoding, request-level validation, and authentication failures raised before SSE begins SHALL retain the normal JSON error response and HTTP status.

#### Scenario: Upstream streaming request times out
- **WHEN** the provider stream reaches its configured deadline after SSE has started
- **THEN** the matching attempt is finalized as `FAILED`, one named `error` event is emitted when writable, no `done` is emitted, and no JSON body is written under `text/event-stream`

#### Scenario: Parsing or build fails after content events
- **WHEN** exact content chunks were emitted but required parsing, validation, build, or publication fails
- **THEN** the stream ends with one named `error`, the chunks remain unchanged, no `done` is emitted, and clients must treat those chunks as provisional

#### Scenario: Generation completes successfully with heartbeats
- **WHEN** an attempt emits content and heartbeat comments before all completion work succeeds
- **THEN** heartbeat comments do not alter accumulated or persisted AI content and exactly one `done` follows successful finalization

#### Scenario: Client cancels an active stream
- **WHEN** the client disconnects after the attempt entered `GENERATING`
- **THEN** cancellation finalizes the matching attempt as `FAILED` when persistence remains available, emits no `done`, and does not require an error event on the closed connection

#### Scenario: Request fails before SSE starts
- **WHEN** request JSON, positive application id, or authentication validation fails synchronously
- **THEN** the system returns the applicable JSON HTTP error and creates no generation attempt

#### Scenario: Unexpected reactive error reaches the controller boundary
- **WHEN** an unclassified service error escapes after the SSE response has begun
- **THEN** the controller logs content-free diagnostics and emits a generic named `error` when writable instead of invoking JSON exception serialization

### Requirement: Generation time limits are coherent and externalized
Provider streaming timeout, complete-attempt timeout, servlet asynchronous-response timeout, heartbeat interval, and stale-attempt age SHALL have explicit finite positive defaults and SHALL be configurable without source changes. Configuration MUST enforce that heartbeat interval is below the provider timeout, provider timeout is below the complete-attempt timeout, servlet asynchronous timeout exceeds the complete-attempt timeout, and stale-attempt age exceeds the maximum live-attempt duration. The complete-attempt limit MUST account for the configured Vue build timeout plus bounded finalization overhead.

Timeout diagnostics SHALL identify application id, attempt id, phase, configured limit, and duration without logging prompts, generated source, provider payloads, or credentials.

#### Scenario: A valid Vue stream exceeds the former default
- **WHEN** an active provider stream runs longer than 60 or 120 seconds but remains within the configured provider and complete-attempt limits
- **THEN** the backend keeps processing it and may complete normally rather than cancelling it at the former implicit deadline

#### Scenario: Complete attempt exceeds its deadline
- **WHEN** provider, parsing, build, and finalization work exceed the configured complete-attempt limit
- **THEN** the system cancels active work, rolls back unresolved publication, finalizes the matching attempt as `FAILED`, and emits no `done`

#### Scenario: Generation timeout configuration is inconsistent
- **WHEN** a duration is non-positive or the required timeout ordering is violated
- **THEN** application startup fails with a configuration error instead of applying ambiguous or unbounded behavior

### Requirement: Users can page through their own applications
The system SHALL expose `POST /app/my/list/page/vo` for authenticated users. It SHALL ignore client attempts to select another owner, SHALL restrict records to the current user, SHALL support an optional application-name contains filter, and SHALL require `pageNum >= 1` and `1 <= pageSize <= 20`.

#### Scenario: User queries own applications
- **WHEN** an authenticated user submits valid pagination and an optional application name
- **THEN** the system returns only active applications owned by that user whose names match the optional filter

#### Scenario: User requests more than 20 records
- **WHEN** the requested page size is greater than 20
- **THEN** the system rejects the request with the parameter-error response

#### Scenario: User supplies non-positive pagination
- **WHEN** `pageNum` or `pageSize` is not positive
- **THEN** the system rejects the request with the parameter-error response

### Requirement: Callers can page through featured applications
The system SHALL expose `POST /app/good/list/page/vo` without requiring authentication. A featured application MUST have priority equal to `99`. The endpoint SHALL support an optional application-name contains filter and SHALL require `pageNum >= 1` and `1 <= pageSize <= 20`.

#### Scenario: Caller queries featured applications
- **WHEN** any caller submits valid pagination and an optional application name
- **THEN** the system returns only active applications with priority `99` whose names match the optional filter

#### Scenario: Caller requests an invalid featured page
- **WHEN** `pageNum` is not positive or `pageSize` is outside the range 1 through 20
- **THEN** the system rejects the request with the parameter-error response

### Requirement: Administrators can delete any active application
The system SHALL expose `POST /app/admin/delete` only to administrators. Deletion SHALL use the same distributed application-processing lease and deletion sequence as owner deletion. A successful request SHALL make any deployed public directory unavailable, SHALL clear local and Redis conversation memory, SHALL logically delete the identified active application regardless of owner, and SHALL logically delete all active chat history in one database transaction.

If lease acquisition, public undeployment, distributed-memory purge, application deletion, or history deletion fails, the system MUST preserve or restore the prior active application, active history, and deployed-site availability. Memory removed before a later database rollback MAY be reconstructed from retained MySQL history.

#### Scenario: Administrator deletes an undeployed application
- **WHEN** an administrator submits the valid id of an active application with no public deployment
- **THEN** the system acquires the application lease, clears memory, logically deletes application and history, and returns success

#### Scenario: Administrator deletes a deployed application
- **WHEN** an administrator submits an active application with a public deployment
- **THEN** the system makes the deployment unavailable, clears memory, logically deletes application and history, and returns success

#### Scenario: Administrator deletes an application whose deployment directory is already missing
- **WHEN** deployment metadata exists but the final public directory is absent
- **THEN** the system treats the site as already undeployed and completes memory and database deletion

#### Scenario: Administrator deletes an application with no history or memory
- **WHEN** the application has no active history rows or cached memory
- **THEN** those absent resources are valid no-ops and deletion succeeds

#### Scenario: Administrator deletion conflicts with generation
- **WHEN** another backend instance holds the application's processing lease
- **THEN** administrator deletion returns an operation-error response and changes no deployment, application, history, or memory state

#### Scenario: Administrator deletion cannot purge memory
- **WHEN** Redis memory cannot be deleted or confirmed absent
- **THEN** deletion fails, prepared undeployment is restored, and application and history remain active

#### Scenario: Administrator deletion cannot undeploy the application
- **WHEN** the application's deployment directory cannot be moved out of public service
- **THEN** deletion fails and retains the application, history, memory, and deployment

#### Scenario: Administrator application deletion fails after memory purge
- **WHEN** deployment was prepared and memory removed but application logical deletion fails
- **THEN** the transaction rolls back, deployment is restored, active data remains, and memory is recoverable from MySQL

#### Scenario: Administrator history deletion fails after memory purge
- **WHEN** deployment was prepared and memory removed but associated history deletion fails
- **THEN** the transaction rolls back, deployment is restored, active data remains, and memory is recoverable from MySQL

#### Scenario: Non-administrator invokes administrator deletion
- **WHEN** a non-administrator invokes `POST /app/admin/delete`
- **THEN** the system returns the no-authority response and leaves application, history, memory, and deployment unchanged

#### Scenario: Administrator deletes a missing application
- **WHEN** the submitted id does not identify an active application
- **THEN** the system returns the not-found response and changes no history or memory

### Requirement: Administrators can update approved application fields
The system SHALL expose `POST /app/admin/update` only to administrators. The update contract SHALL allow partial updates to `appName`, `cover`, and `priority` only; at least one field MUST be provided. A provided name MUST be non-blank and no longer than 256 characters, a provided cover MUST be no longer than 512 characters, and a provided priority MUST be non-negative.

#### Scenario: Administrator updates approved fields
- **WHEN** an administrator submits a valid active application id and at least one valid approved field
- **THEN** the system updates only the provided approved fields and the edit timestamp

#### Scenario: Administrator submits no update
- **WHEN** an administrator supplies an id without any approved field value
- **THEN** the system rejects the request with the parameter-error response

#### Scenario: Administrator submits invalid field values
- **WHEN** an approved field violates its blank, length, or non-negative constraint
- **THEN** the system rejects the request with the parameter-error response

#### Scenario: Non-administrator invokes administrator update
- **WHEN** a non-administrator invokes `POST /app/admin/update`
- **THEN** the system rejects the request with the no-authority response

### Requirement: Administrators can query applications without an application page-size cap
The system SHALL expose `POST /app/admin/list/page/vo` only to administrators. The endpoint SHALL require positive pagination but SHALL NOT impose the global 100-record application page limit. It SHALL support filters for every non-time, non-internal application field: `id`, `appName`, `cover`, `initPrompt`, `codeGenType`, `deployKey`, `priority`, and `userId`.

#### Scenario: Administrator filters applications
- **WHEN** an administrator submits valid pagination and any supported filter combination
- **THEN** the system returns matching active applications using contains matching for descriptive text and exact matching for identifiers, enums, priority, and owner

#### Scenario: Administrator requests more than 100 records
- **WHEN** an administrator submits a valid page size greater than 100
- **THEN** the system retains the requested page size instead of silently reducing it to 100

#### Scenario: Administrator submits invalid pagination
- **WHEN** `pageNum` or `pageSize` is not positive
- **THEN** the system rejects the request with the parameter-error response

#### Scenario: Non-administrator invokes administrator listing
- **WHEN** a non-administrator invokes `POST /app/admin/list/page/vo`
- **THEN** the system rejects the request with the no-authority response

### Requirement: Administrators can retrieve any active application detail
The system SHALL expose `GET /app/admin/get/vo` only to administrators and SHALL return the detail view for any active application identified by a positive id.

#### Scenario: Administrator retrieves application detail
- **WHEN** an administrator supplies the positive id of an active application
- **THEN** the system returns the application detail view

#### Scenario: Non-administrator invokes administrator detail
- **WHEN** a non-administrator invokes `GET /app/admin/get/vo`
- **THEN** the system rejects the request with the no-authority response

#### Scenario: Administrator requests a missing application
- **WHEN** the supplied id does not identify an active application
- **THEN** the system returns the not-found response

### Requirement: Application queries and views are safe and consistent
The system SHALL use logical deletion for applications, SHALL whitelist sortable database fields before constructing dynamic ordering, and SHALL return summary views for list endpoints, owner or administrator detail views for protected detail endpoints, and a distinct sanitized view for public featured details. List responses MUST omit the full initialization prompt, while no response SHALL expose the logical-deletion field.

The `deployUrl` field SHALL be derived with the configured deployment URL builder and SHALL be non-null only when the application has both a valid deployment key and a non-null deployment completion timestamp. The system SHALL return a null `deployUrl` for incomplete or malformed deployment metadata. Public responses MUST expose the derived URL rather than the deployment key itself.

#### Scenario: Client submits an untrusted sort field
- **WHEN** a list request contains a sort field outside the application sort whitelist
- **THEN** the system ignores that sort field and does not include it in generated SQL

#### Scenario: Logically deleted application is queried
- **WHEN** any ordinary or administrator detail or list endpoint evaluates a logically deleted application
- **THEN** the application is treated as nonexistent and is not returned

#### Scenario: List response is produced
- **WHEN** any application list endpoint returns records
- **THEN** each record uses the summary view without the full `initPrompt` or internal `isDelete` value

#### Scenario: Completed deployment is represented in a view
- **WHEN** an application has a valid deployment key and a non-null deployment completion timestamp
- **THEN** its applicable summary, protected detail, or public featured view contains the configured `deployUrl`

#### Scenario: Deployment metadata is incomplete or malformed
- **WHEN** an application has no completion timestamp or its deployment key is absent or invalid
- **THEN** its applicable view contains a null `deployUrl`

#### Scenario: Public featured detail response is produced
- **WHEN** `GET /app/good/get/vo` returns an application
- **THEN** the response uses the public featured detail view without `initPrompt`, `userId`, or `deployKey`

### Requirement: OpenAPI describes session authentication and streaming behavior
The system SHALL publish an OpenAPI cookie security scheme whose cookie name matches the configured server session cookie. Every protected application endpoint SHALL reference that scheme. The code-generation operation SHALL document its JSON request body, conditional `message` requirement, `text/event-stream` string response with an SSE example, `done` success ordering, absence of `done` on failure or cancellation, and major `400`, `401`, `403`, `404`, and `500` responses.

#### Scenario: Client inspects a protected endpoint
- **WHEN** an OpenAPI client reads a protected application operation
- **THEN** the operation references the configured session-cookie security scheme

#### Scenario: Client inspects the generation operation
- **WHEN** an OpenAPI client reads `POST /app/chat/gen/code`
- **THEN** it can determine the JSON request shape, conditional message semantics, SSE string framing, success completion event, failure behavior, and major error responses
