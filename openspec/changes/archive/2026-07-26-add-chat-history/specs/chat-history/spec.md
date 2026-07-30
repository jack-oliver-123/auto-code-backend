## ADDED Requirements

### Requirement: Chat history records are typed, application-scoped, and safe
The system SHALL store chat-history records with a positive record id, non-empty message, message type, positive application id, positive initiating-user id, timestamps, and logical-deletion state. The message type MUST be one of the values defined by `ChatHistoryMessageTypeEnum`: `user` or `ai`. All normal queries MUST exclude logically deleted records, and response views MUST omit persistence-only fields including `isDelete` and `updateTime`.

#### Scenario: User message is represented
- **WHEN** a valid user history record is persisted
- **THEN** the record is scoped to one application and initiating user with `messageType` equal to `user`

#### Scenario: AI message is represented
- **WHEN** a successful, failed, or cancelled AI outcome is persisted
- **THEN** the record is scoped to the same application and initiating user with `messageType` equal to `ai`

#### Scenario: Unsupported message type is submitted internally
- **WHEN** a history write or administrator filter supplies a message type outside `user` and `ai`
- **THEN** the system rejects it with the parameter-error response and does not construct an unvalidated database predicate

#### Scenario: History view is returned
- **WHEN** either protected history endpoint returns a record
- **THEN** the view contains only `id`, `message`, `messageType`, `appId`, `userId`, and `createTime`

#### Scenario: Logically deleted history is queried
- **WHEN** a normal history query evaluates a logically deleted record
- **THEN** the record is not returned

### Requirement: Owners and administrators can load application history with a stable cursor
The system SHALL expose `POST /chatHistory/list/page/vo` to authenticated users. The request MUST contain a positive `appId`, MAY contain a positive exclusive `beforeId`, and MAY specify `pageSize`; `pageSize` SHALL default to 10 and MUST be between 1 and 20. The service SHALL authorize an active application before querying: its owner or an administrator MAY read it, while other callers MUST NOT.

The system SHALL select active records for the requested application in descending id order, applying `id < beforeId` when a cursor is present and fetching at most `pageSize + 1` records without a count query. The response SHALL contain at most `pageSize` records in chronological display order, a `hasMore` flag, and a `nextCursor` equal to the oldest returned id only when more records exist.

#### Scenario: Owner loads the initial history page
- **WHEN** the application owner requests history without `beforeId` and omits `pageSize`
- **THEN** the system returns the latest 10 active messages in oldest-to-newest display order with correct `hasMore` and `nextCursor` values

#### Scenario: Owner loads older history
- **WHEN** the owner submits the prior response's `nextCursor`
- **THEN** the system returns only older active messages whose ids are less than that cursor and does not repeat the cursor record

#### Scenario: Messages share the same creation time
- **WHEN** multiple history records have equal `createTime` values across a cursor boundary
- **THEN** id-based pagination returns each record exactly once without skipping a user or AI message

#### Scenario: New messages arrive between page requests
- **WHEN** newer records are inserted after the initial page was returned and the caller requests an older page using `beforeId`
- **THEN** the older-page result remains anchored before that id and is not shifted by the new records

#### Scenario: Authorized application has no history
- **WHEN** an owner or administrator requests an active application with no active history
- **THEN** the system returns an empty records list, `hasMore` equal to false, and a null `nextCursor`

#### Scenario: Administrator loads another user's application history
- **WHEN** an administrator requests history for an active application owned by another user
- **THEN** the system returns that application's authorized cursor page

#### Scenario: User requests another user's history
- **WHEN** an authenticated non-administrator requests history for an application they do not own
- **THEN** the system returns the no-authority response rather than an empty history page

#### Scenario: Caller requests missing or deleted application history
- **WHEN** `appId` does not identify an active application
- **THEN** the system returns the not-found response rather than an empty history page

#### Scenario: Caller is anonymous or must change the initial password
- **WHEN** an unauthenticated caller or an initial-password user invokes the history endpoint
- **THEN** the existing authentication or initial-password restriction rejects the request before history is returned

#### Scenario: Cursor request is invalid
- **WHEN** `appId` is absent or non-positive, `beforeId` is present but non-positive, or `pageSize` is outside 1 through 20
- **THEN** the system returns the parameter-error response

### Requirement: Administrators can monitor all active chat history
The system SHALL expose `POST /chatHistory/admin/list/page/vo` only to administrators. The request MUST use positive `pageNum` and a `pageSize` between 1 and 100, and MAY filter by positive `appId`, positive `userId`, or a validated `messageType`. The system SHALL return matching active history records ordered by `createTime DESC, id DESC` and SHALL NOT accept client-controlled database sort fields.

#### Scenario: Administrator lists all history
- **WHEN** an administrator submits valid pagination without filters
- **THEN** the system returns a page of active history across applications in deterministic newest-first order

#### Scenario: Administrator filters moderation history
- **WHEN** an administrator supplies any valid combination of `appId`, `userId`, and `messageType`
- **THEN** the system returns only active records matching every supplied filter in newest-first order

#### Scenario: Records have equal creation times
- **WHEN** two administrator-visible records have the same `createTime`
- **THEN** the record with the greater id is ordered first

#### Scenario: Non-administrator invokes moderation history
- **WHEN** a non-administrator invokes `POST /chatHistory/admin/list/page/vo`
- **THEN** the system returns the no-authority response and no history records

#### Scenario: Administrator pagination or filters are invalid
- **WHEN** an administrator submits non-positive pagination, a page size greater than 100, a non-positive identifier filter, or an unsupported message type
- **THEN** the system returns the parameter-error response
