# Diagnostic (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>
## Description

---
**Diagnostic** page provides an ability to identify common issues in the system, present them in the table and provide clear instructions for their resolution. There are several native validations supplied with Qubership Integration Platform, but it is also possible to upload new ones via smartplug.

## User Interface

---
### View Diagnostic Rules

Navigate to **Admin Tools** -> **Diagnostic** to see the table with last diagnostic run's results. Table consist of next columns and elements:

- **Name** - clickable validation name. When clicked, system opens pop-up with detailed explanation of the validation.
	- **Id** - unique identifier of validation.
	- **Name** - exact validation name, as presented in the table.
	- **Description** - short validation description.
	- **Hint** - contains basic instructions to resolve the issues, found as a result of particular validation.
	- **Validation Source** - specifies source of validations. Native validations are marked as "Built-in", others have "Custom" source.
	- **Severity** - shows if found issues shall be considered as Errors or Warnings.
	- **Alerts** - shows current number of alerts.
	- **Parameters** - section, that optionally contains additional parameters, available for validation.
- **Status** - shows current status of each particular validation:
	- <span style="color:gray; font-size: 30px;">&#8226</span> **Not Started** - validation has never been executed.
	- <span style="color:blue; font-size: 30px;">&#8226</span> **In Progress** - validation is in progress.
	- <span style="color:red; font-size: 30px;">&#8226</span> **Failed** - validation failed.
	- <span style="color:green; font-size: 30px;">&#8226</span> **Finished** - validation finished successfully.
- **Alerts** - counter, indicates the quantity of found errors, that must be fixed promptly or warnings, indicating that issues may affect chain processing, but not require immediate resolution.
- **Hint** - allows to see a hint for detected issue's root cause and provide short recommendation for fix. Click to display tooltip with suggestions.
- **Start Time** - timestamp of last validation run.
- **Control panel** - panel, placed on top of the table. Provides next capabilities:
	- **Search** field - allows to find validation or validated entity by its name.
	- <img src="docs/04__Dev_Tools/3__Diagnostic/img/filter.svg" width="20" height="20"> - opens filter pop-up.
	- <img src="docs/04__Dev_Tools/3__Diagnostic/img/setting.svg" width="20" height="20"> - opens pop-up with table properties that allows to adjust visibility and sequence of columns except Name.
	- **Run Diagnostic** button - initiates validation process.

### View Diagnostic Results

When diagnostic results are available and issues found, each executed validation, that has counter, may be expanded with <img src="docs/04__Dev_Tools/3__Diagnostic/img/plus.svg" width="20" height="20"> icon near its name. After its expanded, system shows chains and elements, that contain found issues and require attention.

### Run Validations

Navigate to Admin Tools and then "Diagnostic page" tab. Select required validations from the table via checkboxes and click "Run Validation" button. Next validations are available for execution:

- **Access control settings are not specified for HTTP Triggers** - rule allows to check if RBAC or ABAC settings are defined under HTTP Triggers.
- **Chain reference has no target sub-chain** - rule allows to find chains without reference to the target sub-chain.
- **Deployment has excessive logging settings** - rule allows to detect chains, deployed with "Debug" session level of logging.
- **Deprecated elements found in the chain** - rule allows to find chains with deprecated elements.
- **Large number of snapshots** - rule allows to check the number of snapshots that older than configured value.
- **Multiple async consumers found in the chain** - rule allows to detect chains with identical asynchronous consumers (2 or more). Duplication check is performing by following parameters of Kafka/RabbitMQ/AsyncAPI Trigger according to chosen protocol and connection source type:
	- Kafka (manual) - combination of <u>_Topic_</u> & <u>_Group ID_</u> is equal in the chains.
	- RabbitMQ (manual) - <u>_Queue(s) Name_</u> is equal in the chains.
	- Kafka (MaaS) - combination of <u>_Topic Classifier Name_</u>, <u>_Classifier Namespace_</u> and <u>_Group ID_</u> is equal in the chains.
	- RabbitMQ (MaaS) - combination of <u>_Vhost Maas Classifier Name_</u>, <u>_Classifier Namespace_</u> and <u>_Queue(s) Name_</u> is equal in the chains.
- **Scripting found in the chain** - rule allows to find chains and chain elements which contain scripting.
- **Sub-chain is used by single chain or not used at all** - rule allows to find chains which were not used in other chains, or they were used only once.
- **Timeout is empty** - rule allows to find chain elements with empty timeout value.
- **Unsupported elements found in the chain** - rule allows to find chains with elements that not supported by the system anymore.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px"><b>Each validation performed on chain element data relies solely on the current design-time chain configuration.</b> Сhain snapshots or deployments do not affect the results of these validations.

</div>