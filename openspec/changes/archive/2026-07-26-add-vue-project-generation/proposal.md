## Why

The backend can publish one HTML document or a fixed HTML/CSS/JavaScript set, but it cannot materialize, build, preview, or deploy a componentized Vue application. The new Vue system prompt also assumes model-side file-writing tools that the current LangChain4j service does not expose, so a backend-owned project protocol and build boundary are required before project generation can work reliably or safely.

## What Changes

- **BREAKING** Make `vue_project` the default generation type for a new application's first successful generation; applications with a persisted `html` or `multi_file` type continue using that type.
- Add a versioned, stream-compatible Vue project response contract that distinguishes a project snapshot from ordinary conversation without model-side filesystem tools.
- Parse and validate a bounded set of model-owned project files, combine them with a backend-owned Vue/Vite scaffold, and atomically publish a complete source tree only after a successful isolated build.
- Build Vue projects with a configurable, resource-bounded, network-disabled container runner that receives no backend credentials and never executes model-controlled package scripts or Vite configuration on the backend host.
- Store Vue source and its validated `dist` output under the stable `tmp/code_output/vue_project_{appId}` directory while preserving the prior complete project on parsing, build, preview, persistence, lease-loss, error, or cancellation failures.
- Use the built `dist` directory for immutable previews and explicit deployment, while preserving existing preview isolation, deployment rollback, and SSE `done` ordering.
- Supply later Vue generations with a bounded snapshot of the current stable model-owned source in addition to bounded conversation memory, so follow-up edits do not depend on truncated AI history.
- Update the Vue prompt to support code and ordinary-conversation modes, emit the backend project protocol, and stop claiming access to unavailable file-writing tools.
- Keep automatic code-type routing, client-selectable generation modes, and project archive/download endpoints out of scope.

## Capabilities

### New Capabilities

- `vue-project-generation`: Defines the versioned project response, safe source materialization, trusted scaffold, isolated build, bounded resource use, and atomic Vue project publication.

### Modified Capabilities

- `app-management`: Changes the initial generation type and extends generation, ordinary-conversation, failure, cancellation, and completion behavior to Vue projects.
- `app-deployment`: Makes Vue preview and deployment publish only a validated `dist` snapshot while retaining existing ownership, isolation, and rollback guarantees.
- `chat-memory`: Adds durable-source context for later Vue edits without placing full project snapshots in Redis conversation memory.

## Impact

- Affects `CodeGenTypeEnum`, `AiCodeGeneratorService`, generation facade/session orchestration, response parsing, file publication, application generation, preview redirects, preview snapshots, and deployment source resolution.
- Adds project-generation configuration for response and file limits, source-context limits, build timeout, concurrency, container image, and resource restrictions.
- Adds an operational dependency on a prebuilt Vue builder container image and an available container runtime for `vue_project` requests; normal HTML and multi-file generation remain independent of that builder.
- Adds backend-owned Vue scaffold resources and build-image assets, plus focused parser, path-safety, builder, publication, reactive lifecycle, preview, deployment, prompt-contract, and configuration tests.
- Does not require a new public endpoint or a database schema change; existing `codeGenType` storage can persist `vue_project` after the first generation completes.
