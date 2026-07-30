## MODIFIED Requirements

### Requirement: Owners can explicitly deploy generated applications
The system SHALL expose `POST /app/deploy` for authenticated users and accept `appId` as a request parameter. A valid request MUST contain a positive `appId` identifying an active application owned by the current user, and the application MUST have a supported persisted code-generation type and a complete safe static web root for that type: the stable generated root containing `index.html` for HTML; the stable generated root containing `index.html`, `style.css`, and `script.js` for multi-file generation; or the `dist` child of the stable `vue_project` root containing `index.html` and a bounded regular asset tree. Deployment SHALL remain a separate operation invoked after code generation completes.

#### Scenario: Owner deploys a generated application
- **WHEN** the owner submits the positive id of an active application whose latest type-specific static web root is complete
- **THEN** the system publishes that static web root and returns the application's deployment metadata

#### Scenario: Owner deploys a Vue project
- **WHEN** the owner deploys an active `vue_project` application with a complete validated stable `dist`
- **THEN** the system publishes only the contents of `dist` and does not expose project source, scaffold, lockfile, or builder content

#### Scenario: Generation completion does not deploy automatically
- **WHEN** generation emits its named `done` event and no separate deployment request is submitted
- **THEN** the system does not create or replace a final deployment directory and does not assign `deployKey` or `deployedTime`

#### Scenario: Anonymous caller attempts deployment
- **WHEN** an unauthenticated caller invokes `POST /app/deploy`
- **THEN** the system returns the not-logged-in response without reading deployment sources, allocating a key, or changing deployment metadata

#### Scenario: Initial-password user attempts deployment
- **WHEN** an authenticated user who must change an initial password invokes `POST /app/deploy`
- **THEN** the system returns the password-change-required response without reading deployment sources, allocating a key, or changing deployment metadata

#### Scenario: Caller supplies an invalid application id
- **WHEN** an authenticated caller omits `appId` or supplies a non-positive value
- **THEN** the system rejects the request with the parameter-error response before reading or publishing files

#### Scenario: Application does not exist
- **WHEN** the submitted id does not identify an active application
- **THEN** the system returns the not-found response and publishes no files

#### Scenario: User attempts to deploy another user's application
- **WHEN** an authenticated user submits an application owned by another user, including when that user is an administrator
- **THEN** the system rejects the request with the no-authority response and changes neither deployment files nor metadata

#### Scenario: Application has not completed generation
- **WHEN** the owned application has no supported persisted code-generation type, no stable generated directory, or any type-specific required static root or regular file is absent or unsafe
- **THEN** the system rejects deployment with an operation-error response and does not allocate a deployment key

#### Scenario: Vue project has source but no valid build
- **WHEN** a `vue_project` directory exists but `dist` is missing, incomplete, unsafe, or exceeds its configured output bounds
- **THEN** the system rejects deployment without copying source files, allocating a first key, or changing the prior deployment

### Requirement: Deployment publishes an exact recoverable directory snapshot
The system SHALL resolve one static web root from the stable generated directory `tmp/code_output/{codeGenType}_{appId}`: the stable root itself for HTML and multi-file generation, or its validated `dist` child for Vue project generation. It SHALL copy only that static web root into a staging directory under the configured deployment root before publishing it as `{deployRoot}/{deployKey}`. Every normalized generated root, static web root, staging, backup, tombstone, and target path MUST remain beneath its configured root, and copy MUST NOT follow a symbolic link inside or outside the selected static web root. Publication MUST replace the complete target directory rather than merge files, MUST preserve the complete stable generated source directory, and MUST restore the previous deployed directory if copying or replacement fails.

#### Scenario: First deployment publishes a complete snapshot
- **WHEN** staging copy and final publication both succeed for an application that has never been deployed
- **THEN** `{deployRoot}/{deployKey}` contains the complete selected static web-root snapshot with `index.html` and no partial staging content is publicly visible

#### Scenario: First Vue deployment excludes source
- **WHEN** a complete Vue project is deployed for the first time
- **THEN** the final key directory mirrors `vue_project_{appId}/dist` and contains no `src`, `package.json`, lockfile, Vite configuration, or sibling project content

#### Scenario: Redeployment replaces the prior snapshot
- **WHEN** an already deployed application is deployed after a later successful generation
- **THEN** the system replaces the entire directory at the same deployment key so files absent from the new type-specific static root do not remain

#### Scenario: Staging copy fails
- **WHEN** any selected static-root file cannot be copied into the staging directory
- **THEN** the system returns an operation-error response, removes staging content, allocates no first key, and leaves the previous deployed directory and deployment timestamp unchanged

#### Scenario: Final directory replacement fails
- **WHEN** the staged snapshot cannot replace the final deployment directory
- **THEN** the system restores the previous deployed directory when one existed, removes recoverable temporary content, and returns an operation-error response

#### Scenario: Selected static root contains a symbolic link
- **WHEN** staging encounters any symbolic link in the selected HTML, multi-file, or Vue `dist` snapshot
- **THEN** the system rejects deployment, cleans staging content, and reads or copies no content through that link

#### Scenario: A resolved path escapes its configured root
- **WHEN** any normalized generated, static-root, deployment, staging, backup, tombstone, or target path would not remain beneath its required configured parent
- **THEN** the system rejects deployment before reading or writing the escaped path

#### Scenario: Vue source changes after build
- **WHEN** files outside a validated Vue `dist` differ from the project version used to produce it
- **THEN** deployment still selects only the atomically published stable `dist` belonging to that completed generation and never rebuilds during deployment

## ADDED Requirements

### Requirement: Generated previews use immutable type-specific static snapshots
The system SHALL create or refresh an owner-authorized application preview from the same type-specific static web root used by deployment. It MUST validate and copy a complete regular snapshot before publishing a new immutable preview identity. HTML snapshots SHALL contain their complete `index.html`; multi-file snapshots SHALL contain `index.html`, `style.css`, and `script.js` and MAY use the existing safe preview bundling step; Vue snapshots SHALL copy the validated `dist` contents unchanged and MUST NOT include or serve project source, scaffold, lockfile, or build-runtime content.

The preview source and every copied entry MUST remain beneath their required roots and MUST reject links, hidden or temporary entries, unsupported non-regular entries, missing type-specific files, and configured Vue output limit violations. A failed generation, build, snapshot copy, preview publication, or downstream finalization MUST NOT replace or revoke the prior usable immutable preview solely because a new candidate existed. The owner-authenticated legacy redirect SHALL recognize `vue_project_{appId}` with a positive full `Long` id and SHALL retain all existing owner checks and token exchange behavior.

Vue's canonical relative Vite base and hash router SHALL allow its preview root, assets, and client routes to operate beneath the generated token-free snapshot subpath. The isolated preview's existing restrictive CSP and no-network policy remain in force; core generated behavior MUST NOT depend on third-party network access.

#### Scenario: Owner previews a Vue project
- **WHEN** generation or `POST /app/preview` creates a preview for an owned Vue application with valid stable `dist`
- **THEN** the immutable snapshot contains only the built static web root and returns a bootstrap URL whose token-free content path loads `dist/index.html`

#### Scenario: Vue preview loads nested assets and routes
- **WHEN** the browser opens the token-free Vue preview root and navigates through hash routes
- **THEN** relative built assets resolve below the same immutable snapshot path without a server-side history fallback

#### Scenario: Vue source is requested through preview
- **WHEN** a caller guesses a source, package, lockfile, Vite, staging, or sibling path that is not present beneath the immutable built snapshot
- **THEN** the preview listener returns `404` without reading the stable project source

#### Scenario: New Vue preview preparation fails
- **WHEN** a candidate `dist` is missing, unsafe, over limit, or cannot be copied and published as an immutable snapshot
- **THEN** generation emits no `done`, the candidate is rejected, and the previous complete project and previously issued preview remain usable

#### Scenario: Legacy Vue preview URL is used by its owner
- **WHEN** an authenticated owner requests `/api/static/vue_project_{appId}/` with a positive full `Long` application id
- **THEN** the API creates a fresh isolated preview grant and returns the existing temporary redirect without serving generated bytes from the API origin

#### Scenario: Legacy Vue preview URL is malformed or unauthorized
- **WHEN** the directory name contains an invalid type or id, or the caller does not own the identified application
- **THEN** the system returns the applicable parameter, authentication, not-found, or no-authority response without revealing or copying project files

#### Scenario: Vue application references a third-party asset in preview
- **WHEN** built Vue content attempts network access prohibited by the existing preview CSP
- **THEN** the listener keeps the restrictive security policy and the application's local navigation and core interactions remain usable without that resource
