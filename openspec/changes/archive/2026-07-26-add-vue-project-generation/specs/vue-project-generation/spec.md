## ADDED Requirements

### Requirement: Vue project responses have an explicit versioned contract
The system SHALL support `vue_project` as a code-generation type with both structured and streaming AI service contracts. A streaming Vue code response MUST contain exactly one project envelope delimited by line-oriented `<<<AUTO_CODE_PROJECT_V1>>>` and `<<<END_AUTO_CODE_PROJECT_V1>>>` markers. Each file inside the envelope MUST have one `FILE: <relative-posix-path>` declaration followed by exactly one non-empty fenced source block. Text outside the envelope MAY contain a short plan or completion statement and MUST NOT be interpreted as a project file.

The system SHALL accumulate the exact provider response subject to a positive configurable response-character limit and SHALL parse it only after normal provider completion. A response with no opening project marker MAY be considered ordinary conversation only for an application that already completed generation. Once an opening marker appears, an unsupported version, missing or repeated boundary, undeclared block, duplicate declaration, malformed fence, trailing project entry outside the boundary, or other protocol violation MUST be treated as invalid generated code rather than ordinary conversation.

#### Scenario: Provider returns a valid V1 project
- **WHEN** a Vue generation stream completes with one well-formed V1 envelope and valid declared files
- **THEN** the system constructs one ordered immutable Vue project result while preserving all original response chunks for SSE and history

#### Scenario: Later response is ordinary conversation
- **WHEN** a generated Vue application receives a later non-blank response that contains no project opening marker
- **THEN** the response is eligible for ordinary-conversation completion and no project parser treats its natural-language content as files

#### Scenario: Initial response contains no project
- **WHEN** first generation completes without a V1 project opening marker
- **THEN** generation fails as an invalid code response, publishes no project, and emits no `done` event

#### Scenario: Project intent is malformed
- **WHEN** a response begins a project envelope but its version, boundaries, declarations, or fences violate the V1 contract
- **THEN** generation fails as invalid generated code and MUST NOT preserve the response as successful ordinary conversation

#### Scenario: Provider response exceeds its configured limit
- **WHEN** accepting the next exact provider chunk would exceed the configured response-character limit
- **THEN** the system terminates generation without trimming or normalizing previously emitted chunks, publishes no new project, and emits no `done` event

### Requirement: Vue project source is bounded and path safe
The system SHALL accept only UTF-8 text files beneath configured model-owned `src/` and optional `public/` roots. Before writing any file, it MUST validate the complete declared set and reject null or blank content, unsupported roots or extensions, absolute or drive-qualified paths, backslashes, empty segments, `.` or `..` segments, hidden or publication-temporary segments, paths that normalize outside staging, paths exceeding configured length or depth, and case-insensitive duplicate paths. Model files MUST NOT collide with backend-owned scaffold files, `dist`, or any reserved build path.

The combined scaffold and model project MUST contain fewer than 30 files and MUST satisfy positive configurable bounds for model file count, per-file characters, aggregate model-source characters, and path length and depth. `src/main.js`, `src/App.vue`, and `src/router/index.js` MUST be present and non-empty. Validation MUST complete before staging writes begin, and materialization MUST create only contained directories and regular UTF-8 files without following links.

#### Scenario: Nested model source is valid
- **WHEN** the provider declares a bounded set of unique supported files such as `src/pages/HomePage.vue` and every required entry is present
- **THEN** the system materializes those files beneath the one project staging directory with their relative hierarchy preserved

#### Scenario: Required Vue entry is missing
- **WHEN** a project omits `src/main.js`, `src/App.vue`, or `src/router/index.js`
- **THEN** the system rejects the complete project before building or replacing stable files

#### Scenario: Declared path attempts traversal
- **WHEN** a file path is absolute, drive-qualified, contains a backslash or traversal segment, normalizes outside staging, or targets a reserved location
- **THEN** the system rejects the complete project without reading or writing the escaped or reserved path

#### Scenario: Paths collide by case
- **WHEN** a response declares paths such as `src/App.vue` and `src/app.vue` that are equivalent under case-insensitive comparison
- **THEN** the system rejects the complete project consistently on Windows and case-sensitive hosts

#### Scenario: Source exceeds a configured bound
- **WHEN** declared files reach 30 combined files or exceed a configured count, path, per-file, or aggregate-content limit
- **THEN** the system rejects the complete project before build or stable publication

#### Scenario: Materialized tree contains an unsupported entry
- **WHEN** validation of the staged source encounters a symbolic link, non-regular file, hidden temporary entry, or path outside staging
- **THEN** the system rejects and cleans the staging tree without following or publishing that entry

### Requirement: A trusted scaffold makes every accepted project runnable
The system SHALL combine accepted model-owned source with backend-owned canonical `index.html`, `package.json`, lockfile, and `vite.config.js` resources. The scaffold MUST target Vue 3, Vue Router 4, and Vite versions compatible with Node.js 18 or later; MUST define only the required `dev` and `build` package scripts; MUST contain no install lifecycle scripts; MUST configure `base: './'`, the `@` alias for `src`, and no fixed development port; and MUST use only the backend-approved Vue, Vue Router, Vite, and Vue plugin dependencies. The generated router MUST use hash history so the built application works below arbitrary preview and deployment subpaths.

The backend SHALL use its canonical scaffold even if provider output attempts to declare a protected scaffold path. Accepted source MUST build against the same pinned dependency set contained by the configured builder image. The project saved under the stable application directory SHALL contain the trusted scaffold, complete model-owned source, and validated `dist`, but SHALL NOT contain `node_modules`, registry credentials, provider data, or build-runtime secrets.

#### Scenario: Accepted source receives the scaffold
- **WHEN** all model-owned files pass validation
- **THEN** the staging project receives the canonical package, lockfile, Vite, and HTML resources needed for `npm install`, `npm run dev`, and `npm run build`

#### Scenario: Provider declares a protected scaffold file
- **WHEN** the project response declares `package.json`, `vite.config.js`, `index.html`, a lockfile, or any other backend-owned path
- **THEN** the system rejects the response rather than executing or silently trusting provider-controlled infrastructure

#### Scenario: Router does not use hash history
- **WHEN** the generated router entry does not satisfy the required hash-history contract
- **THEN** the system rejects the project before stable publication

#### Scenario: Project build leaves transient dependencies
- **WHEN** a builder creates `node_modules` or another forbidden transient path in the staging workspace
- **THEN** the system removes or rejects that transient content and never includes it in the stable generated directory

### Requirement: Vue builds run in an isolated and bounded environment
The system SHALL build staged Vue projects through an injected project-builder contract whose default implementation invokes a configured, pinned container image without a command shell. The runtime container MUST receive only the one staging workspace, MUST NOT receive backend environment variables, credentials, host sockets, or unrelated host paths, and MUST run without network access as a non-root user with a read-only container filesystem apart from bounded workspace and temporary storage. It MUST drop capabilities, enable no-new-privileges, and enforce configurable positive CPU, memory, process, wall-clock, diagnostic-output, and global concurrent-build limits.

The builder image SHALL contain the approved dependencies and a trusted entrypoint. Runtime generation MUST NOT perform dependency installation, execute project package scripts, or load the project-owned Vite configuration. Success requires a zero build exit and a subsequently validated `dist` tree. Timeout, interruption, reactive cancellation, lease loss, startup failure, abnormal exit, or unavailable runtime/image MUST terminate and remove the exact build process/container and return a controlled operation failure. Diagnostics MUST identify the application and failure category without source text, unrestricted builder output, host environment, or credentials.

#### Scenario: Isolated build succeeds
- **WHEN** valid source is compiled by the configured image within every resource limit and exits successfully
- **THEN** the builder returns only after producing a candidate `dist` tree for backend validation

#### Scenario: Container runtime or image is unavailable
- **WHEN** a Vue build cannot start because the configured runtime or image is unavailable
- **THEN** Vue generation fails with a controlled operation error while existing HTML and multi-file generation remain available

#### Scenario: Build times out or is cancelled
- **WHEN** the build exceeds its wall-clock limit or the reactive generation is cancelled or loses its lease
- **THEN** the system terminates and removes the exact build process/container, cleans staging, preserves the prior stable project, and emits no `done`

#### Scenario: Build attempts network or host access
- **WHEN** generated source or configuration attempts to use the network, host credentials, host sockets, or an unrelated host path during compilation
- **THEN** the isolated builder denies that access and no backend secret or unrelated file is exposed

#### Scenario: Project contains executable package configuration
- **WHEN** provider source attempts to introduce package scripts, dependencies, or a project Vite configuration
- **THEN** source validation rejects protected paths and the trusted builder never executes that provider-controlled configuration

#### Scenario: Build concurrency is exhausted
- **WHEN** the configured number of concurrent Vue builds is already active
- **THEN** an additional build fails or waits only according to its bounded configured policy and cannot create unbounded processes or queued work

#### Scenario: Builder emits excessive diagnostics
- **WHEN** a failed build writes more diagnostic output than the configured bound
- **THEN** the system drains the process safely, retains only bounded sanitized diagnostics, and does not log generated source or secrets

### Requirement: Only a complete validated Vue project is published
The system SHALL stage, materialize, build, and validate a Vue project before replacing `tmp/code_output/vue_project_{appId}`. The complete positive `Long appId` MUST be used without narrowing. A valid built output MUST be a bounded regular tree beneath staging with a regular `dist/index.html`; it MUST contain no symbolic links, hidden or publication-temporary paths, escaped paths, unsupported entries, or file-count and byte totals above configured limits.

After validation, publication SHALL use the existing rollback-capable sibling-directory replacement and SHALL remain unresolved until preview creation, successful AI-history persistence, initial `codeGenType` persistence when applicable, and all other required completion work succeed. Failed first generation MUST leave no stable partial directory. Failed regeneration or downstream finalization MUST restore the previous complete source and `dist`. Successful regeneration MUST replace the complete prior tree so removed source and built assets do not survive.

#### Scenario: First Vue project completes
- **WHEN** parsing, source validation, isolated build, `dist` validation, publication, preview, and required persistence all succeed for a new application
- **THEN** the stable `vue_project_{appId}` directory contains one complete source and `dist` snapshot and publication can commit

#### Scenario: Vue regeneration removes files
- **WHEN** a later valid full-source response omits source or built assets that existed in the prior version
- **THEN** successful replacement removes those files rather than merging them into the new stable project

#### Scenario: Parsing or build fails during regeneration
- **WHEN** the new response is invalid or its isolated build fails before publication
- **THEN** staging is cleaned and the prior stable source, `dist`, and preview remain unchanged

#### Scenario: Built output is incomplete or unsafe
- **WHEN** the builder exits successfully but `dist/index.html` is absent or the built tree violates containment, entry, count, or byte limits
- **THEN** the system rejects and cleans the candidate without replacing the prior stable project

#### Scenario: Required finalization fails after replacement preparation
- **WHEN** the new project has been published with a recoverable backup but preview or required database/history persistence fails
- **THEN** publication rolls back to the prior complete project and no `done` event is emitted

#### Scenario: Application id exceeds integer range
- **WHEN** Vue generation uses a valid application id greater than `Integer.MAX_VALUE`
- **THEN** the full value identifies the unique staging and stable directory without overflow, truncation, or collision

### Requirement: Vue generation configuration is explicit and safe
The system SHALL externalize Vue project protocol and response limits, source and path limits, source-context limit, built-output limits, builder timeout and concurrency, runtime executable and image, resource limits, and diagnostic bound through tracked safe defaults plus environment or ignored local overrides. All numeric and duration limits MUST be positive, the combined project limit MUST remain below 30 files, dependent limits MUST be internally coherent, and tracked configuration MUST NOT contain registry or runtime credentials.

#### Scenario: Configuration is valid
- **WHEN** all required project and builder settings use coherent positive values and a non-blank image identifier
- **THEN** application startup exposes the Vue generation components without contacting the container runtime or running a build

#### Scenario: Configuration is invalid
- **WHEN** a bound is non-positive, combined project files are not fewer than 30, dependent sizes conflict, or the runtime/image setting is blank
- **THEN** application startup fails with a configuration error instead of applying an unbounded or ambiguous default

#### Scenario: Runtime availability changes after startup
- **WHEN** startup configuration is valid but the external runtime becomes unavailable before a Vue request
- **THEN** that Vue request fails through the controlled builder-error path without changing stable generated files
