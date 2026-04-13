# General Functions

## Notifications

Notifications ![Bell|20](img/bell.svg) are implemented to communicate interactively with the user. It makes an ability to track the process of system changes. Notification are divided into 3 types:

🟩 **Success** - some process or operation was ended without errors and exceptions (e.g. service discovery was completed or chain was deployed successfully).

🟨 **Warning** - warning messages in some operations (e.g. chain has Draft deployment status).

🟥 **Error** - error messages (e.g. chain was not deployed or some required parameter is not filled in chain element).

Notification has the next general structure:
1. **Service name** (optional) - name of the QIP service where the change was occurred.
2. **Message** - the main content of the notifications.
3. **Stack trace** (optional) - in case of technical error provides the ability to check root cause of the error and helps to find the problem root cause.
4. **Date** - date and time of the notification.

## Qubership Integration Platform Help

Qubership Integration Platform Help is embedded to QIP UI detailed guide about how to use QIP. For each UI page or QIP element there is particular help page.

To open QIP Help:

1. Authorize in QIP UI.
2. Navigate to some page or popup of some QIP element. Click icon ![Question|20](img/question-circle.svg) (for the page - top-right side, for the QIP element popup - top left side near the element type in curly brackets). There will be opened appropriate page in new browser tab.
3. Use **pages navigation tree** on the left side of QIP Helper to go through the pages and **"Search Documentation... ![Search|20](img/search.svg)"** text field to find particular information by fulltext search.

## Export/Import

QIP provides an ability to export ![Download|20](img/cloud-download.svg) and import ![Upload|20](img/cloud-upload.svg) different entities.
The export function supports both **legacy** and **new** formats for QIP artifacts.
Switching between legacy and new format option for export is possible via configuration of specific environment parameter
(for the correct parameter name, please, contact system administrator).
The next set of tables specifies the structure for QIP artifacts in the different supported formats.

<details><summary>Export - New Format</summary>

```text
Project root (git/SVN root, not included in zip-archive)
└── chains/                                                                    container for chains [1..1]
    └── {chain-id}/                                                            UUID of the chain [0..N]
        ├── chain-{chain_id}.yaml                                              yaml configuration of the chain [1..1]
        ├── script-{module_id}.groovy                                          groovy file for script module (if chain configuration contains script) [0..N]
        ├── script-before-{service_call_id}.groovy                             groovy file for embedded scripting module in Service Call for preparing of request [0..N]
        ├── script-{response code}-{module_id}.groovy                          groovy file for embedded scripting module in Service Call for response handling (by particular response code) [0..N]
        ├── mappingDescription-{module_id}.json                                json file with mapping configuration (if chain configuration contains mapper) [0..N]
        ├── mappingDescription-before-{module_id}.json                         json file for embedded Mapper in Service Call for preparing of request [0..N]
        └── mappingDescription-{response code}-{module_id}.json                json file for embedded Mapper in Service Call for response handling (by particular response code) [0..N]
├── services/                                                                  container for services [1..1]
│   └── {service_id}/                                                          UUID of the service [1..N]
│       ├── service-{service_id}.yaml                                          yaml configuration of the chain (incl. env configs) [1..1]
│       ├── specGroup-{specification_group_id}.yaml                            yaml configuration of the specification group [0..N]
│       ├── specification-{specification_id}.yaml                              yaml configuration of the API Specification [0..N]
│       └── source-{specification_id}/                                         container for specifications of the group [0..N]
│           └── {specification_name}.{json|yaml|wsdl|xsd}                      swagger | asyncAPI | wsdl specification source file [1..N]
├── variables/                                                                 common QIP variables [1..1]
│   └── common-variables.yaml                                                  yaml file with list of common variables (will be exported every time if exist at least one variable) [0..1]
└── qip-import-instructions.yaml                                               yaml configuration of the import instructions [0..1]
```

</details>

<details><summary>Export - Legacy Format</summary>

```text
Project root (git/SVN root, not included in zip-archive)
└── chains/                                                                    container for chains [1..1]
    └── {chain-id}/                                                            UUID of the chain [0..N]
        ├── chain-{chain_id}.yaml                                              yaml configuration of the chain [1..1]
        ├── script-{module_id}.groovy                                          groovy file for script module (if chain configuration contains script) [0..N]
        ├── script-before-{service_call_id}.groovy                             groovy file for embedded scripting module in Service Call for preparing of request [0..N]
        ├── script-{response code}-{module_id}.groovy                          groovy file for embedded scripting module in Service Call for response handling (by particular response code) [0..N]
        ├── mappingDescription-{module_id}.json                                json file with mapping configuration (if chain configuration contains mapper) [0..N]
        ├── mappingDescription-before-{module_id}.json                         json file for embedded Mapper in Service Call for preparing of request [0..N]
        └── mappingDescription-{response code}-{module_id}.json                json file for embedded Mapper in Service Call for response handling (by particular response code) [0..N]
├── services/                                                                  container for services [1..1]
│   └── {service_id}/                                                          UUID of the service [1..N]
│       ├── service-{service_id}.yaml                                          yaml configuration of the chain (incl. env configs) [1..1]
│       ├── specGroup-{specification_group_id}.yaml                            yaml configuration of the specification group [0..N]
│       ├── specification-{specification_id}.yaml                              yaml configuration of the API Specification [0..N]
│       └── source-{specification_id}/                                         container for specifications of the group [0..N]
│           └── {specification_name}.{json|yaml|wsdl|xsd}                      swagger | asyncAPI | wsdl specification source file [1..N]
├── variables/                                                                 common QIP variables [1..1]
│   └── common-variables.yaml                                                  yaml file with list of common variables (will be exported every time if exist at least one variable) [0..1]
└── qip-import-instructions.yaml                                               yaml configuration of the import instructions [0..1]
```

</details>

## Filters

There is filtering functionality available for most of the tables, utilized across different pages. Click button ![Filter|20](img/filter.svg) and enter next data on filter pop-up:
- Column
- Condition
- Value

It is possible to specify multiple filtering conditions via "**Add filter**" button. When filters are applied, button "**Filter**" will have a small counter indicator, showing quantity of active filters. It is also possible to remove particular filter via ![Delete|20](img/delete.svg) button or remove all filters via "**Clear All**" button.

## Table Settings

Most of the tables in the system can be adjusted not only by extending/shrinking column size, but also by controlling each column's visibility and sequence. To do so, click gear button ![Filter|20](img/filter.svg) on top of the table and adjust properties accordingly. Some of the columns can't be hidden or moved - this is explained for exact columns in respective design articles.

## Switch between Blue and Green versions

In order to switch between Blue and Green configuration versions in QIP, simply use selector on top right of the screen. This selector is only visible on environments, properly configured for Blue/Green approach.
