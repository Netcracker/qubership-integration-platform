# Filters

## Description

---

### Overview

Almost every commonly used application's window allows to limit the output data by specifying filtering rules via ![filter icon](img/filter.svg) button. Please view the list of pages, where filtering is available:

- Chains
- Chain/Snapshots
- Chain/Sessions
- Services
- Admin Tools/Variables
- Admin Tools/Audit
- Admin Tools/Sessions
- Admin Tools/Access Control
- Admin Tools/Diagnostic Page
- Admin Tools/Import Instructions
- Mapper/Table View
- Configuration Graph → Right Panel/Elements view
- Configuration Graph →  Right Panel/Property search

Next sections of this article describe filtering capabilities for each particular page.

> ℹ️
> Every new filter rule is handled via **AND** operator.

### Chains

| Column      | Condition                                                                                       | Available Value                                                                              |
|:------------|:------------------------------------------------------------------------------------------------|:---------------------------------------------------------------------------------------------|
| Name        | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                                | Any text value.                                                                              |
| Status      | - In<br>- Not in                                                                                | Predefined list: <br/>- Draft<br>- Processing<br>- Failed<br>- Deployed                      |
| Labels      | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Empty<br>- Not empty                  | Any text value.                                                                              |
| ID          | - Is<br>- Is not<br>- Contains                                                                  | Any text value.                                                                              |
| Description | - Contains<br>- Does not contain<br>- Empty<br>- Not empty                                      | Any text value.                                                                              |
| Element     | - In<br>- Not in                                                                                | List of all elements.                                                                        |
| Domains     | - In<br>- Not in                                                                                | Any text value.                                                                              |
| Logging     | - In<br>- Not in                                                                                | Predefined list: <br/>- Off<br>- Error<br>- Info<br>- Debug                                  |
| Path        | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Empty | Any text value.                                                                              |
| Method      | - In<br>- Not in                                                                                | Predefined list: <br/>- GET<br>- POST<br>- PUT<br>- PATCH<br>- DELETE<br>- HEAD<br>- OPTIONS |
| Topic       | - Is<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Empty             | Any text value.                                                                              |
| Exchange    | - Is<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Empty             | Any text value.                                                                              |
| Queue       | - Is<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Empty             | Any text value.                                                                              |
| Service     | Is                                                                                              | Any text value.                                                                              |
| Classifier  | - Is<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with                        | Any text value.                                                                              |

### Sessions (Chain)

| Column      | Condition                                | Available Value                                                                                                     |
|:------------|:-----------------------------------------|:--------------------------------------------------------------------------------------------------------------------|
| Status      | - In<br>- Not in                         | Predefined list: <br/>- Completed Normally<br>- Completed With Warnings<br>- Completed With Errors<br>- In Progress |
| Start Time  | - Is within<br>- Is after<br>- Is before | Calendar.                                                                                                           |
| Finish Time | - Is within<br>- Is after<br>- Is before | Calendar.                                                                                                           |
| Engine      | - In<br>- Not in                         | List of tracked engines.                                                                                            |

### Snapshots (Chain)

| Column       | Condition                                                                      | Available Value   |
|:-------------|:-------------------------------------------------------------------------------|:------------------|
| Version Name | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with               | Any text value.   |
| Labels       | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Empty<br>- Not empty | Any text value.   |
| Created When | - Is within<br>- Is after<br>- Is before                                       | Calendar.         |

### Services

| Column                                                              | Condition                                                                      | Available Value                                                                         |
|:--------------------------------------------------------------------|:-------------------------------------------------------------------------------|:----------------------------------------------------------------------------------------|
| Name                                                                | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with               | Any text value.                                                                         |
| ID                                                                  | - Is<br>- Is not<br>- Contains                                                 | Any text value.                                                                         |
| Created                                                             | - Is within<br>- Is after<br>- Is before                                       | Calendar.                                                                               |
| Specification Group <br/> *(Not available for Database services)*   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with               | Any text value.                                                                         |
| Specification Version <br/> *(Not available for Database services)* | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with               | Any text value.                                                                         |
| URL <br/> *(Not available for Database services)*                   | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with          | Any text value.                                                                         |
| Protocol <br/> *(Not available for Database services)*              | - In<br>- Not in                                                               | Predefined list: <br/>- http<br>- amqp<br>- kafka<br>- GraphQL<br>- metamodel<br>- gRPC |
| Labels                                                              | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Empty<br>- Not empty | Any text value.                                                                         |

### Variables

| Column   | Condition                                                                                                      | Available Value   |
|:---------|:---------------------------------------------------------------------------------------------------------------|:------------------|
| Key      | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with                           | Any text value.   |
| Value    | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Empty<br>- Not empty | Any text value.   |

### Audit

| Column      | Condition                                                        | Available Value                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
|:------------|:-----------------------------------------------------------------|:-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Operation   | - In<br>- Not in                                                 | Predefined list: <br/>- Create<br>- Update<br>- Create Or Update<br>- Delete<br>- Copy<br>- Move<br>- Revert<br>- Group<br>- Ungroup<br>- Export<br>- Import<br>- Scale<br>- Execute<br>- Activate<br>- Deprecate                                                                                                                                                                                                                                                        |
| Action Time | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| Entity Type | - In<br>- Not in                                                 | Predefined list: <br/>- Chain<br>- Chains<br>- Snapshot<br>- Snapshot Cleanup<br>- Deployment<br>- Element<br>- Masked Field<br>- Chain Runtime Properties<br>- Database System<br>- Database Script<br>- Service Discovery<br>- External Service<br>- Inner Cloud Service<br>- Implemented Service<br>- Environment<br>- Specification<br>- Specification Group<br>- Services<br>- Maas Kafka<br>- Maas Rabbitmq<br>- Secret<br>- Secured Variable<br>- Common Variable |
| Initiator   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| Request Id  | - Is<br>- Is not<br>- Contains                                   | Any text value.                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| Entity Id   | - Is<br>- Is not<br>- Contains                                   | Any text value.                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| Entity Name | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| Parent Id   | - Is<br>- Is not<br>- Contains                                   | Any text value.                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| Parent Name | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                                                                                                                                                                                                                                                                                                                                                                                                          |

### Sessions (Admin Tools)

| Column      | Condition                                                        | Available Value                                                                                                     |
|:------------|:-----------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------|
| Chain       | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                                                     |
| Status      | - In<br>- Not in                                                 | Predefined list: <br/>- Completed Normally<br>- Completed With Warnings<br>- Completed With Errors<br>- In Progress |
| Start Time  | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                                                           |
| Finish Time | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                                                           |
| Engine      | - In<br>- Not in                                                 | List of tracked engines.                                                                                            |

### Access Control

| Column              | Condition                                                                                       | Available Value                                                         |
|:--------------------|:------------------------------------------------------------------------------------------------|:------------------------------------------------------------------------|
| Endpoint            | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Empty | Any text value.                                                         |
| Type                | - In<br>- Not in                                                                                | Predefined list: <br/>- External<br>- Private<br>- Internal             |
| Access Control Type | - Is<br>- Is not                                                                                | Predefined List: <br/>- RBAC<br>- ABAC<br>- NONE                        |
| Roles               | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Empty<br>- Not empty                  | Any text value.                                                         |
| Chain               | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                                | Any text value.                                                         |
| Chain Status        | - In<br>- Not in                                                                                | Predefined list: <br/>- Draft<br>- Deployed<br>- Failed<br>- Processing |

### Diagnostic Page

| Column              | Condition                                                                                       | Available Value                                                                                                                 |
|:--------------------|:------------------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------|
| Chain Name          | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Empty | Any text value.                                                                                                                 |
| Chain Id            | - Is<br>- Is not<br>- Contains                                                                  | Any text value.                                                                                                                 |
| Chain Element Name  | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Empty | Any text value.                                                                                                                 |
| Chain Element Id    | - Is<br>- Is not<br>- Contains                                                                  | Any text value.                                                                                                                 |
| Chain Element Type  | - In<br>- Not in                                                                                | List of next elements: <br/>- HTTP Trigger<br>- SDS Trigger<br>- HTTP Sender<br>- Service Call<br>- SCS Sender<br>- Mail Sender |
| Validation Severity | - In<br>- Not in                                                                                | List of all possible values: <br/>- Error<br>- Warning                                                                          |

### Import Instructions

| Column        | Condition                                                                      | Available Value                                    |
|:--------------|:-------------------------------------------------------------------------------|:---------------------------------------------------|
| Id            | - Is<br>- Is not<br>- Contains                                                 | Any text value.                                    |
| Action        | - In<br>- Not in                                                               | List of next elements: <br/>- Ignore<br>- Override |
| Overridden By | - Is<br>- Is not<br>- Contains                                                 | Any text value.                                    |
| Labels        | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Empty<br>- Not empty | Any text value.                                    |
| Modified When | - Is within<br>- Is after<br>- Is before                                       | Calendar.                                          |

### Table View (Mapper)

| Column                                                                               | Condition                                                                            | Available Value                                                                |
|:-------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------------|:-------------------------------------------------------------------------------|
| Name                                                                                 | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                |
| Type                                                                                 | - In<br>- Not in                                                                     | Predefined list: <br/>- string<br>- number<br>- boolean<br>- object<br>- array |
| Optionality                                                                          | - Is<br>- Is not                                                                     | Predefined list: <br/>- optional<br>- required                                 |
| Description                                                                          | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                                                                |
| Default Value <br/> *(Available when switcher is in "Source" position)*              | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                |
| Sources <br/> *(Available when switcher is in "Target" position)*                    | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                |
| Targets <br/> *(Available when switcher is in "Source" position)*                    | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                |
| Targets Location <br/> *(Available when switcher is in "Source" position)*           | - In<br>- Not in                                                                     | Predefined list: <br/>- Header<br>- Property<br>- Body attribute               |
| Sources Location *(Available when switcher is in "Target" position)*                 | - In<br>- Not in                                                                     | Predefined list: <br/>- Constant<br>- Header<br>- Property<br>- Body attribute |
| Transformation Parameters <br/> *(Available when switcher is in "Source" position)*  | - Is<br>- Is not<br>- Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                |
| Transformation Description <br/> *(Available when switcher is in "Source" position)* | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                                                                |

### Elements view (Configuration Graph, Right Panel)

| Column       | Condition                                                        | Available Value       |
|:-------------|:-----------------------------------------------------------------|:----------------------|
| Element Type | - In<br>- Not in                                                 | List of all elements. |
| Element Name | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.       |

### Property search (Configuration Graph, Right Panel)

| Column              | Condition                                                        | Available Value                                                            |
|:--------------------|:-----------------------------------------------------------------|:---------------------------------------------------------------------------|
| Property Name       | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                            |
| Property Source     | - In<br>- Not in                                                 | Predefined list: <br/>- Header<br>- Property                               |
| Property Type       | - In<br>- Not in                                                 | Predefined list: <br/>- string<br>- number<br>- boolean<br>- object<br>- - |
| Property Usage Type | - In<br>- Not in                                                 | Predefined list: <br/>- Get<br>- Set                                       |
| Element Type        | - In<br>- Not in                                                 | List of all elements.                                                      |

### Test Cases (Chains)

| Column       | Condition                                                        | Available Value                               |
|:-------------|:-----------------------------------------------------------------|:----------------------------------------------|
| Name         | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |
| Description  | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |
| Trigger      | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |
| Status       | - Is<br>- Is not                                                 | Predefined list: <br/>- Enabled<br>- Disabled |
| Readiness    | - Is<br>- Is not                                                 | Predefined list: <br/>- Ready<br>- Incomplete |
| Rules        | - Is<br>- Less than<br>- More than                               | Any numeric value.                            |
| Active Rules | - Is<br>- Less than<br>- More than                               | Any numeric value.                            |
| Created When | - Is within<br>- Is after<br>- Is before                         | Calendar.                                     |
| Created By   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |
| Updated When | - Is within<br>- Is after<br>- Is before                         | Calendar.                                     |
| Updated By   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |

### Test Cases (Admin Tools)

| Column       | Condition                                                        | Available Value                               |
|:-------------|:-----------------------------------------------------------------|:----------------------------------------------|
| Name         | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |
| Description  | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |
| Chain        | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |
| Trigger      | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |
| Status       | - Is<br>- Is not                                                 | Predefined list: <br/>- Enabled<br>- Disabled |
| Readiness    | - Is<br>- Is not                                                 | Predefined list: <br/>- Ready<br>- Incomplete |
| Rules        | - Is<br>- Less than<br>- More than                               | Any numeric value.                            |
| Active Rules | - Is<br>- Less than<br>- More than                               | Any numeric value.                            |
| Created When | - Is within<br>- Is after<br>- Is before                         | Calendar.                                     |
| Created By   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |
| Updated When | - Is within<br>- Is after<br>- Is before                         | Calendar.                                     |
| Updated By   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                               |

### Test Cases → Response Validations (Admin Tools)

| Column      | Condition                                                                            | Available Value                                                                                                                                                     |
|:------------|:-------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Name        | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                                                                                                                                                     |
| Description | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                                                                                                                                                     |
| Status      | - Is<br>- Is not                                                                     | Predefined list: <br/>- Enabled<br>- Disabled                                                                                                                       |
| Entity Type | - In<br>- Not in                                                                     | Predefined list: <br/>- HTTP Response Code<br>- Body<br>- Header                                                                                                    |
| Entity Name | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                                                                                                                                                     |
| Condition   | - In<br>- Not in                                                                     | Predefined list: <br/>- Empty<br>- Exists<br>- Equals<br>- Contains<br>- Matches pattern<br>- Starts with<br>- Ends with<br>- Matches JSON schema<br>- Matches JSON |
| Parameters  | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Is<br>- Is not | Any text value.                                                                                                                                                     |

### Test Runs (Admin Tools)

| Column                 | Condition                                                        | Available Value                                                                        |
|:-----------------------|:-----------------------------------------------------------------|:---------------------------------------------------------------------------------------|
| Id                     | - Is<br>- Is not<br>- Contains                                   | Any text value.                                                                        |
| Status                 | - In<br>- Not in                                                 | Predefined list: <br/>- Pending<br>- Skipped<br>- Running<br>- Cancelled<br>- Finished |
| Test Cases With Errors | - Is<br>- Less than<br>- More than                               | Any numeric value.                                                                     |
| Test Cases             | - Is<br>- Less than<br>- More than                               | Any numeric value.                                                                     |
| Start Time             | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                              |
| Finish Time            | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                              |
| Created When           | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                              |
| Created By             | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                        |
| Updated When           | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                              |
| Updated By             | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                        |

### Test Runs (Chains)

| Column      | Condition                                                        | Available Value                                                                        |
|:------------|:-----------------------------------------------------------------|:---------------------------------------------------------------------------------------|
| Test Case   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                        |
| Test Run    | - Is<br>- Is not<br>- Contains                                   | Any text value.                                                                        |
| Status      | - In<br>- Not in                                                 | Predefined list: <br/>- Pending<br>- Skipped<br>- Running<br>- Cancelled<br>- Finished |
| Errors      | - Is<br>- Less than<br>- More than                               | Any numeric value.                                                                     |
| Start Time  | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                              |
| Finish Time | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                              |

### Test Runs → Test Run Id (Admin Tools)

| Column      | Condition                                                        | Available Value                                                                        |
|:------------|:-----------------------------------------------------------------|:---------------------------------------------------------------------------------------|
| Test Case   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                        |
| Chain       | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                                                                        |
| Status      | - In<br>- Not in                                                 | Predefined list: <br/>- Pending<br>- Skipped<br>- Running<br>- Cancelled<br>- Finished |
| Start Time  | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                              |
| Finish Time | - Is within<br>- Is after<br>- Is before                         | Calendar.                                                                              |

### Test Runs → Test Run Id → Test Case (Admin Tools)

| Column      | Condition                                                                            | Available Value   |
|:------------|:-------------------------------------------------------------------------------------|:------------------|
| Rule        | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Is<br>- Is not | Any text value.   |
| Message     | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Is<br>- Is not | Any text value.   |
| Description | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.   |

### Test Case Runs → Test Case (Chains)

| Column      | Condition                                                                            | Available Value   |
|:------------|:-------------------------------------------------------------------------------------|:------------------|
| Rule        | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Is<br>- Is not | Any text value.   |
| Description | - Contains<br>- Does not contain<br>- Empty<br>- Not empty                           | Any text value.   |
| Message     | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Is<br>- Is not | Any text value.   |

### Endpoint Mocks (Chains)

| Column                    | Condition                                                                            | Available Value                               |
|:--------------------------|:-------------------------------------------------------------------------------------|:----------------------------------------------|
| Name                      | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |
| Description               | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |
| Element                   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |
| Status                    | - Is<br>- Is not                                                                     | Predefined list: <br/>- Enabled<br>- Disabled |
| HTTP Response Status Code | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Is<br>- Is not | Any text value.                               |
| Response Time             | - Is<br>- Less than<br>- More than                                                   | Any numeric value.                            |
| Created When              | - Is within<br>- Is after<br>- Is before                                             | Calendar                                      |
| Created By                | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |
| Updated When              | - Is within<br>- Is after<br>- Is before                                             | Calendar                                      |
| Updated By                | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |

### Endpoint Mocks (Dev Tools)

| Column                    | Condition                                                                            | Available Value                               |
|:--------------------------|:-------------------------------------------------------------------------------------|:----------------------------------------------|
| Name                      | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |
| Description               | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |
| Chain                     | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |
| Element                   | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |
| Status                    | - Is<br>- Is not                                                                     | Predefined list: <br/>- Enabled<br>- Disabled |
| HTTP Response Status Code | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Is<br>- Is not | Any text value.                               |
| Response Time             | - Is<br>- Less than<br>- More than                                                   | Any numeric value.                            |
| Created When              | - Is within<br>- Is after<br>- Is before                                             | Calendar                                      |
| Created By                | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |
| Updated When              | - Is within<br>- Is after<br>- Is before                                             | Calendar                                      |
| Updated By                | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                               |

### Endpoint Mocks → Request Matchers

| Column      | Condition                                                                            | Available Value                                                                                                                                                     |
|:------------|:-------------------------------------------------------------------------------------|:--------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Name        | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                                                                                                                                                     |
| Description | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                                                                                                                                                     |
| Status      | - Is<br>- Is not                                                                     | Predefined list: <br/>- Enabled<br>- Disabled                                                                                                                       |
| Entity Type | - In<br>- Not in                                                                     | Predefined list: <br/>- Body<br>- Header<br>- Path Parameter<br>- Query Parameter                                                                                   |
| Entity Name | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with                     | Any text value.                                                                                                                                                     |
| Condition   | - In<br>- Not in                                                                     | Predefined list: <br/>- Empty<br>- Exists<br>- Equals<br>- Contains<br>- Matches pattern<br>- Starts with<br>- Ends with<br>- Matches JSON schema<br>- Matches JSON |
| Parameters  | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with<br>- Is<br>- Is not | Any text value.                                                                                                                                                     |

### Design Templates

| Column       | Condition                                                        | Available Value                              |
|:-------------|:-----------------------------------------------------------------|:---------------------------------------------|
| Name         | - Contains<br>- Does not contain<br>- Starts with<br>- Ends with | Any text value.                              |
| Type         | - Is<br>- Is not                                                 | Predefined list: <br/>- Custom<br>- Built-in |
| Created When | - Is within<br>- Is after<br>- Is before                         | Calendar.                                    |

## Process Initialization

---

Filtering options shall be manually specified and applied via user interface.

## User Interface

---

Filtering pop-up is accessible by clicking ![filter icon](img/filter.svg) button on the respective page. Data will be filtered after rules are configured and applied via "Apply" button.

## Data Storage

---

No data is being stored.

## Configuration

---

No additional configuration is available. Everything is being controlled by user interface.

