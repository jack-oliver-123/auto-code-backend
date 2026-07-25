## ADDED Requirements

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
The system SHALL expose `POST /app/delete` for authenticated users. A successful deletion SHALL mark the owner's application as logically deleted and SHALL make it unavailable to normal detail and list queries.

#### Scenario: Owner deletes application
- **WHEN** the owner submits the valid id of an active application
- **THEN** the system logically deletes the application and returns success

#### Scenario: User attempts to delete another user's application
- **WHEN** an authenticated user submits the id of an application owned by another user
- **THEN** the system rejects the request with the no-authority response and retains the application

#### Scenario: Application to delete does not exist
- **WHEN** the submitted id does not identify an active application
- **THEN** the system returns the not-found response

### Requirement: Application details are publicly readable
The system SHALL expose `GET /app/get/vo` for retrieving an active application by positive id without requiring authentication. The detail response SHALL include the application's public business data, including its initialization prompt, and MUST NOT expose the internal logical-deletion field.

#### Scenario: Caller retrieves application details
- **WHEN** any caller supplies the positive id of an active application
- **THEN** the system returns the application detail view

#### Scenario: Caller requests a missing or deleted application
- **WHEN** the id does not identify an active application
- **THEN** the system returns the not-found response

#### Scenario: Caller supplies an invalid detail id
- **WHEN** the id is missing, malformed, or not positive
- **THEN** the system returns the parameter-error response

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
The system SHALL expose `POST /app/admin/delete` only to administrators. A successful request SHALL logically delete the identified active application regardless of its owner.

#### Scenario: Administrator deletes an application
- **WHEN** an administrator submits the valid id of an active application
- **THEN** the system logically deletes the application and returns success

#### Scenario: Non-administrator invokes administrator deletion
- **WHEN** a non-administrator invokes `POST /app/admin/delete`
- **THEN** the system rejects the request with the no-authority response

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
The system SHALL use logical deletion for applications, SHALL whitelist sortable database fields before constructing dynamic ordering, and SHALL return summary views for list endpoints and detail views for detail endpoints. List responses MUST omit the full initialization prompt, while no response SHALL expose the logical-deletion field.

#### Scenario: Client submits an untrusted sort field
- **WHEN** a list request contains a sort field outside the application sort whitelist
- **THEN** the system ignores that sort field and does not include it in generated SQL

#### Scenario: Logically deleted application is queried
- **WHEN** any ordinary or administrator detail or list endpoint evaluates a logically deleted application
- **THEN** the application is treated as nonexistent and is not returned

#### Scenario: List response is produced
- **WHEN** any application list endpoint returns records
- **THEN** each record uses the summary view without the full `initPrompt` or internal `isDelete` value
