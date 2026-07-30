## Context

The generation core currently supports two fixed result shapes. `HTML` accepts one complete document, while `MULTI_FILE` extracts exactly one HTML, CSS, and JavaScript Markdown block. `AppServiceImpl` treats a null `app.codeGenType` as the initial-generation state and currently selects `MULTI_FILE`; after a successful stream, parsing and saving publish a staged directory to `tmp/code_output/{codeGenType}_{appId}` before preview and history finalization commit it. Existing HTML and multi-file directories are already protected by application-scoped distributed leases, rollback-capable directory publication, isolated immutable previews, and explicit deployment snapshots.

A Vue/Vite project differs in three ways. It has a variable nested file set, its source cannot be served directly by the static preview listener, and producing `dist` requires running a JavaScript build toolchain. The current LangChain4j `AiServices` registration exposes only chat models and no tools, so the initial Vue prompt's instruction to call a file-writing tool cannot work. More importantly, executing model-controlled `package.json` scripts or `vite.config.js` on the backend host would give untrusted generated content access to host credentials and arbitrary command execution.

Later generation also needs a source-of-truth distinction. MySQL preserves the full AI reply for audit, but Redis conversation memory intentionally truncates each message to 6,000 characters and the complete context to 24,000 characters. That is appropriate for conversation but insufficient for reconstructing a multi-component project. The current stable project directory therefore needs to provide separate, bounded source context.

The repository has no build image or project-builder abstraction. Docker is available in the current development environment, but runtime availability and image identity must remain externalized deployment concerns. The existing static saver executor also cannot inject such a runtime dependency, so the generation publication boundary must become dependency-injected without changing the behavior of the two existing modes.

## Goals / Non-Goals

**Goals:**

- Add `vue_project` as a fully supported generated-code type and make it the default for new first generations.
- Produce a complete, runnable Vue 3/Vite/Hash Router source tree from bounded model-owned files plus a trusted backend scaffold.
- Build generated source without exposing backend credentials, host files, network access, or unrestricted compute to untrusted content.
- Preserve the current SSE chunk contract, terminal history ordering, distributed lease behavior, immutable preview model, and rollback of failed regeneration.
- Make later Vue edits use the current stable model-owned source while still receiving bounded conversation memory and exactly one current user message.
- Publish only validated static `dist` files to preview and deployment targets.
- Keep all file paths, response sizes, build outputs, diagnostics, and cleanup behavior bounded and testable.

**Non-Goals:**

- Automatically classify prompts among HTML, multi-file, and Vue generation.
- Add a client generation-mode selector or allow an application to change type after its first successful generation.
- Add project archive download, source browsing, online editing, dependency installation, arbitrary npm packages, server-side rendering, or a persistent Vite development server.
- Relax the isolated preview's network, form, object, frame, or credential boundaries. Generated projects must remain functional when third-party assets are unavailable in preview.
- Run paid or nondeterministic model calls in normal tests.

## Decisions

### 1. Use an explicit versioned project envelope instead of model-side filesystem tools

The streaming Vue prompt will define two response modes. An ordinary answer contains natural language and no project envelope. A code response contains exactly one envelope delimited by line-oriented `<<<AUTO_CODE_PROJECT_V1>>>` and `<<<END_AUTO_CODE_PROJECT_V1>>>` markers. Inside it, each model-owned file is represented by a `FILE: <relative-posix-path>` line followed by one fenced source block. A brief plan or completion sentence may appear outside the envelope and remains part of the byte-preserved SSE stream and durable AI history.

The parser will distinguish three outcomes:

- no opening marker: no code intent, eligible for the existing later-turn ordinary-conversation path;
- a complete, valid envelope: a `VueProjectCodeResult` containing ordered immutable project files;
- any opening marker with malformed, incomplete, duplicated, or invalid content: invalid code intent, which fails generation and can never be accepted as ordinary conversation.

Only text source extensions allowed by configuration will be accepted, so nested Markdown fences are not a supported project-file use case. The exact protocol version permits later evolution without silently interpreting a new shape as V1. The normal streaming endpoint will use this protocol. For parity with the existing generation facade, a separate non-streaming structured prompt and `VueProjectCodeResult` method will use the same path and content contract without Markdown wrappers.

Alternatives considered:

- Registering write tools on the singleton AI service was rejected because reactive concurrent requests need request-scoped state, partial tool writes conflict with atomic publication, tool callbacks complicate SSE content, and an unsafe path bug would write during provider execution.
- A large JSON streaming document was rejected because source newlines and quotes substantially increase token overhead and malformed escaping is harder for users to inspect. The versioned envelope retains the project's existing human-readable fenced-output pattern while adding an unambiguous intent marker.
- Incremental patch operations were deferred because first-generation creation and follow-up deletion semantics become much harder to validate and roll back. V1 always emits the complete model-owned source snapshot.

### 2. The backend owns executable scaffold and dependency files

The model will own only bounded text files under `src/` and an optional restricted `public/` subtree. It MUST provide `src/main.js`, `src/App.vue`, and `src/router/index.js`; other pages, components, utilities, and styles are optional. The backend will materialize canonical `index.html`, `package.json`, lockfile, and `vite.config.js` resources with relative Vite base, the `@` alias, Hash Router-compatible dependencies, no fixed development port, pinned dependency versions, and no lifecycle scripts.

The complete combined project must contain fewer than 30 files. Configuration will additionally bound model files, path characters and depth, per-file characters, aggregate source characters, complete AI response characters, and built-output files and bytes. All limits must be positive and internally consistent at startup.

Before any write, the project validator will reject null or blank content, absolute or drive-qualified paths, backslashes, empty or dot segments, `..`, hidden or publication-temporary segments, unsupported roots or extensions, overlong paths, case-insensitive duplicates, collisions with scaffold or `dist`, and missing required model files. Validation is case-insensitive even on a case-sensitive host so a project behaves consistently on the Windows development environment. Materialization resolves each normalized path beneath one staging directory, creates only required parent directories, writes UTF-8 regular files without following links, and then validates the complete regular-file tree.

The backend-owned scaffold makes the generated project reproducible and ensures that a user can run the documented package scripts after download is introduced. The model is still responsible for the application architecture and contents, but it cannot select packages or executable build configuration.

Alternative considered: accepting and merely inspecting model-generated `package.json` and Vite configuration was rejected. Text inspection cannot reliably prove that JavaScript configuration is safe to execute, and dependency/version drift would make build reproducibility depend on model behavior.

### 3. Build through an injected, isolated container runner

Introduce a `VueProjectBuilder` contract and a default container-backed implementation. The builder image contains the exact Node, Vue, Vue Router, Vite, Vue plugin, lockfile, and trusted build entrypoint required by the backend scaffold. Runtime generation does not run `npm install`, does not execute project package scripts, and explicitly invokes the image's trusted build entrypoint against the staged project.

The container command will be assembled as argument values rather than a shell string. It will mount only the one staging workspace, receive no backend environment variables or credentials, run as a non-root user, disable networking, use a read-only container filesystem apart from the workspace and bounded temporary storage, drop capabilities, enable no-new-privileges, and apply configured CPU, memory, process, wall-clock, output-log, and global concurrent-build limits. A uniquely identified container/process will be terminated and removed on timeout, thread interruption, reactive cancellation, lease loss, startup failure, or abnormal exit.

The builder returns only after a zero exit status and a regular `dist` tree exists. Diagnostic output is bounded and sanitized; application logs contain app id, operation, duration, exit category, and sizes but never generated source, provider payloads, host environment, credentials, or unrestricted container output. Runtime or image unavailability fails only Vue generation with an operation error and does not disable existing HTML or multi-file modes.

Alternatives considered:

- Running local `npm install` or `npm run build` with `ProcessBuilder` was rejected because child processes inherit a sensitive host boundary and generated Vite configuration is executable JavaScript.
- Serving a Vite development process was rejected because it is stateful, exposes a compiler server, complicates tenancy and cleanup, and is unsuitable for immutable preview/deployment.
- Skipping the build was rejected because browsers cannot resolve `.vue` files, `@` aliases, or npm dependencies from the static source tree.

### 4. Build and validate before rollback-capable atomic publication

Vue publication will create a sibling staging directory below the configured code-output root, validate and materialize the full source, run the builder in that staging directory, remove any forbidden transient build content such as `node_modules`, and validate `dist` as a bounded regular tree containing a regular `index.html`. The validator will reject links, hidden or temporary output paths, escaped paths, missing entry files, and excessive file count or bytes.

Only then will the existing `DirectoryPublisher` replace `tmp/code_output/vue_project_{appId}` and retain the former target as an unresolved backup. The resulting `CodeFilePublication` attaches to the existing `CodeGenerationSession`; preview creation, successful AI-history persistence, initial `codeGenType` persistence, and other required application finalization occur before publication commit. Any downstream failure or cancellation rolls the publication back to the former complete project. A successful commit removes the backup best-effort.

The source root contains the trusted scaffold, model-owned source, and `dist`, but never `node_modules`, provider data, or builder credentials. The complete `Long appId` remains part of the stable directory name. Failed first generation leaves no stable partial directory; failed regeneration leaves the prior source and `dist` unchanged.

To support an injected builder, generation publication will move behind an instance-based dispatcher/registry selected by `CodeGenTypeEnum`. Existing HTML and multi-file parser/saver behavior will be adapted behind that dispatcher without changing their output or validation contracts. This avoids introducing a static global builder and keeps tests able to inject deterministic fake builders.

### 5. Add current Vue source as a separate bounded context

For a later `vue_project` turn, while holding the application lease and after persisting the current user-history row, a project context loader will read only the current stable model-owned `src/` and permitted `public/` files. It will reapply the same containment, regular-tree, path, file-count, and content-size rules used at publication. Scaffold files, `dist`, temporary paths, and hidden entries are excluded. Files are serialized in deterministic path order with explicit context markers.

The final provider request is composed in this order: bounded prior conversation, bounded current project source, and one exact current user message. The current user-history id remains the exclusive conversation boundary. Initial generation includes neither prior conversation nor project source. Full project source is not written to Redis memory as a separate cache value; MySQL history remains the audit source and the stable generated directory remains the editable-source truth.

Project context will not be silently truncated because a partial source tree can cause destructive regeneration. If the stable tree is missing, unsafe, incomplete, or exceeds configured context limits, the request fails before AI invocation, records the existing safe terminal failure when possible, emits no `done`, and preserves the old project. Ordinary conversation still receives source context so the model can answer application-specific questions, but a response without a project envelope does not rewrite or rebuild it.

Alternative considered: relying on prior AI chat history was rejected because the existing memory limits intentionally truncate large code replies and a cache miss can produce a different subset. Tool-based on-demand file reads were deferred with the rest of model tool calling.

### 6. Keep generation type immutable after first success

When `app.codeGenType` is null, generation continues to treat the request as initial, uses stored `initPrompt`, ignores the client message, and now selects `VUE_PROJECT`. The selected type is persisted only during the existing successful finalization, so a failed or crashed first build leaves the app retryable as an initial Vue generation without a schema change. Once a type is stored, all later generation, preview, and deployment operations resolve that exact type. Existing `html` and `multi_file` applications therefore preserve their current behavior.

Automatic routing and client-selected types remain separate product changes. This avoids overloading `codeGenType` with both a requested type and a completion-state marker or adding a new database state solely for this feature.

### 7. Preserve SSE, ordinary conversation, history, lease, and memory ordering

Every provider chunk remains byte-for-byte unchanged in the existing `{"d":"<chunk>"}` SSE content event and in the accumulated AI reply. The accumulator enforces a configured maximum without trimming or modifying emitted chunks. Parsing, materialization, container build, `dist` validation, rollback-capable publication, preview preparation, required database updates, and successful AI-history persistence are required before the named `done` event. Redis memory refresh remains recoverable and outside the required `done` dependency.

An absent Vue project marker on a later non-blank reply is eligible for ordinary conversation. An initial reply without a valid project, or any reply that starts a project envelope and then violates the protocol, is a generation failure. Build failure, timeout, cancellation, lease loss, source-context failure, and output-limit failure use the existing safe AI failure/cancellation history paths, never emit `done`, terminate or clean builder work, and release the distributed lease.

### 8. Resolve a type-specific static web root for preview and deployment

Introduce one generated-artifact layout abstraction used by preview and deployment. For `HTML` and `MULTI_FILE`, the static web root remains the stable generated directory and required files remain unchanged. For `VUE_PROJECT`, the editable root is `tmp/code_output/vue_project_{appId}`, the static web root is its `dist` child, and the required static entry is `dist/index.html` plus a safe bounded regular asset tree.

Immutable preview snapshots and deployment staging copy only the resolved static web root. Multi-file preview bundling remains limited to `MULTI_FILE`; Vue assets are already compiled and are copied unchanged. The legacy owner-authenticated `/api/static/{codeGenType}_{appId}` redirect accepts `vue_project` while retaining positive full-`Long` parsing and all authorization. Relative Vite base and hash history allow built assets and routes to work under tokenized preview and arbitrary deployment-key subpaths.

The existing isolated preview CSP and no-network posture remain unchanged. The Vue prompt must not make core functionality depend on remote assets; external placeholders can remain progressive enhancement for public deployment but may be unavailable in preview.

### 9. Externalize limits and verify configuration at startup

A dedicated configuration-properties object will externalize protocol version support, response characters, combined and model file counts, per-file and aggregate source characters, path length/depth, source-context characters, built file count/bytes, timeout, global concurrency, container executable/image, CPU, memory, process limit, and bounded diagnostic bytes. Numeric and duration limits must be positive, the combined file limit must remain below 30, and dependent limits must be coherent. Tracked configuration contains safe development defaults and no registry credentials.

Unit tests use fake builders and temporary output roots and never require Docker or paid AI calls. A separate opt-in integration profile may build a minimal project with the real builder image and verify its `dist`; normal `mvn clean test` remains deterministic.

## Risks / Trade-offs

- [The versioned fenced protocol can still be truncated or malformed by a model] -> Use explicit intent markers, strict whole-response parsing, bounded accumulation, safe failure history, and no publication before complete validation.
- [A compiler or bundler vulnerability may process hostile source] -> Keep the builder inside a non-root, no-network, resource-limited container with a pinned image and no host secrets; validate output again outside the container.
- [Container startup and Vue compilation increase completion latency] -> Bound build concurrency and duration, expose content-free timing/failure metrics, and keep HTML/multi-file paths free of builder overhead.
- [Builder image and scaffold versions can drift] -> Version them together, pin dependencies, include a compatibility label/check, and verify a representative project before rollout.
- [Full source context consumes substantial model input] -> Exclude scaffold and `dist`, enforce the same small project limits as generation, order files deterministically, and fail rather than send a destructive partial snapshot.
- [Switching the default changes cost and output for every new app] -> Preserve stored types for existing apps, keep the change explicit in release notes, monitor build capacity, and allow rollback of the default before removing Vue support.
- [A previous backend version cannot interpret persisted `vue_project`] -> Rollback must first stop new generation and retain a Vue-capable backend for existing Vue preview/deploy requests, or temporarily accept those operations as unavailable while preserving their directories and database rows.
- [Remote placeholder images may be blocked in isolated preview] -> Keep the security boundary; require the generated application's core layout and interactions to remain usable without third-party network resources.

## Migration Plan

1. Build, scan, and publish the pinned Vue builder image and record the image reference expected by the backend scaffold.
2. Configure the container executable/image and conservative resource limits in each environment; verify the output, deployment, and preview roots remain non-overlapping and have adequate capacity.
3. Deploy the backend with `vue_project` parsing/build support before allowing new initial generation. Existing HTML and multi-file applications continue operating throughout rollout.
4. Run an opt-in builder smoke test, then generate one new Vue application and verify SSE completion ordering, immutable preview assets, hash navigation, explicit deployment, regeneration replacement, and rollback after an induced build failure.
5. Monitor builder availability, build latency, rejection categories, staging cleanup, output sizes, and code-output storage without logging source content.

Rollback disables new Vue generation first. Existing Vue directories and `codeGenType` rows are retained. Roll back the default selection independently if the deployed binary still understands Vue; do not deploy a binary that cannot interpret `vue_project` until Vue applications are migrated or their preview/deployment unavailability is accepted. No database downgrade is required.

## Open Questions

None. Automatic routing, client-selectable types, archive download, and broader preview-network policy are intentionally deferred.
