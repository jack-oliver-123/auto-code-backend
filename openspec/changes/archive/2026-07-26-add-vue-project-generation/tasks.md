## 1. Configuration and trusted scaffold

- [x] 1.1 Add the `VUE_PROJECT` code-generation type and type-specific artifact layout metadata while preserving the behavior of existing HTML and multi-file applications.
- [x] 1.2 Add configuration properties for project response size, source file count and size, path depth, build timeout, build concurrency, builder image, container resources, output limits, and diagnostic limits.
- [x] 1.3 Validate all Vue project configuration at startup, including positive bounds, the combined file-count limit below 30, coherent byte limits, and a nonblank builder image.
- [x] 1.4 Add tracked environment-backed defaults to application configuration without introducing credentials or host-specific paths.
- [x] 1.5 Add the canonical backend-owned Vue scaffold resources for `index.html`, `package.json`, lockfile, and Vite configuration with relative-base deployment support.
- [x] 1.6 Add tests proving the trusted scaffold has compatible pinned dependencies and invalid or incoherent configuration prevents application startup.

## 2. AI contracts and project protocol

- [x] 2.1 Add immutable Vue project and project-file result models for structured generation, with field descriptions and no client-controlled scaffold fields.
- [x] 2.2 Update the streaming Vue system prompt to distinguish code-generation and ordinary-conversation modes and to emit complete snapshots using the versioned project protocol.
- [x] 2.3 Add the structured Vue system prompt with the same ownership, file-count, path, dependency, hash-routing, and complete-snapshot rules as the streaming prompt.
- [x] 2.4 Extend the AI service contract and generation facade with structured and streaming Vue project methods without registering nonexistent model file tools.
- [x] 2.5 Implement a parser for the exact `AUTO_CODE_PROJECT_V1` envelope and fenced file blocks, preserving every file byte after stream chunks have been concatenated.
- [x] 2.6 Classify a response with no opening project marker as ordinary conversation, but reject malformed, incomplete, duplicated, trailing, or unsupported project envelopes as generation errors.
- [x] 2.7 Enforce the configured total AI response limit while accumulating a project response, without trimming or altering SSE chunks forwarded to the client.
- [x] 2.8 Add focused prompt and parser tests for valid projects, normal questions, whitespace preservation, split stream markers, malformed envelopes, unsupported versions, and response overflow.

## 3. Project validation and safe materialization

- [x] 3.1 Implement a Vue source validator that permits only the approved model-owned `src/**` and optional `public/**` files and requires `src/main.js`, `src/App.vue`, and `src/router/index.js`.
- [x] 3.2 Reject protected scaffold paths, unsupported extensions, absolute paths, drive-qualified paths, backslashes, traversal segments, hidden or temporary paths, excessive path depth, and empty file names.
- [x] 3.3 Reject case-insensitive duplicate paths and enforce per-file, total-source, and combined scaffold-plus-source file-count limits before writing any model output.
- [x] 3.4 Implement contained UTF-8 project materialization that creates regular nested files only and refuses symbolic links or any resolved path outside the staging root.
- [x] 3.5 Materialize the trusted scaffold independently of model output and verify the generated router uses hash history before a build can begin.
- [x] 3.6 Add validation and materialization tests covering Windows path forms, case collisions, long application IDs, all configured bounds, protected-file collisions, symbolic links, and partial-write cleanup.

## 4. Isolated and bounded Vue builder

- [x] 4.1 Introduce an injectable `VueProjectBuilder` contract and build-result model so orchestration and publication tests can use deterministic fakes.
- [x] 4.2 Implement the container builder using argument-list process execution with a unique container name and no shell interpolation.
- [x] 4.3 Run builds as a non-root user with networking disabled, capabilities dropped, a read-only container filesystem, no application secrets, and configured CPU, memory, process, and temporary-storage limits.
- [x] 4.4 Mount only the staging workspace and use the trusted image toolchain without running `npm install`, model-supplied package scripts, or the generated project's Vite configuration.
- [x] 4.5 Add a fair configured concurrency limit and ensure timeout, cancellation, interruption, lease loss, and startup failure terminate only the exact build process and container and always release the permit.
- [x] 4.6 Drain standard output and error without deadlock, cap retained output, sanitize user-facing diagnostics, and prevent source code or environment secrets from appearing in errors or logs.
- [x] 4.7 Validate the produced `dist` tree for a regular `index.html`, allowed contained files, byte and file-count limits, and absence of links, hidden files, temporary files, or runtime source artifacts.
- [x] 4.8 Add unit tests for success, unavailable runtime, nonzero exit, timeout, cancellation, interruption, concurrency saturation, oversized logs, invalid output, and cleanup.
- [x] 4.9 Add a trusted builder image definition and an opt-in smoke test that builds a minimal fixture and verifies the pinned Node and package-manager compatibility without calling an AI provider.

## 5. Atomic project publication

- [x] 5.1 Refactor code saving behind an injected type dispatcher or registry while keeping the existing HTML and multi-file saver contracts and outputs unchanged.
- [x] 5.2 Implement the Vue project saver pipeline: create a staging tree, add the trusted scaffold, validate and write model source, run the isolated build, validate `dist`, and remove transient build artifacts.
- [x] 5.3 Publish the complete source-plus-`dist` project atomically to `tmp/code_output/vue_project_{appId}` through the existing directory publication lifecycle.
- [x] 5.4 Attach Vue publication state to generation finalization so downstream database or preview failure can restore the previous stable application version.
- [x] 5.5 Add publication tests for first generation, repeat generation, deleted source files, build failure, invalid `dist`, downstream rollback, stable long-ID paths, and staging or backup cleanup.
- [x] 5.6 Implement and test equivalent structured and streaming Vue save paths so both enforce the same validation, build, publication, and rollback rules.

## 6. Source context and chat memory

- [x] 6.1 Implement a deterministic stable-source loader for later Vue requests that reads only approved model-owned `src/**` and optional `public/**` files.
- [x] 6.2 Exclude backend-owned scaffold files, `dist`, dependencies, staging data, and publication backups from source context and reject unsafe or nonregular entries.
- [x] 6.3 Enforce complete source-context byte and file limits and fail before invoking the AI provider instead of silently truncating project source.
- [x] 6.4 Compose later-generation context in the defined order of persisted conversation, stable source snapshot, and current user message, with the current message included exactly once.
- [x] 6.5 Keep first generation free of prior source or conversation context and keep full project source out of Redis chat-memory entries.
- [x] 6.6 Add tests for Redis hits, MySQL fallback, ordinary questions, deterministic ordering, missing source, unsafe source, excessive source, initial generation, and absence of full source in caches and logs.

## 7. Application generation orchestration

- [x] 7.1 Make a new application's first successful generation default to Vue project generation while continuing to route persisted HTML and multi-file applications by their existing type.
- [x] 7.2 Keep `codeGenType` unset until successful finalization, ignore the client message on first generation in favor of stored `initPrompt`, and preserve retry-as-initial behavior after failure.
- [x] 7.3 Route Vue structured and streaming results through project classification so ordinary conversation does not build or replace files and malformed project output cannot be treated as conversation.
- [x] 7.4 Hold the per-application processing lease across AI generation, validation, build, publication, preview creation, history persistence, and type persistence, including cancellation and lease-loss cleanup.
- [x] 7.5 Save the user message before AI work, save successful AI content or bounded failure information afterward, and avoid storing project source or sensitive builder diagnostics as chat memory.
- [x] 7.6 Emit the SSE `done` event only after Vue build, atomic publication, required preview and database updates, and history persistence all succeed; never emit it on error or cancellation.
- [x] 7.7 Add service and controller tests for ownership, first and later generation, existing application types, ordinary conversation, malformed projects, build failure, persistence failure, cancellation, concurrent operations, and completion ordering.

## 8. Preview and deployment

- [x] 8.1 Add a type-aware static artifact resolver that maps HTML and multi-file applications to their existing web roots and Vue projects exclusively to validated `dist`.
- [x] 8.2 Update preview snapshot publication to copy only the resolved static web root, preserving immutable-preview rollback and the existing multi-file bundling behavior.
- [x] 8.3 Update deployment to publish only the resolved static web root and preserve the previous deployed snapshot when validation or copying fails.
- [x] 8.4 Extend the legacy static redirect to support `vue_project_{appId}` using full positive `Long` application IDs without weakening traversal checks.
- [x] 8.5 Add preview and deployment tests for source-file nonexposure, MIME types, hash-route refreshes, subpath-relative assets, repeat deployment, rollback, long IDs, missing `dist`, and the existing isolated-preview CSP.
- [x] 8.6 Update deployment and operations documentation with the Vue artifact layout, trusted builder image, required container runtime, configuration, resource limits, and failure diagnostics.

## 9. End-to-end verification

- [x] 9.1 Add a provider-free end-to-end fixture test from streamed V1 output through parsing, isolated fake build, publication, preview resolution, history persistence, and `done` ordering.
- [x] 9.2 Run the focused parser, validator, builder, saver, source-context, application-service, controller, preview, and deployment test classes and resolve all failures.
- [x] 9.3 Run `mvn clean test` and confirm normal tests neither call paid AI providers nor require a locally installed Node.js toolchain or container runtime.
- [x] 9.4 Run the opt-in real builder smoke test when the trusted image and container runtime are available, recording an explicit skip reason otherwise.
- [x] 9.5 Inspect configuration and runtime commands to confirm no tracked secrets, host-side package execution, network-enabled builds, or model file-tool registrations were introduced.
- [x] 9.6 Run `git diff --check` and inspect `git status` to ensure the change contains no formatting defects or unrelated modifications.
