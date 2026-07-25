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
The system SHALL expose `POST /app/delete` for authenticated users. A successful deletion SHALL make any deployed public directory for the owner's application unavailable and SHALL mark the application as logically deleted so it is unavailable to normal detail and list queries. If moving the public directory out of service or logical deletion fails, the system MUST preserve or restore the prior active application and deployed-site availability.

#### Scenario: Owner deletes an undeployed application
- **WHEN** the owner submits the valid id of an active application with no public deployment
- **THEN** the system logically deletes the application and returns success

#### Scenario: Owner deletes a deployed application
- **WHEN** the owner submits the valid id of an active application with a public deployment
- **THEN** the system makes the deployment URL unavailable, logically deletes the application, and returns success

#### Scenario: Owner deletes an application whose deployment directory is already missing
- **WHEN** the owner submits an active application with deployment metadata but no final public directory
- **THEN** the system treats the site as already undeployed, logically deletes the application, and returns success

#### Scenario: Public undeployment fails
- **WHEN** the deployment directory cannot be moved out of public service before logical deletion
- **THEN** the system returns an operation-error response and retains both the active application and its prior public deployment

#### Scenario: Logical deletion fails after undeployment preparation
- **WHEN** the deployment directory was moved out of public service but the database logical deletion fails
- **THEN** the system restores the deployment directory, retains the active application, and returns an operation-error response

#### Scenario: User attempts to delete another user's application
- **WHEN** an authenticated user submits the id of an application owned by another user
- **THEN** the system rejects the request with the no-authority response and retains the application and any deployment

#### Scenario: Application to delete does not exist
- **WHEN** the submitted id does not identify an active application
- **THEN** the system returns the not-found response

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
The system SHALL expose `POST /app/chat/gen/code` with an `application/json` request body containing a positive `appId` and a conditionally required `message`. Only the authenticated owner MAY generate an application. The first generation SHALL use the stored `initPrompt` and ignore the submitted message; every later generation SHALL require a non-blank submitted message. The former GET route SHALL NOT be supported.

The response SHALL use `text/event-stream`. Each content event SHALL contain JSON data shaped as `{"d":"<chunk>"}` while preserving every generated chunk byte-for-byte at the application layer. The system SHALL emit exactly one named `done` event only after generation, parsing, file publication, and required database updates all succeed. An error or cancellation SHALL terminate the stream without a `done` event.

#### Scenario: Owner starts first generation
- **WHEN** the owner posts a positive `appId` for an application that has not completed a prior generation
- **THEN** the system generates with the stored `initPrompt`, preserves streamed chunk content, and emits `done` only after all completion work succeeds

#### Scenario: Owner continues generation
- **WHEN** the owner posts a positive `appId` and non-blank `message` for an application that has completed a prior generation
- **THEN** the system generates with the submitted message and emits `done` only after all completion work succeeds

#### Scenario: Later generation omits its message
- **WHEN** the owner posts no message or a blank message for an application that has completed a prior generation
- **THEN** the system rejects generation with the parameter-error response and emits no `done` event

#### Scenario: Caller uses the legacy GET route
- **WHEN** a caller invokes `GET /app/chat/gen/code`
- **THEN** the system rejects the unsupported method and does not start generation

#### Scenario: Generation fails or is cancelled
- **WHEN** generation, parsing, publication, a required database update, or the client subscription fails or is cancelled
- **THEN** the stream terminates without a `done` event

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
The system SHALL expose `POST /app/admin/delete` only to administrators. A successful request SHALL make any deployed public directory unavailable and SHALL logically delete the identified active application regardless of its owner. If moving the public directory out of service or logical deletion fails, the system MUST preserve or restore the prior active application and deployed-site availability.

#### Scenario: Administrator deletes an undeployed application
- **WHEN** an administrator submits the valid id of an active application with no public deployment
- **THEN** the system logically deletes the application and returns success

#### Scenario: Administrator deletes a deployed application
- **WHEN** an administrator submits the valid id of an active application with a public deployment
- **THEN** the system makes the deployment URL unavailable, logically deletes the application, and returns success

#### Scenario: Administrator deletes an application whose deployment directory is already missing
- **WHEN** an administrator submits an active application with deployment metadata but no final public directory
- **THEN** the system treats the site as already undeployed, logically deletes the application, and returns success

#### Scenario: Administrator deletion cannot undeploy the application
- **WHEN** the application's deployment directory cannot be moved out of public service before logical deletion
- **THEN** the system returns an operation-error response and retains both the active application and its prior public deployment

#### Scenario: Administrator logical deletion fails after undeployment preparation
- **WHEN** the deployment directory was moved out of public service but the database logical deletion fails
- **THEN** the system restores the deployment directory, retains the active application, and returns an operation-error response

#### Scenario: Non-administrator invokes administrator deletion
- **WHEN** a non-administrator invokes `POST /app/admin/delete`
- **THEN** the system rejects the request with the no-authority response and leaves the application and any deployment unchanged

#### Scenario: Administrator deletes a missing application
- **WHEN** an administrator submits an id that does not identify an active application
- **THEN** the system returns the not-found response

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
