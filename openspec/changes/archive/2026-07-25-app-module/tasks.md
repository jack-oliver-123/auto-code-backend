## 1. Persistence Model and Schema

- [x] 1.1 Rewrite the App domain model with camelCase fields, database-auto id mapping, Date timestamps, and MyBatis-Plus logical deletion.
- [x] 1.2 Replace the generated AppMapper CRUD contract with `BaseMapper<App>` and reduce AppMapper.xml to the project result-map and column-list pattern.
- [x] 1.3 Make the app table initialization idempotent and enforce a non-null `initPrompt` while preserving existing defaults and indexes.

## 2. API Contracts

- [x] 2.1 Add application priority constants for default (`0`) and featured (`99`) applications.
- [x] 2.2 Add narrow DTOs for creation, ordinary-user update, administrator update, name-only pagination, and administrator field queries.
- [x] 2.3 Add separate application summary and detail VOs that expose the required business fields without `isDelete`.

## 3. Application Service

- [x] 3.1 Create AppService and AppServiceImpl on `IService`/`ServiceImpl`, including shared parameter, length, priority, existence, and ownership validation helpers.
- [x] 3.2 Implement application creation with prompt normalization, owner binding, generated initial name, default priority, persistence failure handling, and id return.
- [x] 3.3 Implement owner-only ordinary update and logical deletion using field-limited objects and owner-constrained write conditions.
- [x] 3.4 Implement administrator field-limited update and unrestricted-owner logical deletion with consistent not-found and operation-error behavior.
- [x] 3.5 Implement user, featured, and administrator query wrappers with forced conditions, supported filters, stable default ordering, and a dynamic-sort whitelist.
- [x] 3.6 Implement domain-to-summary/detail conversion and Page record conversion without returning full prompts in list records.

## 4. HTTP Endpoints and Authorization

- [x] 4.1 Add authenticated user create, update, and delete endpoints plus the public application detail endpoint.
- [x] 4.2 Add the authenticated my-applications endpoint and public featured-applications endpoint with positive pagination and a 20-record maximum.
- [x] 4.3 Add administrator delete, partial update, and detail endpoints with `ADMIN_ROLE` authorization.
- [x] 4.4 Add the administrator field-query page endpoint, validate positive pagination, and override only that Page instance with `Long.MAX_VALUE` maxLimit.

## 5. Service Tests

- [x] 5.1 Add AppServiceImpl tests for creation normalization/defaults, invalid prompts, write failures, and VO field exposure.
- [x] 5.2 Add AppServiceImpl tests for owner update/delete success, foreign-owner rejection, missing records, approved-field validation, and constrained write conditions.
- [x] 5.3 Add AppServiceImpl query tests for name/owner/featured/admin filters, logical-delete-compatible wrappers, default ordering, and untrusted sort-field rejection.

## 6. Controller and Authorization Tests

- [x] 6.1 Add AppController tests for user create/update/delete/detail success and parameter, authentication, temporary-password, ownership, and not-found failures.
- [x] 6.2 Add AppController tests for my/featured pagination defaults, name filtering, 20/21 and non-positive boundaries, and summary response shape.
- [x] 6.3 Add AppController tests for every administrator route, including ordinary-user rejection, approved update fields, missing records, and detail responses.
- [x] 6.4 Verify administrator `pageSize = 101` is retained with per-Page `Long.MAX_VALUE` while invalid pagination is rejected.

## 7. Verification

- [x] 7.1 Run the App-focused tests and resolve all failures without weakening the specification assertions.
- [x] 7.2 Run the complete Maven test suite and confirm existing User, authentication, pagination, and AI-generation tests remain green.
- [x] 7.3 Validate the OpenSpec change in strict mode and review the final diff for accidental changes or exposed internal fields.
