## ADDED Requirements

### Requirement: Normal provider completion is part of Vue project completeness
A Vue project candidate SHALL be eligible for parsing and publication only after the provider invokes normal stream completion and the exact accumulated response satisfies the complete versioned project envelope. Upstream timeout, connection closure, provider error, cancellation, lease loss, response-limit termination, or application-level deadline MUST be treated as abnormal completion even when the received prefix contains plausible files or an apparent closing marker.

Abnormal completion MUST NOT invoke project parsing as a successful candidate, build or publish a first stable project, replace a previous stable project, persist the partial response as a successful AI reply, persist a successful initial generation type, transition the attempt to `SUCCEEDED`, or emit `done`. Any staging or unresolved publication created before a downstream failure MUST be cleaned or rolled back. The terminal generation status and SSE error contract SHALL follow the application-generation lifecycle requirements.

#### Scenario: Provider closes before the project ending marker
- **WHEN** a first Vue stream closes abnormally after returning only a prefix of the project envelope
- **THEN** the candidate is rejected, no stable project or successful metadata remains, the attempt becomes `FAILED`, and no `done` is emitted

#### Scenario: Provider closes after text resembling an ending marker
- **WHEN** transport termination is abnormal even though the received prefix contains text equal to the V1 closing marker
- **THEN** normal provider completion is still absent and the system does not parse, build, publish, or mark the attempt successful

#### Scenario: Regeneration stream is interrupted
- **WHEN** abnormal provider completion occurs while a previous complete Vue project exists
- **THEN** the previous source, `dist`, preview, and generation type remain unchanged while the latest attempt becomes `FAILED`

#### Scenario: Required persistence fails after candidate preparation
- **WHEN** a complete Vue candidate was built and prepared but successful history, initial type, or lifecycle persistence fails
- **THEN** the database success transaction rolls back, code and preview publications restore the prior state, and no `done` is emitted

#### Scenario: Successful final cleanup cannot remove an obsolete backup
- **WHEN** all required work and the `SUCCEEDED` transaction completed but cleanup of an obsolete code or preview backup fails
- **THEN** the complete new project remains successful, cleanup failure is logged without content, and exactly one `done` is emitted
