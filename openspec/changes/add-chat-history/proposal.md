## Why

Application conversations currently exist only in the live generation stream, so users cannot reliably restore prior messages and the frontend cannot distinguish a new application from one with an existing conversation. Persisted, application-scoped history is also needed for owner-only continuity, administrator moderation, and complete failure records.

## What Changes

- Persist the backend-selected user message before generation and persist the complete AI reply after successful generation.
- Record a safe AI failure or cancellation message when a generation attempt terminates unsuccessfully, without changing the original SSE error or completion semantics.
- Add application-scoped cursor pagination that loads the latest 10 messages first and supports loading older messages without timestamp-collision gaps.
- Restrict application history to the application owner and administrators; anonymous users and unrelated authenticated users cannot read it.
- Add an administrator-only paginated history view across all active applications, ordered by creation time descending.
- Logically delete an application's history in the same database operation as the application deletion while preserving the existing deployment rollback guarantees.
- Add the chat-history persistence model, message-type enum, mapper, service, controller, DTOs, VOs, schema indexes, OpenAPI contracts, and focused tests.

## Capabilities

### New Capabilities

- `chat-history`: Persistent user/AI conversation records, application-scoped cursor retrieval, authorization, failure recording, and administrator moderation queries.

### Modified Capabilities

- `app-management`: Code generation now records its effective user input and terminal AI outcome, and application deletion also removes associated chat history.

## Impact

- Adds a `chat_history` table contract and updates `sql/init.sql` with stable cursor and moderation indexes.
- Adds ChatHistory domain, enum, DTO, VO, mapper, service, controller, and test classes under the existing package layout.
- Changes `AppServiceImpl` generation and deletion orchestration, including reactive success, error, and cancellation paths.
- Adds protected `/chatHistory` APIs for owner/administrator history loading and administrator-wide moderation.
- Requires frontend consumers to treat authorization failures as failures rather than empty history and to use the returned cursor when prepending older messages.
- Introduces no new external dependencies and no breaking change to existing request or SSE content-event shapes.
