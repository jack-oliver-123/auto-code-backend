# App Deployment Specification

## Purpose

Define secure, recoverable publication of generated applications to a configurable static hosting location, including stable deployment identities, metadata consistency, concurrency control, and public URL behavior.

## Requirements

### Requirement: Owners can explicitly deploy generated applications
The system SHALL expose `POST /app/deploy` for authenticated users and accept `appId` as a request parameter. A valid request MUST contain a positive `appId` identifying an active application owned by the current user, and the application MUST have a supported persisted code-generation type and all regular files required by that type: `index.html` for HTML and `index.html`, `style.css`, and `script.js` for multi-file generation. Deployment SHALL remain a separate operation invoked after code generation completes.

#### Scenario: Owner deploys a generated application
- **WHEN** the owner submits the positive id of an active application whose latest generated source is complete
- **THEN** the system publishes that source and returns the application's deployment metadata

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
- **WHEN** the owned application has no supported persisted code-generation type, no generated source directory, or any required regular file is absent
- **THEN** the system rejects deployment with an operation-error response and does not allocate a deployment key

### Requirement: Deployment keys are stable, server-owned, and unique
On the first publish attempt that reaches key allocation, the system SHALL generate a key matching `[A-Za-z0-9]{6}` from a server-controlled random source. For an application without a key, allocation MUST occur only after the complete staging copy succeeds. The system MUST NOT accept a deployment key from the client, MUST assign at most one key to an application, and MUST reuse the stored key for every retry and redeployment. Every stored key MUST match the same six-character pattern before filesystem use. Key uniqueness SHALL include active and logically deleted application rows and SHALL follow the database unique-index comparison semantics.

#### Scenario: First deployment allocates a key
- **WHEN** an owned generated application without a deployment key reaches key allocation
- **THEN** the system conditionally stores one six-character alphanumeric key before publishing to its final directory

#### Scenario: Existing key is reused
- **WHEN** an application with a stored deployment key is deployed again or retries a failed first deployment
- **THEN** the system uses the exact stored key and does not generate or persist a replacement

#### Scenario: Generated key conflicts with an existing row
- **WHEN** the database unique constraint rejects a candidate because an active or logically deleted row already owns an equivalent key
- **THEN** the system retries with another candidate up to a bounded limit without publishing to the conflicting directory

#### Scenario: Generated key conflicts with an orphan deployment directory
- **WHEN** a first-key candidate names an existing final deployment directory that is not the current application's stored deployment
- **THEN** the system preserves that directory unchanged and retries allocation with another candidate without treating the orphan as a prior version of the current application

#### Scenario: Another executor assigns the same application's key first
- **WHEN** conditional key assignment affects no row because the application already received a key
- **THEN** the system reloads the active owned application and reuses its stored key rather than overwriting it

#### Scenario: Key allocation retries are exhausted
- **WHEN** every candidate in the bounded allocation attempt is rejected
- **THEN** the system returns an operation-error response, publishes no final deployment, and does not report the application as deployed

#### Scenario: Stored key is malformed
- **WHEN** an application contains a stored deployment key that does not match `[A-Za-z0-9]{6}`
- **THEN** the system returns an operation-error response without rotating the key or resolving, reading, or writing a path outside the deployment root

#### Scenario: Key persistence fails for a non-collision reason
- **WHEN** first-key assignment fails with a database error other than a unique-key collision
- **THEN** the system returns an operation-error response, removes its staging directory, publishes no final directory, and leaves any prior deployment unchanged

### Requirement: Deployment publishes an exact recoverable directory snapshot
The system SHALL copy the stable generated source from `tmp/code_output/{codeGenType}_{appId}` into a staging directory under the configured deployment root before publishing it as `{deployRoot}/{deployKey}`. Every normalized source, staging, backup, tombstone, and target path MUST remain beneath its configured root, and copy MUST NOT follow a symbolic link outside the generated source. Publication MUST replace the complete target directory rather than merge files, MUST preserve the source directory, and MUST restore the previous deployed directory if copying or replacement fails.

#### Scenario: First deployment publishes a complete snapshot
- **WHEN** staging copy and final publication both succeed for an application that has never been deployed
- **THEN** `{deployRoot}/{deployKey}` contains the complete source snapshot with `index.html` and no partial staging content is publicly visible

#### Scenario: Redeployment replaces the prior snapshot
- **WHEN** an already deployed application is deployed after a later successful generation
- **THEN** the system replaces the entire directory at the same deployment key so files absent from the new source do not remain

#### Scenario: Staging copy fails
- **WHEN** any source file cannot be copied into the staging directory
- **THEN** the system returns an operation-error response, removes staging content, allocates no first key, and leaves the previous deployed directory and deployment timestamp unchanged

#### Scenario: Final directory replacement fails
- **WHEN** the staged snapshot cannot replace the final deployment directory
- **THEN** the system restores the previous deployed directory when one existed, removes recoverable temporary content, and returns an operation-error response

#### Scenario: Source contains a symbolic link
- **WHEN** staging encounters any symbolic link in the generated source snapshot
- **THEN** the system rejects deployment, cleans staging content, and reads or copies no content through that link

#### Scenario: A resolved path escapes its configured root
- **WHEN** any normalized deployment path would not remain beneath the configured output or deployment root
- **THEN** the system rejects deployment before reading or writing the escaped path

### Requirement: Deployment metadata identifies only completed publication
After final directory publication succeeds, the system SHALL update `deployedTime` for the active owned application and SHALL return `deployKey`, `deployUrl`, and `deployedTime`. `deployUrl` MUST be built from the configured static host and the exact key, with a trailing slash. The system MUST treat non-null `deployedTime`, rather than key presence alone, as the persisted indication of a completed deployment.

#### Scenario: Deployment completes successfully
- **WHEN** final directory publication and the owner-constrained metadata update both succeed
- **THEN** the system refreshes `deployedTime` and returns metadata whose URL ends in `/{deployKey}/`

#### Scenario: Metadata update fails after first publication
- **WHEN** the final directory was published but the `deployedTime` update definitively affects no row or is confirmed not to have committed
- **THEN** the system removes the first final directory, retains any already reserved key for retry, leaves `deployedTime` unset, and returns an operation-error response

#### Scenario: Metadata update fails during redeployment
- **WHEN** a replacement directory was published but the `deployedTime` update definitively affects no row or is confirmed not to have committed
- **THEN** the system restores the prior deployed directory, leaves the previous timestamp unchanged, and returns an operation-error response

#### Scenario: Metadata update reports an uncertain database outcome
- **WHEN** the metadata call fails and the system cannot reread the App to determine whether the intended `deployedTime` committed
- **THEN** the system returns an error without a successful deployment response, retains the stable key and recoverable filesystem snapshots, and allows a later deployment retry to reconcile the same key

### Requirement: Application processing operations are mutually exclusive
Within one application process, generation, deployment, owner deletion, and administrator deletion for the same `appId` SHALL share one processing guard. The guard MUST be released after synchronous success or error and after every reactive generation termination signal, including cancellation. Operations for different application ids SHALL NOT be rejected solely because another application is processing.

#### Scenario: Deployment is requested while the application is generating
- **WHEN** generation currently holds the same application's processing guard
- **THEN** deployment is rejected with an operation-error response before reading or publishing files

#### Scenario: Generation or deletion is requested while deployment is running
- **WHEN** deployment currently holds the same application's processing guard
- **THEN** the competing generation or deletion is rejected with an operation-error response

#### Scenario: A second deployment is requested for the same application
- **WHEN** one deployment currently holds the application's processing guard
- **THEN** the second deployment is rejected with an operation-error response before it stages files

#### Scenario: Another application is processing
- **WHEN** one application holds its processing guard and a valid operation begins for a different application id
- **THEN** the second application's operation is allowed to proceed independently

#### Scenario: Generation is cancelled before deployment
- **WHEN** a generation subscription is cancelled and then the owner requests deployment for the same application
- **THEN** cancellation releases the processing guard and the later deployment is evaluated normally against the last complete generated source

#### Scenario: Deployment terminates
- **WHEN** a deployment succeeds or ends with an error
- **THEN** the system releases the processing guard so a later operation for that application can proceed

### Requirement: Deployment locations and public URLs are configurable
The deployment root and static host SHALL be supplied by application configuration, with local defaults of `tmp/code_deploy` and `http://127.0.0.1:9332`. The configured deployment root and generated-code output root MUST be distinct and MUST NOT contain one another. The backend SHALL build public URLs from the configured static host but MUST NOT register the deployment root as a same-origin Spring MVC static-resource mapping, including any `/api/static` mapping. Production rollout MUST provide persistent storage and a static origin whose cookie scope does not receive backend session cookies; that static host SHALL serve each key directory using `index.html` as its entry point.

#### Scenario: Configured deployment location is used
- **WHEN** deployment succeeds with a configured root and static host
- **THEN** files are published beneath that root and the returned URL uses that host without exposing an internal filesystem path

#### Scenario: Configured roots overlap
- **WHEN** the deployment root equals, contains, or is contained by the generated-code output root
- **THEN** the system rejects the invalid deployment configuration before copying or publishing files

#### Scenario: Static host contains an optional trailing slash
- **WHEN** deployment URL construction receives an otherwise equivalent configured static host with or without trailing slashes
- **THEN** it returns a URL containing exactly one separator before the key and exactly one trailing slash after the key

#### Scenario: Deployed multi-file application loads relative assets
- **WHEN** a caller opens the returned deployment URL for a multi-file application
- **THEN** the trailing-slash URL allows `index.html` to resolve sibling CSS and JavaScript assets beneath the same key directory

#### Scenario: Production deployment is accepted for rollout
- **WHEN** deployment configuration is promoted to production
- **THEN** its deployment root is persistent across backend replacement and its static origin, directory-index behavior, and cookie scope are verified independently from the backend API origin

### Requirement: Local deployment previews use an isolated restrictive listener
The system SHALL provide an independently configurable JDK HTTP static listener using `app.deployment.local-server.enabled`, `app.deployment.local-server.bind-address`, and `app.deployment.local-server.port`, with development defaults of `true`, `127.0.0.1`, and `9332`. The listener MUST start only after the primary application web server is initialized, MUST ignore management web-server events, and MUST NOT bind a port for a mock web application context. When enabled, the configured deployment host MUST be a pathless `http` origin whose effective port equals the local listener port. A public hostname used with a specific bind address MUST resolve to that address; a wildcard bind MAY accept any resolvable public hostname. Invalid or mismatched configuration MUST fail listener startup. Production MAY disable the listener and configure an external deployment host.

The listener SHALL serve only a complete `{deployKey}` directory beneath the deployment root. It MUST accept only `GET` and `HEAD`, redirect `/{deployKey}` to `/{deployKey}/`, serve `index.html` only for the key root, provide deterministic MIME types for HTML, CSS, JavaScript, and common assets, disable directory listing, and return `404` for invalid keys, missing resources, directories, traversal attempts, hidden or temporary paths, and symbolic links. Every normalized and decoded path MUST remain beneath the selected six-character key directory, and neither the key directory nor any selected path component MAY be a symbolic link.

Every listener response SHALL include `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Content-Security-Policy: sandbox allow-scripts`, and `Permissions-Policy: camera=(), microphone=(), geolocation=()`. The CSP MUST NOT grant `allow-same-origin`, `allow-forms`, or top-navigation privileges. The listener MUST NOT emit credentialed CORS headers.

#### Scenario: Local owner opens a deployed application
- **WHEN** local preview serving is enabled and a caller requests `/{deployKey}/` with `GET`
- **THEN** the listener serves that key directory's regular `index.html` file with the restrictive response headers and without routing through the `/api` servlet context

#### Scenario: Trailing slash is omitted
- **WHEN** a caller requests an existing safe `/{deployKey}` with `GET` or `HEAD`
- **THEN** the listener redirects to `/{deployKey}/` so relative application assets resolve beneath the same key directory

#### Scenario: Caller requests a static asset
- **WHEN** a caller requests a contained regular CSS or JavaScript file beneath an existing safe key directory
- **THEN** the listener returns the exact file with its deterministic CSS or JavaScript MIME type and no-store caching

#### Scenario: Caller attempts an unsupported method
- **WHEN** a caller uses a method other than `GET` or `HEAD`
- **THEN** the listener returns `405` with `Allow: GET, HEAD` and does not read or mutate a deployed file

#### Scenario: Caller attempts to escape or enumerate a deployment
- **WHEN** a request contains an invalid key, traversal segment, encoded path separator, hidden path, publication-temporary directory, symbolic link, missing resource, or directory-listing target
- **THEN** the listener returns `404` without reading content outside the selected key directory or revealing a filesystem listing

#### Scenario: Public host and local listener ports differ
- **WHEN** local preview serving is enabled and the effective `app.deployment.host` port differs from `app.deployment.local-server.port`
- **THEN** listener startup fails instead of allowing deployment responses to advertise an unreachable URL

#### Scenario: Public host does not reach a specific bind address
- **WHEN** local preview serving uses a non-wildcard bind address and the configured public hostname does not resolve to that address
- **THEN** listener startup fails instead of advertising a host that cannot reach the listener

#### Scenario: Wildcard bind uses a public hostname
- **WHEN** local preview serving uses a wildcard bind and the configured public hostname is resolvable with the matching listener port
- **THEN** the listener may start and deployment responses use that public hostname

#### Scenario: Production uses external static hosting
- **WHEN** `app.deployment.local-server.enabled` is false and an external deployment host is configured
- **THEN** the JDK listener does not bind and deployment URLs use the configured external host
