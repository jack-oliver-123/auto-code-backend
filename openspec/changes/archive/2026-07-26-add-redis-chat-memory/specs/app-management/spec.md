## MODIFIED Requirements

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

### Requirement: Authenticated owners generate application code through POST SSE
The system SHALL expose `POST /app/chat/gen/code` with an `application/json` request body containing a positive `appId` and a conditionally required `message`. Only the authenticated owner MAY generate an application. The system SHALL acquire a distributed processing lease keyed by the complete `Long appId` before starting application processing, SHALL renew it while the subscription is active, and SHALL release it on success, error, or cancellation. Generation and deletion of the same application MUST be mutually exclusive across backend instances.

The first generation SHALL use the stored `initPrompt`, ignore the submitted message, and SHALL NOT inject follow-up memory. Every later generation SHALL require a non-blank submitted message. After authentication, active-application, ownership, state, and effective-message validation, the system MUST persist the exact backend-selected user message before preview/provider preflight. Later generation SHALL recover bounded prior application memory using the current user-history id as an exclusive boundary and SHALL provide the current message to the model exactly once.

The system SHALL accumulate the AI reply without trimming, normalization, or inserted separators and MUST persist the terminal AI history outcome before successful completion. Successful code output SHALL be parsed and published as before. A later response containing normal conversation instead of generated code MAY complete successfully without changing the prior code or preview publication. Generation failure and cancellation SHALL persist their existing safe AI outcomes. After terminal history persistence, the system SHALL refresh derived conversation memory; memory-cache refresh failure MUST NOT replace the original generation result.

The response SHALL use `text/event-stream`. Each content event SHALL contain JSON data shaped as `{"d":"<chunk>"}` while preserving every generated chunk byte-for-byte at the application layer. The system SHALL emit exactly one named `done` event only after generation, parsing when required, file publication when required, preview preparation, required database updates, and successful AI-history persistence all succeed. A memory-cache refresh is recoverable and is not a required `done` dependency. An error, cancellation, or lost processing lease SHALL terminate the stream without `done`.

#### Scenario: Owner starts first generation
- **WHEN** the owner posts a positive `appId` for an application that has not completed a prior generation
- **THEN** the system stores and generates with the normalized `initPrompt`, injects no follow-up memory, persists the complete AI reply on success, preserves streamed chunks, and emits `done` only after required completion work

#### Scenario: First-generation request contains a different message
- **WHEN** the owner submits a client message during first generation
- **THEN** the system ignores it for generation, history, and memory and uses the stored `initPrompt`

#### Scenario: Owner continues generation with cached memory
- **WHEN** the owner posts a valid later message and current application memory is available
- **THEN** the system supplies the bounded prior messages followed by one exact copy of the submitted message, persists the terminal outcome, and refreshes memory

#### Scenario: Owner continues generation after cache expiry
- **WHEN** a valid later generation has no usable Caffeine or Redis snapshot
- **THEN** prior messages are recovered from MySQL below the current user-history id before AI invocation

#### Scenario: Significant whitespace is present
- **WHEN** a valid later user message or AI reply contains leading, trailing, repeated spaces, or newlines
- **THEN** current input, persisted history, and emitted chunks preserve that content, while only prior cached copies may be deterministically truncated at configured bounds

#### Scenario: Follow-up is normal conversation
- **WHEN** the model returns a valid non-code answer to a later informational question
- **THEN** the system preserves the current generated code and preview, persists the exact AI answer, refreshes memory, and emits `done`

#### Scenario: Later generation omits its message
- **WHEN** the owner posts no message or a blank message for an application that completed prior generation
- **THEN** the system returns the parameter-error response, writes no history, invokes no AI provider, and emits no `done`

#### Scenario: Request fails authorization or application validation
- **WHEN** the application is missing, the caller is not its owner, or generation state is invalid
- **THEN** the system returns the applicable error, writes no history, invokes no AI provider, and releases any acquired lease

#### Scenario: Another instance is processing the application
- **WHEN** a generation or deletion request arrives while another instance holds the application's processing lease
- **THEN** the request returns an operation-error response without writing history, invoking the provider, or modifying memory

#### Scenario: Distributed lease cannot be acquired
- **WHEN** Redis coordination is unavailable before generation starts
- **THEN** generation fails without falling back to a JVM-only lock, writes no history, invokes no provider, and emits no `done`

#### Scenario: Processing lease is lost during generation
- **WHEN** lease renewal fails or ownership is lost while the stream is active
- **THEN** the system aborts application finalization, records a safe terminal failure after a user row exists, rolls back unresolved publication, and emits no `done`

#### Scenario: User-history persistence fails
- **WHEN** the effective user message cannot be persisted
- **THEN** the system fails before memory recovery or AI invocation, releases the processing lease, and emits no content or `done`

#### Scenario: Generation fails after the user message is stored
- **WHEN** memory recovery, preview preflight, provider invocation, streaming, parsing, publication, preview preparation, required application update, or successful AI-history persistence fails
- **THEN** the system preserves the original error, records one safe AI failure when possible, rolls back unresolved publication, refreshes memory from the terminal durable state when possible, releases the lease, and emits no `done`

#### Scenario: Generation is cancelled after the user message is stored
- **WHEN** the client cancels before successful finalization
- **THEN** the system records one safe AI cancellation, rolls back unresolved publication, refreshes memory from durable terminal history when possible, releases the lease, and emits no `done`

#### Scenario: Failure-history persistence also fails
- **WHEN** generation fails and its safe terminal history cannot be persisted
- **THEN** the system preserves the original generation error, logs the secondary persistence failure without sensitive content, invalidates local memory, releases the lease, and emits no `done`

#### Scenario: Terminal memory refresh fails after successful persistence
- **WHEN** all required generation and history updates succeed but Redis memory refresh fails
- **THEN** the system invalidates local memory, emits the normal successful `done` event, and allows a later request to recover from MySQL

#### Scenario: Caller uses the legacy GET route
- **WHEN** a caller invokes `GET /app/chat/gen/code`
- **THEN** the system rejects the unsupported method, writes no history, acquires no processing lease, and invokes no provider

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
