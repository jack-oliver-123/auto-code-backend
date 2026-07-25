## 1. Persistence Model

- [x] 1.1 Update `sql/init.sql` so `chat_history` is created idempotently with `MEDIUMTEXT`, exact camel-case columns, logical deletion, and `(appId, isDelete, id)` plus `(isDelete, createTime, id)` indexes; document the equivalent alteration needed if the draft table already exists.
- [x] 1.2 Add the `ChatHistory` MyBatis-Plus domain model and `ChatHistoryMessageTypeEnum` with validated `user` and `ai` value lookup.
- [x] 1.3 Add `ChatHistoryMapper` and its XML result map/column list using exact database column names while relying on MyBatis-Plus logical-delete behavior.
- [x] 1.4 Add cursor-query and administrator-query DTOs plus `ChatHistoryVO` and the cursor-page response VO without exposing persistence-only fields.

## 2. Chat History Service

- [x] 2.1 Add `ChatHistoryService` and `ChatHistoryServiceImpl` recording operations that validate positive identifiers, non-empty messages, and enum values, return inserted IDs where lifecycle coordination needs them, and fail with repository-standard exceptions on mapper errors.
- [x] 2.2 Implement safe AI failure/cancellation message construction that preserves controlled user-facing context but never stores stack traces, credentials, provider payloads, or filesystem paths.
- [x] 2.3 Implement owner-or-administrator application-history authorization using active application lookup, including distinct not-found and no-authority results.
- [x] 2.4 Implement count-free `beforeId` cursor loading with `pageSize + 1`, default size 10, maximum size 20, chronological response records, and correct `hasMore`/`nextCursor` values.
- [x] 2.5 Implement administrator pagination with validated `appId`, `userId`, and `messageType` filters, a page-size cap of 100, and fixed `createTime DESC, id DESC` ordering.
- [x] 2.6 Implement application-scoped history logical deletion where zero matching history rows is a successful no-op and mapper failures propagate.

## 3. Protected History APIs

- [x] 3.1 Add `ChatHistoryController` with authenticated `POST /chatHistory/list/page/vo` and administrator-only `POST /chatHistory/admin/list/page/vo`, keeping transport validation and login-user resolution in the controller and ownership rules in the service.
- [x] 3.2 Add session-cookie OpenAPI security requirements and document request validation, cursor response semantics, authorization errors, and administrator filters without exposing internal fields.

## 4. Generation Lifecycle Integration

- [x] 4.1 Inject chat-history recording into `AppServiceImpl` and persist the backend-selected effective user message after request/ownership/state validation but before preview/provider preflight; first generation must record `initPrompt`, while later messages retain significant whitespace.
- [x] 4.2 Accumulate AI chunks without mutation and persist the complete AI reply as a required successful-finalization update before emitting the existing `done` event.
- [x] 4.3 Add per-subscription terminal state so generation errors record one safe AI failure, cancellation records one safe AI cancellation, success does not create a terminal error, and history failures never mask the original generation error or emit `done`.
- [x] 4.4 Preserve existing per-app processing-lock release and reversible `CodeGenerationSession` cleanup across success, error, cancellation, and history-persistence failure paths.

## 5. Application Deletion Integration

- [x] 5.1 Add a transaction-scoped deletion callback that logically deletes the application and its history together, treats absent history as valid, and completes the database commit before permanent undeployment cleanup.
- [x] 5.2 Route both owner and administrator deletion through the transactional callback so application/history mapper failures restore the prior database state and flow through the existing deployment rollback behavior.

## 6. Focused Tests

- [x] 6.1 Add enum, mapper-contract, and history-recording service tests covering valid rows, invalid inputs/types, safe failure text, logical deletion, and mapper failure.
- [x] 6.2 Add cursor service tests covering default/latest 10, older pages, equal timestamps, inserts between requests, chronological output, empty history, cursor boundaries, page-size limits, owner/admin access, foreign ownership, and missing/deleted applications.
- [x] 6.3 Add administrator service and controller tests covering filters, deterministic descending order, pagination limits, authentication, administrator enforcement, initial-password restriction, and response-field safety.
- [x] 6.4 Extend generation service tests to verify first-generation `initPrompt` selection, later-message whitespace, exact AI chunk accumulation, user-before-provider ordering, AI-before-`done` ordering, and no paid or nondeterministic provider calls.
- [x] 6.5 Extend reactive failure tests for preview/provider/stream/parser/publication/application-update/history-write failures and cancellation, verifying safe history outcomes, original-error preservation, no duplicate terminal records, no `done`, rollback, and lock release.
- [x] 6.6 Extend owner/admin deletion tests for histories present or absent, unauthorized/missing applications, application mapper failure, history mapper failure, database rollback, and deployed-directory restoration.
- [x] 6.7 Add history endpoint and OpenAPI contract tests for request shape, cursor response shape, security scheme references, and major `400`, `401`, `403`, and `404` responses.

## 7. Verification

- [x] 7.1 Run the narrow ChatHistory, AppService, and controller test classes and resolve all failures.
- [x] 7.2 Run `mvn clean test` to verify the complete backend without live AI calls.
- [x] 7.3 Run `openspec validate add-chat-history --type change --strict`, `git diff --check`, and `git status --short`; inspect the final diff so unrelated user changes remain untouched.
