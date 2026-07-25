## Context

The App module already stores `deployKey` and `deployedTime`, exposes them through its views, and has a unique index on `deployKey`. Successful generation publishes a complete snapshot to `tmp/code_output/{codeGenType}_{appId}` through a staging-and-backup sequence. `AppServiceImpl` also owns an in-process `processingAppIds` guard that currently serializes generation and deletion for one application.

No deployment service or endpoint exists. `AppConstant` contains preliminary deployment-root and host constants, but the backend has no resource mapping and the repository has no Nginx configuration. Generated HTML and JavaScript are untrusted content, so production deployment must use an independently hosted static origin rather than the backend session origin.

Deployment spans MySQL and a filesystem, which cannot participate in one atomic transaction. The design must therefore define observable states and compensating actions. In particular, a key can be reserved before file publication while `deployedTime` remains null; only the timestamp represents a completed deployment.

## Goals / Non-Goals

**Goals:**

- Provide an explicit, authenticated, owner-only deployment operation after generation completes.
- Give every application one stable six-character alphanumeric URL key and safely handle database and filesystem collisions.
- Publish and redeploy exact directory snapshots without exposing partial files or damaging the previous deployment on recoverable failures.
- Keep generation, deployment, and deletion mutually exclusive for one application in the current process.
- Make deletion remove a deployed site from public service with rollback when database deletion fails.
- Return stable deployment metadata and support environment-specific persistent storage and an isolated static host.

**Non-Goals:**

- Automatically deploy as part of the generation SSE flow or change the existing `done` event contract.
- Treat `deployKey` as a secret or provide private, authenticated deployed sites.
- Serve generated files through Spring MVC or ship a production Nginx/CDN configuration in this change.
- Add a distributed lock, object storage, or multi-node filesystem coordination.
- Rotate deployment keys, reclaim keys from logically deleted rows, or delete generated source under `code_output` when an App is deleted.

## Decisions

### 1. Deployment is an explicit owner-only application command

Add `POST /app/deploy` with a positive `@RequestParam Long appId`. The controller uses ordinary `@AuthCheck`, obtains the current user, delegates to `AppService.deployApp`, and returns `BaseResponse<AppDeployVO>`. `AppDeployVO` contains `deployKey`, `deployUrl`, and `deployedTime`.

The service repeats all trust-boundary checks: valid login user, active App lookup, exact owner comparison, supported persisted `CodeGenTypeEnum`, stable source directory, and the complete fixed regular-file set for that type (`index.html`, plus `style.css` and `script.js` for `MULTI_FILE`). An administrator who does not own the App receives the same no-authority response as any other user. No request model exposes a client-controlled key.

This keeps deployment separate from generation. The frontend calls it only after receiving generation `done`; a deployment failure therefore does not reinterpret a successfully completed generation or add blocking file work to a cancellable reactive stream. The alternative of automatically appending deployment to the SSE stream would remove the draft/publish boundary and complicate cancellation and completion ordering, so it is not used.

### 2. Service orchestration and filesystem publication remain separate

`AppServiceImpl` owns authorization, the App processing guard, key persistence, metadata updates, and error mapping. A dedicated core filesystem component owns normalized path construction, source validation, staging copy, final directory replacement, rollback, undeployment, and cleanup.

The filesystem component exposes reversible publication operations rather than updating the database itself. Its publication handle retains the prior backup until the service either commits after the metadata update or rolls back after a failure. Its undeployment handle similarly moves a public target to a hidden tombstone, then either deletes the tombstone after database deletion or restores it when deletion fails.

The staging, backup, and move primitives currently private to `CodeFileSaverTemplate` should be extracted into a focused internal directory-publication abstraction where this can preserve the existing saver contract and tests. Directly calling `FileUtil.copyContent` against the final directory is rejected because readers can observe a partial version and merge-copy can leave obsolete files.

### 3. Stage source files before allocating a first key

Deployment follows this order while holding the App processing guard:

1. Load and authorize the App, validate its type, and resolve the stable source beneath `CODE_OUTPUT_ROOT_DIR`.
2. Copy the complete source into a uniquely named hidden staging directory under the configured deployment root while rejecting every symbolic link.
3. Reuse the App's existing valid key or reserve its first key in the database.
4. Replace `{deployRoot}/{deployKey}` with the staged directory while retaining a rollback backup.
5. Update `deployedTime` with an `id + userId + deployKey` condition.
6. Commit filesystem cleanup and return the metadata; otherwise roll back the final directory and propagate an operation error.

Staging first means invalid or unreadable generated output cannot consume a key. A publish failure after key reservation can still leave `deployKey` non-null with `deployedTime` null; this is intentional and retryable. The retry reuses the key.

All paths are built with `Path`, converted to absolute normalized paths, and checked to remain beneath their configured roots. Configuration rejects equal or nested generated-output and deployment roots. The source tree accepts only regular files and directories and rejects every symbolic link rather than attempting to follow or preserve it. Existing stored keys must match `[A-Za-z0-9]{6}` before they are used as path segments. An invalid legacy value is reported as an operation error rather than silently rotated.

### 4. The database unique index is the key-allocation authority

Generate candidates with `SecureRandom` from the exact alphabet `A-Z`, `a-z`, and `0-9`. Before attempting assignment, skip a candidate whose final target already exists without a corresponding App record so an orphan directory is not overwritten. Reserve a candidate using an update constrained by `id`, `userId`, and `deployKey IS NULL`.

The unique index remains the final collision detector. Catch `DuplicateKeyException` and retry with another candidate up to a small fixed limit. Do not rely on an ordinary MyBatis-Plus existence query because logical-delete filtering hides rows whose keys still occupy the physical unique index. If the conditional update affects no row, reload the active owned App and reuse a concurrently assigned key; never overwrite it.

The current `utf8mb4_unicode_ci` column comparison treats case-only variants as equivalent, which is also safe on the development Windows filesystem. The generator may emit mixed-case keys, but collision handling follows the database's case-insensitive semantics and returned URLs preserve the exact stored spelling. Tightening the column to a case-sensitive collation is not part of this change.

### 5. Publication and metadata use explicit compensation

The deployment component creates staging beside the target so directory moves remain on the same filesystem. If a target exists, it moves the target to a unique hidden backup, moves staging to the target, and restores the backup if the replacement fails. Atomic move is attempted first and falls back to a regular move when unsupported, matching generated-code publication behavior.

Only after final publication does the service set a new `deployedTime`; every successful redeployment refreshes it. The service chooses an intended timestamp at database-supported precision that is strictly later than the previously stored timestamp, allowing a reread to identify this attempt even when two deployments begin within one database clock unit. A zero-row update or an exception whose subsequent read confirms the intended timestamp did not commit causes removal of a first deployment or restoration of the previous backup. If a read confirms the intended timestamp did commit despite the reported exception, publication is committed and returned as success. The reserved key is retained in every branch.

If the metadata call has an uncertain commit result and the App cannot be reread, the service cannot truthfully choose between the new and previous version. It returns an error, retains the final target and any rollback backup instead of destroying either snapshot, and logs the indeterminate state. A later idempotent deployment with the same key stages the desired source again, replaces the target, refreshes `deployedTime`, and leaves hidden crash/uncertainty artifacts available for controlled cleanup. This is a recovery posture, not cross-resource atomicity.

Backup cleanup failure after a confirmed successful metadata update is logged but does not turn a visible successful deployment into an error.

No long-lived database transaction spans file I/O. Such a transaction would still not make the filesystem atomic and would hold database resources while copying. Compensation provides the strongest practical semantics with the current schema.

### 6. The existing App processing guard covers deployment

Synchronous deployment acquires `processingAppIds` before loading the App and releases it in `finally`. Generation keeps its current deferred acquire and `doFinally` release. Owner deletion and administrator deletion retain their current guarded structure and add reversible undeployment inside it.

This guarantees that a deployment copies a stable generated snapshot and that deletion cannot race with publication in one JVM. Different App ids remain independent. Database conditional updates still protect key assignment races across instances, but full multi-instance same-App publication safety requires a distributed lock and shared-storage coordination and remains out of scope.

### 7. Deletion moves the public directory out of service before logical deletion

After authorization and while holding the same processing guard, deletion asks the filesystem component to prepare undeployment for the stored key. A missing target is treated as already undeployed. An existing target is moved to a hidden tombstone under the same root, making the public URL unavailable without destroying rollback data.

The service then performs its existing owner-constrained or administrator logical delete. A database failure restores the tombstone to the original target and returns an operation error. After a successful logical delete, tombstone cleanup is best effort: failure is logged, but the request succeeds because the public path is already unavailable. Generated source is retained because source-retention policy is outside this change.

### 8. Deployment root and host are typed configuration

Replace the hard-coded deployment root and host usage with an `app.deployment` configuration bean. `app.deployment.root-dir` (environment override `APP_DEPLOY_ROOT_DIR`) defaults to `tmp/code_deploy`, and `app.deployment.host` (environment override `APP_DEPLOY_HOST`) defaults to `http://localhost`. Configuration normalizes both filesystem roots and rejects equal or nested output/deployment roots. Production supplies a persistent-volume path and a separately hosted static origin. URL construction removes duplicate host slashes and always appends `/{deployKey}/` so relative CSS and JavaScript assets resolve correctly.

The backend does not register the deployment root as a Spring static resource location. Operations must configure a static server to use that root, enable `index.html` directory entry, disable directory listing, serve correct MIME types, and avoid stale caching for fixed asset names on redeployment. The production host must not receive backend session cookies; generated code is arbitrary executable content and is not trusted with the authenticated API origin.

## Risks / Trade-offs

- [A process crash or lost database response can occur between database and filesystem phases] -> Operations are idempotent, key reservation is stable, confirmed database outcomes are compensated, uncertain outcomes preserve both recoverable snapshots, and a retry repairs the normal key path; perfect crash atomicity would require a versioned deployment record or transactional storage model.
- [Six case-insensitive characters have a smaller effective namespace than true base-62] -> The unique index and bounded random retry make collisions safe; capacity or unguessability is not used as an authorization boundary.
- [A stable URL can serve cached files after redeployment] -> The external static server must avoid long-lived caching for `index.html`, `style.css`, and `script.js` unless future generation introduces content-hashed assets.
- [Backup or tombstone cleanup can leave hidden disk content] -> Public success is preserved, cleanup failures are logged, and operators can remove identifiable hidden directories after confirming no active operation owns them.
- [The in-memory guard does not coordinate multiple backend instances] -> Database conditions protect key ownership, but production must run a single writer per shared deployment root until a distributed guard is designed.
- [Static-server misconfiguration can expose generated content to application sessions] -> Use a separate production origin with no backend session-cookie scope and validate the host and persistent root during deployment rollout.

## Migration Plan

1. Verify every target MySQL environment has the existing `uk_deployKey` unique index; `CREATE TABLE IF NOT EXISTS` does not migrate an existing table.
2. Configure a persistent deployment root and an isolated static host, then configure the static server to serve key directories with directory indexes and safe cache behavior.
3. Deploy the backend changes with the deployment endpoint enabled and run owner, collision, redeployment, rollback, concurrency, and deletion tests.
4. Smoke-test one HTML and one multi-file App through generation, explicit deployment, asset loading, redeployment at the same URL, and deletion-based undeployment.
5. To roll back, disable or remove the endpoint and revert the backend code. No schema rollback is required; already deployed directories can remain available until explicitly removed according to the rollback decision.

## Open Questions

None. Environment-specific deployment paths, hostnames, and static-server configuration are rollout inputs rather than product-design decisions.
