## Why

Generated applications currently stop at a stable directory under `tmp/code_output`, so owners cannot publish them at a durable public URL or refresh an existing deployment safely. The backend needs an explicit deployment boundary that preserves ownership, key uniqueness, prior deployed versions, and the existing generation lifecycle.

## What Changes

- Add an authenticated owner-only `POST /app/deploy` operation that publishes the latest successfully generated code and returns deployment metadata.
- Generate a server-owned six-character alphanumeric `deployKey` once per application, reuse it for every redeployment, and resolve collisions using the database unique constraint.
- Publish an exact directory snapshot to `tmp/code_deploy/{deployKey}` through staging, replacement, and rollback so a failed deployment cannot damage the prior deployed version.
- Record `deployedTime` only after successful publication and expose deployment URLs from a configurable, session-isolated static origin.
- Make generation, deployment, and deletion mutually exclusive for the same application within the process.
- Extend owner and administrator deletion so deleting a deployed application also removes its publicly served deployment.

## Capabilities

### New Capabilities

- `app-deployment`: Owner authorization, stable deployment keys and URLs, collision handling, atomic file publication, redeployment, metadata, concurrency, and static-host boundaries.

### Modified Capabilities

- `app-management`: Owner and administrator deletion additionally undeploys an application's public files while preserving the existing authorization and logical-deletion behavior.

## Impact

- Affects the App controller, service contract and implementation, deployment response models, application configuration, and App service tests.
- Adds a filesystem deployment component under `core` and shares the existing staging/backup publication semantics used by generated-code saving.
- Relies on the existing `app.deployKey` unique index, including keys retained by logically deleted records; no new runtime dependency is required.
- Requires an external static server or deployment host to serve the configured deployment root from an origin isolated from backend session cookies.
