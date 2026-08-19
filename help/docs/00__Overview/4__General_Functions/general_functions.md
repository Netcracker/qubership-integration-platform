# General Functions

## Notifications
<ins>Web UI</ins>

Notifications ![bell](img/bell.svg) are implemented to communicate interactively with the user. It provides the ability to track the process of system changes. Notifications are divided into 3 types:

![exclamation-circle-blue](img/info-circle.svg) **Success** - some process or operation was ended without errors and exceptions (e.g. service discovery was completed or chain was deployed successfully).

![exclamation-circle-warn](img/exclamation-circle-warn.svg) **Warning** - warning messages in some operations (e.g. chain has Draft deployment status).

![close-circle](img/close-circle.svg) **Error** - error messages (e.g. chain was not deployed or some required parameter is not filled in chain element).

Each notification has the following structure:
1. **Service** (optional) - name of the QIP service where the change occurred.
2. **Message** - the main content of the notifications.
3. **Stack trace** (optional) - in case of a technical error, provides the stack trace to help identify the root cause.
4. **Occurred** - date and time of the notification.

<ins>VS Code Extension</ins>

The extension uses the standard Visual Studio Code notification structure.

## Qubership Integration Platform Help
Qubership Integration Platform Help is a UI embedded guide on how to work with QIP. For each UI page or QIP element there is a particular help page.

To open QIP Help:

1. Log in to <ins>Web UI</ins>.
2. Navigate to some page or popup of some QIP element. Click icon ![question-circle](img/question-circle.svg) (for the page - top-right side, for the QIP element popup - top left side near the element type in curly brackets). The appropriate page opens in a new browser tab.
3. Use **pages navigation tree** on the left side of QIP Helper to go through the pages and **"Search Documentation... ![search](img/search.svg)"** text field to find particular information by full-text search.

## Export/Import
**`⛔ Not available via VS Code extension`**

QIP provides an ability to export ![download](img/cloud-download.svg) and import ![upload](img/cloud-upload.svg) different entities.
The export function supports both **legacy** and **new** formats for QIP artifacts.
Switching between legacy and new format option for export is possible via configuration of specific environment parameter
(for the correct parameter name, contact your system administrator).
The following tables specify the structure for QIP artifacts in the different supported formats.

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
└── import-instructions.yaml                                               yaml configuration of the import instructions [0..1]
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
└── import-instructions.yaml                                               yaml configuration of the import instructions [0..1]
```

</details>

## Filters

There is filtering functionality available for most of the column tables, utilized across different pages. Click the ![filter](img/filter.svg) button and enter the following values in the filter popup:
- Column
- Condition
- Value

It is possible to specify multiple filtering conditions via "**Add filter**" button. When filters are applied, button "**Filter**" will have a small counter indicator, showing the number of active filters. It is also possible to remove particular filter via ![delete](img/delete.svg) button or remove all filters via "**Clear All**" button.

## Table Sorting

For some table columns the following sort options are available:
- ![caret-up](img/caret-up.svg) Sort Ascending
- ![caret-down](img/caret-down.svg) Sort Descending

## Table Settings

Most of the tables in the system can be adjusted not only by extending/shrinking column size, but also by controlling each column's visibility and sequence. To do so, click the gear button ![setting](img/setting.svg) at the top of the table and adjust properties accordingly. Some of the columns can't be hidden or moved - this is explained for exact columns in respective design articles.

## View User Details
**`⛔ Not available via VS Code extension`**

Click the user icon ![user](img/user.svg) at the top right of the screen to see user and tenant details:
- username
- user email
- tenant name
- tenant ID

The tenant is set during login, when you select the domain.


## Go to Home Page
**`⛔ Not available via VS Code extension`**

In the top-left corner, the “QIP” button returns you to the home page, which lists all chains and chain folders.

## Switch between Blue and Green versions
**`⛔ Not available via VS Code extension`**

To switch between Blue and Green configuration versions in QIP, use the selector at the top right of the screen. This selector is only visible on environments, properly configured for Blue/Green approach.

## Reset UI Configuration
**`⛔ Not available via VS Code extension`**

The system saves the state of some UI elements while the user works with them. Such elements are mentioned below:
- **Table filters** - selected columns, conditions and values.
- **Table columns** - sorting results, visibility and sequence.
- **Left panel** for sections "Services", "Admin Tools", "Dev Tools" - expanded or collapsed view.

To reset saved configuration, click user icon ![user](img/user.svg) on top right of the screen and then click ![redo](img/redo.svg) "Reset UI preferences" button.

## Theme Settings
<ins>Web UI</ins>

To choose the interface theme, click the user icon ![user](img/user.svg) at the top right of the screen. The popup includes a **Theme** selector with the following options:
- ![desktop](img/desktop.svg) – System (default); automatically follows the theme of your operating system (light or dark mode).
- ![sun](img/sun.svg) – Light; applies a bright background with dark text.
- ![moon](img/moon.svg)  – Dark; applies a dark background with light text.
- ![eye](img/eye.svg)  – HC (high contrast); maximizes color contrast for better readability.

<ins>VS Code Extension</ins>

In the VS Code Extension, the interface theme cannot be changed separately. It automatically follows the theme selected in Visual Studio Code.
