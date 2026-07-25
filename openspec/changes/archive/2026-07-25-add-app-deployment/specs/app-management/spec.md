## MODIFIED Requirements

### Requirement: Users can logically delete only their own applications
The system SHALL expose `POST /app/delete` for authenticated users. A successful deletion SHALL make any deployed public directory for the owner's application unavailable and SHALL mark the application as logically deleted so it is unavailable to normal detail and list queries. If moving the public directory out of service or logical deletion fails, the system MUST preserve or restore the prior active application and deployed-site availability.

#### Scenario: Owner deletes an undeployed application
- **WHEN** the owner submits the valid id of an active application with no public deployment
- **THEN** the system logically deletes the application and returns success

#### Scenario: Owner deletes a deployed application
- **WHEN** the owner submits the valid id of an active application with a public deployment
- **THEN** the system makes the deployment URL unavailable, logically deletes the application, and returns success

#### Scenario: Owner deletes an application whose deployment directory is already missing
- **WHEN** the owner submits an active application with deployment metadata but no final public directory
- **THEN** the system treats the site as already undeployed, logically deletes the application, and returns success

#### Scenario: Public undeployment fails
- **WHEN** the deployment directory cannot be moved out of public service before logical deletion
- **THEN** the system returns an operation-error response and retains both the active application and its prior public deployment

#### Scenario: Logical deletion fails after undeployment preparation
- **WHEN** the deployment directory was moved out of public service but the database logical deletion fails
- **THEN** the system restores the deployment directory, retains the active application, and returns an operation-error response

#### Scenario: User attempts to delete another user's application
- **WHEN** an authenticated user submits the id of an application owned by another user
- **THEN** the system rejects the request with the no-authority response and retains the application and any deployment

#### Scenario: Application to delete does not exist
- **WHEN** the submitted id does not identify an active application
- **THEN** the system returns the not-found response

### Requirement: Administrators can delete any active application
The system SHALL expose `POST /app/admin/delete` only to administrators. A successful request SHALL make any deployed public directory unavailable and SHALL logically delete the identified active application regardless of its owner. If moving the public directory out of service or logical deletion fails, the system MUST preserve or restore the prior active application and deployed-site availability.

#### Scenario: Administrator deletes an undeployed application
- **WHEN** an administrator submits the valid id of an active application with no public deployment
- **THEN** the system logically deletes the application and returns success

#### Scenario: Administrator deletes a deployed application
- **WHEN** an administrator submits the valid id of an active application with a public deployment
- **THEN** the system makes the deployment URL unavailable, logically deletes the application, and returns success

#### Scenario: Administrator deletes an application whose deployment directory is already missing
- **WHEN** an administrator submits an active application with deployment metadata but no final public directory
- **THEN** the system treats the site as already undeployed, logically deletes the application, and returns success

#### Scenario: Administrator deletion cannot undeploy the application
- **WHEN** the application's deployment directory cannot be moved out of public service before logical deletion
- **THEN** the system returns an operation-error response and retains both the active application and its prior public deployment

#### Scenario: Administrator logical deletion fails after undeployment preparation
- **WHEN** the deployment directory was moved out of public service but the database logical deletion fails
- **THEN** the system restores the deployment directory, retains the active application, and returns an operation-error response

#### Scenario: Non-administrator invokes administrator deletion
- **WHEN** a non-administrator invokes `POST /app/admin/delete`
- **THEN** the system rejects the request with the no-authority response and leaves the application and any deployment unchanged

#### Scenario: Administrator deletes a missing application
- **WHEN** an administrator submits an id that does not identify an active application
- **THEN** the system returns the not-found response
