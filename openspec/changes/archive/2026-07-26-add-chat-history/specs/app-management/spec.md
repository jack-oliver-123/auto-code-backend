## MODIFIED Requirements

### Requirement: Users can logically delete only their own applications
The system SHALL expose `POST /app/delete` for authenticated users. A successful deletion SHALL make any deployed public directory for the owner's application unavailable, SHALL mark the application as logically deleted so it is unavailable to normal detail and list queries, and SHALL logically delete all active chat history associated with the application. Application and history deletion MUST be one database transaction. If moving the public directory out of service or either database deletion fails, the system MUST preserve or restore the prior active application, its active chat history, and deployed-site availability.

#### Scenario: Owner deletes an undeployed application
- **WHEN** the owner submits the valid id of an active application with no public deployment
- **THEN** the system logically deletes the application and all of its active chat history and returns success

#### Scenario: Owner deletes a deployed application
- **WHEN** the owner submits the valid id of an active application with a public deployment
- **THEN** the system makes the deployment URL unavailable, logically deletes the application and all of its active chat history, and returns success

#### Scenario: Owner deletes an application whose deployment directory is already missing
- **WHEN** the owner submits an active application with deployment metadata but no final public directory
- **THEN** the system treats the site as already undeployed, logically deletes the application and its active chat history, and returns success

#### Scenario: Application has no chat history
- **WHEN** the owner deletes an active application that has no active chat-history rows
- **THEN** the absence of history is treated as valid and the application deletion succeeds

#### Scenario: Public undeployment fails
- **WHEN** the deployment directory cannot be moved out of public service before logical deletion
- **THEN** the system returns an operation-error response and retains the active application, its active chat history, and its prior public deployment

#### Scenario: Application logical deletion fails after undeployment preparation
- **WHEN** the deployment directory was moved out of public service but application logical deletion fails
- **THEN** the database transaction rolls back, the system restores the deployment directory, retains the active application and its active history, and returns an operation-error response

#### Scenario: History logical deletion fails after undeployment preparation
- **WHEN** the deployment directory was moved out of public service but associated history deletion fails
- **THEN** the database transaction rolls back, the system restores the deployment directory, retains the active application and its active history, and returns an operation-error response

#### Scenario: User attempts to delete another user's application
- **WHEN** an authenticated user submits the id of an application owned by another user
- **THEN** the system rejects the request with the no-authority response and retains the application, its chat history, and any deployment

#### Scenario: Application to delete does not exist
- **WHEN** the submitted id does not identify an active application
- **THEN** the system returns the not-found response and changes no chat history

### Requirement: Authenticated owners generate application code through POST SSE
The system SHALL expose `POST /app/chat/gen/code` with an `application/json` request body containing a positive `appId` and a conditionally required `message`. Only the authenticated owner MAY generate an application. The first generation SHALL use the stored `initPrompt` and ignore the submitted message; every later generation SHALL require a non-blank submitted message. The former GET route SHALL NOT be supported.

After authentication, active-application, ownership, generation-state, and effective-message validation succeed, the system MUST persist the exact backend-selected effective message as a `user` chat-history record before preview/provider preflight or AI invocation. The system SHALL accumulate the AI reply without trimming, normalizing, or adding separators and MUST persist the complete reply as an `ai` record before successful completion is reported. If generation fails after the user record is stored, the system SHALL store a safe `ai` failure message; if the subscription is cancelled, it SHALL store a safe `ai` cancellation message. Stored failures MUST NOT contain stack traces, credentials, provider payloads, or internal filesystem paths.

The response SHALL use `text/event-stream`. Each content event SHALL contain JSON data shaped as `{"d":"<chunk>"}` while preserving every generated chunk byte-for-byte at the application layer. The system SHALL emit exactly one named `done` event only after generation, parsing, file publication, preview preparation, required application updates, and successful AI-history persistence all succeed. An error or cancellation SHALL terminate the stream without a `done` event.

#### Scenario: Owner starts first generation
- **WHEN** the owner posts a positive `appId` for an application that has not completed a prior generation
- **THEN** the system stores the normalized `initPrompt` as the user message, generates with that stored prompt, persists the complete AI reply on success, preserves streamed chunk content, and emits `done` only after all completion work succeeds

#### Scenario: First-generation request contains a different message
- **WHEN** the owner submits a client message during first generation
- **THEN** the system ignores that client message for both generation and history and stores the application's `initPrompt` as the user message

#### Scenario: Owner continues generation
- **WHEN** the owner posts a positive `appId` and non-blank `message` for an application that has completed a prior generation
- **THEN** the system stores and generates with the submitted message, persists the complete AI reply on success, and emits `done` only after all completion work succeeds

#### Scenario: Significant whitespace is present
- **WHEN** a valid later user message or AI reply contains leading, trailing, repeated spaces, or newlines
- **THEN** the persisted message and emitted AI chunks preserve that content without trimming, normalization, or inserted separators

#### Scenario: Later generation omits its message
- **WHEN** the owner posts no message or a blank message for an application that has completed a prior generation
- **THEN** the system rejects generation with the parameter-error response, writes no history record for that rejected request, and emits no `done` event

#### Scenario: Request fails authorization or application validation
- **WHEN** the application is missing, the caller is not its owner, or the application generation state is invalid
- **THEN** the system returns the applicable error, writes no history for that rejected request, does not call the AI provider, and emits no `done` event

#### Scenario: User-history persistence fails
- **WHEN** the effective user message cannot be persisted
- **THEN** the system fails the request before AI invocation and emits no content or `done` event

#### Scenario: Generation fails after the user message is stored
- **WHEN** preview preflight, provider invocation, streaming, parsing, publication, preview preparation, a required application update, or successful AI-history persistence fails
- **THEN** the system preserves the original error semantics, records one safe AI failure outcome when history storage remains available, rolls back unresolved publication as applicable, and emits no `done` event

#### Scenario: Generation is cancelled after the user message is stored
- **WHEN** the client cancels the generation subscription before successful finalization
- **THEN** the system records one safe AI cancellation outcome, rolls back unresolved publication, releases application processing state, and emits no `done` event

#### Scenario: Failure-history persistence also fails
- **WHEN** generation fails and the attempt to persist its safe AI failure outcome also fails
- **THEN** the system preserves and propagates the original generation error, logs the secondary persistence failure, and emits no `done` event

#### Scenario: Caller uses the legacy GET route
- **WHEN** a caller invokes `GET /app/chat/gen/code`
- **THEN** the system rejects the unsupported method, writes no history, and does not start generation

### Requirement: Administrators can delete any active application
The system SHALL expose `POST /app/admin/delete` only to administrators. A successful request SHALL make any deployed public directory unavailable, SHALL logically delete the identified active application regardless of its owner, and SHALL logically delete all active chat history associated with that application. Application and history deletion MUST be one database transaction. If moving the public directory out of service or either database deletion fails, the system MUST preserve or restore the prior active application, its active chat history, and deployed-site availability.

#### Scenario: Administrator deletes an undeployed application
- **WHEN** an administrator submits the valid id of an active application with no public deployment
- **THEN** the system logically deletes the application and all of its active chat history and returns success

#### Scenario: Administrator deletes a deployed application
- **WHEN** an administrator submits the valid id of an active application with a public deployment
- **THEN** the system makes the deployment URL unavailable, logically deletes the application and all of its active chat history, and returns success

#### Scenario: Administrator deletes an application whose deployment directory is already missing
- **WHEN** an administrator submits an active application with deployment metadata but no final public directory
- **THEN** the system treats the site as already undeployed, logically deletes the application and its active chat history, and returns success

#### Scenario: Administrator deletes an application with no chat history
- **WHEN** an administrator deletes an active application that has no active chat-history rows
- **THEN** the absence of history is treated as valid and the application deletion succeeds

#### Scenario: Administrator deletion cannot undeploy the application
- **WHEN** the application's deployment directory cannot be moved out of public service before logical deletion
- **THEN** the system returns an operation-error response and retains the active application, its active chat history, and its prior public deployment

#### Scenario: Administrator application deletion fails after undeployment preparation
- **WHEN** the deployment directory was moved out of public service but application logical deletion fails
- **THEN** the database transaction rolls back, the system restores the deployment directory, retains the active application and its active history, and returns an operation-error response

#### Scenario: Administrator history deletion fails after undeployment preparation
- **WHEN** the deployment directory was moved out of public service but associated history deletion fails
- **THEN** the database transaction rolls back, the system restores the deployment directory, retains the active application and its active history, and returns an operation-error response

#### Scenario: Non-administrator invokes administrator deletion
- **WHEN** a non-administrator invokes `POST /app/admin/delete`
- **THEN** the system rejects the request with the no-authority response and leaves the application, its chat history, and any deployment unchanged

#### Scenario: Administrator deletes a missing application
- **WHEN** an administrator submits an id that does not identify an active application
- **THEN** the system returns the not-found response and changes no chat history
