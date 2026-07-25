## 1. Deployment Contract And Configuration

- [x] 1.1 Add typed `app.deployment.root-dir` / `app.deployment.host` configuration with environment overrides, `tmp/code_deploy` / `http://localhost` development defaults, non-overlapping-root validation, path/URL normalization, and test overrides.
- [x] 1.2 Add the narrow `AppDeployVO` response containing `deployKey`, trailing-slash `deployUrl`, and `deployedTime`, and add `deployApp(appId, loginUser)` to the App service contract.
- [x] 1.3 Add a server-owned deploy-key generator using `SecureRandom` and the exact `[A-Za-z0-9]` alphabet, with deterministic seams for collision and retry tests.

## 2. Reversible Filesystem Publication

- [x] 2.1 Extract or introduce a focused directory-publication utility for same-root staging, backup, atomic-move fallback, commit cleanup, and rollback while preserving all existing generated-code saver behavior and tests.
- [x] 2.2 Implement the deployment filesystem component to validate normalized contained paths, type-specific required regular files, six-character stored keys, and rejection of every symbolic link before staging an exact source snapshot.
- [x] 2.3 Implement reversible first publication and redeployment at `{deployRoot}/{deployKey}`, including exact directory replacement, obsolete-file removal, staging cleanup, previous-version restoration, and orphan-target protection.
- [x] 2.4 Implement reversible undeployment that moves a public directory to a hidden tombstone, supports restoration after database failure, treats a missing target as already undeployed, and performs best-effort committed cleanup.
- [x] 2.5 Add focused filesystem tests for HTML and multi-file snapshots, repeat deployment, invalid paths/keys/sources, copy and move failures, metadata-triggered rollback handles, undeployment restoration, and temporary-directory cleanup.

## 3. App Service Orchestration

- [x] 3.1 Implement owner-only deployment validation and reuse the existing per-App processing guard so generation, deployment, and both deletion paths are mutually exclusive and always release state after termination.
- [x] 3.2 Implement conditional first-key reservation with `id + userId + deployKey IS NULL`, database-unique-constraint collision retries (including logically deleted and case-equivalent keys), concurrent-assignment reload, bounded exhaustion, and exact reuse of stored keys.
- [x] 3.3 Orchestrate staging, key resolution, reversible publication, owner-constrained `deployedTime` updates, confirmed-failure compensation, committed-after-error rereads, indeterminate-outcome preservation, committed cleanup, and `AppDeployVO` construction without holding a database transaction across file I/O.
- [x] 3.4 Extend owner and administrator deletion to prepare undeployment before logical deletion, restore the public directory when deletion fails, and commit hidden-directory cleanup only after database success.
- [x] 3.5 Expand App service tests for invalid login/id, missing and foreign Apps, administrator non-owner rejection, unsupported or incomplete generation, first deployment, stable redeployment, every key race/failure path, timestamp commit/rollback/uncertainty semantics, publication compensation, deletion compensation, and same/different-App concurrency.

## 4. HTTP API

- [x] 4.1 Add authenticated `POST /app/deploy` handling with controller-level positive-id validation, current-user resolution, service delegation, and consistent `BaseResponse<AppDeployVO>` wrapping.
- [x] 4.2 Expand App controller tests for successful metadata serialization, invalid ids, anonymous and initial-password users, owner/service errors, and confirmation that generation SSE `done` behavior remains unchanged.

## 5. Operations And Verification

- [x] 5.1 Document the persistent deployment-root, isolated static-host, directory-index, MIME-type, directory-listing, cache, session-cookie, and existing `uk_deployKey` rollout prerequisites without adding same-origin Spring static serving.
- [x] 5.2 Run the narrow App controller/service/core deployment and generated-code saver tests, then run `mvn clean test` to catch cross-module regressions.
- [x] 5.3 Run `openspec validate add-app-deployment --strict`, `git diff --check`, and `git status --short`, and inspect the final diff to confirm only intended deployment and App lifecycle changes are included.
