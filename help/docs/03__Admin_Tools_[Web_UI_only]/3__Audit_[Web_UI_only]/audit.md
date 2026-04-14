# Audit (Web UI only)

> ⛔️ This functionality is not available via the VS Code Extension.

## Description

---
Audit allows tracking most of the actions in UI. Click on the expandable section below to check the list of audit log types grouped by the entity:

<details><summary>Audit log types</summary>

| QIP Entity | Operation | Cases when action is registered |
| --- | --- | --- |
| Chain | Create | <ul><li>Create new chain.</li><li>Import new chain.</li></ul> |
| Chain | Update | <ul><li>Update chain name, description or labels.</li><li>Import new version of existing chain.</li></ul> |
| Chain | Delete | Delete chain. |
| Chain | Copy | <ul><li>Duplicate chain.</li><li>Copy chain to another folder.</li></ul> |
| Chain | Move | Move chain to another folder. |
| Chain | Export | Export chain. |
| Chain | Import | Import chain. |
| Snapshot | Create | Create snapshot. |
| Snapshot | Update | Change snapshot name or labels. |
| Snapshot | Delete | Delete snapshot. |
| Snapshot | Revert | Revert chain to previous state via saved snapshot. |
| Deployment | Create | <ul><li>Deploy chain.</li><li>Redeploy chain.</li></ul> |
| Deployment | Delete | Delete deployment. |
| Element | Create | Add new chain element (HTTP Trigger, Service Call, etc.) in Graph. |
| Element | Update | Update chain element in Graph. |
| Element | Delete | Delete element from Graph. |
| Element | Group | Group multiple elements in Graph. |
| Element | Ungroup | Ungroup elements in Graph. |
| Chain Runtime Properties | Create | Override logging settings for chain. |
| Chain Runtime Properties | Update | Override logging settings for chain. |
| Chain Runtime Properties | Delete | Switch to Consul's logging settings for chain. |
| Masked field | Create | Add new masking field. |
| Masked field | Update | Change masking field name. |
| Masked field | Delete | Delete masking field. |
| External/ Inner Cloud/ Implemented | Create | <ul><li>Create new service.</li><li>Import new service.</li></ul> |
| External/ Inner Cloud/ Implemented | Update | <ul><li>Change service name, description or labels.</li><li>Reactivate environment.</li><li>Update environment.</li><li>Import new version of existing service.</li></ul> |
| External/ Inner Cloud/ Implemented | Delete | Delete service. |
| External/ Inner Cloud/ Implemented | Export | Export service. |
| External/ Inner Cloud/ Implemented | Import | Import service. |
| Environment | Create | <ul><li>Add new environment manually.</li><li>Create environment by service discovery.</li></ul> |
| Environment | Update | Update environment data. |
| Environment | Delete | Delete environment (for external service). |
| API Specification | Create | <ul><li>Import API Specification manually.</li><li>Import API Specification via service discovery.</li></ul> |
| API Specification | Update | <ul><li>Run service discovery for Inner Cloud Services.</li><li>Change labels.</li></ul> |
| API Specification | Deprecate | Deprecate specification. |
| API Specification | Delete | Delete specification. |
| API Specification | Export | Export specification. |
| Specification Group | Create | <ul><li>Add new specification group manually.</li><li>Create specification group by service discovery.</li></ul> |
| Specification Group | Update | Change labels. |
| Specification Group | Delete | Delete Specification Group. |
| Service discovery | Start | When service discovery was started. |
| Service discovery | Execute | When service discovery was completed. |
| Common/ Secured Variable | Create | Create secured variable. |
| Common/ Secured Variable | Update | Change secured variable value. |
| Common/ Secured Variable | Delete | Delete secured variable. |
| Common/ Secured Variable | Export | Export secured variable(s). |
| Common/ Secured Variable | Import | Import secured variable(s). |
| Secret | Create | Create new secret on secured variables tab. |
| Template | Create | Upload new document template into the system. |
| Template | Delete | Delete template from the system. |
| Import Instructions | Create | Create new import instruction. |
| Import Instructions | Update | Update existing import instruction. |
| Import Instructions | Delete | Delete import instruction. |
| Import Instructions | Import | Upload import instructions. |
| Exchange | Delete | Terminate the exchange manually on ["Live Exchanges"](../8__Live_Exchanges/live_exchanges.md) tab. |

</details>

There is a specific **"Audit"** tab available in QIP UI, that could be utilized by user to view the logs.

> ℹ️ **Notes:**
>
> - There are retention policy settings, that could be configured as part of system installation. Following the settings, action logs might be removed after some period of time.
> - In case of bulk operations, audit page will register separate action per each entity (e.g. bulk chain import/export, bulk snapshot deletion, etc.)
> - For manual import of complex entities, such as chains, services, etc. system may register both IMPORT and CREATE/UPDATE operations, depending on how imported entities are handled.
> - In some scenarios, when the platform does not identify the exact operation type, it sets "Create and Update" as a value for the record.
## User Interface

---
### "Audit" Table View

Accessed via **Admin Tools → Audit**, the interface features a customizable table with these default columns:

- **Action Time** - datetime of the action. Time is being sorted the way that the last records are always on top by default.
- **Initiator** - name of the user who performed operation.
- **Operation** - type of the operation (Create, Update, Delete, etc.) with color bar additionally applied.
- **Entity Id** - unique identifier of recorded entity. Column is hidden by default.
- **Entity Type** - type of the entity with respective icon.
- **Entity Name** - reference to the entity that has been captured within the current operation. Reference won't operate if entity has been removed.
- **Request Id** - unique identifier, that helps to identify if multiple operations were executed as a group. Column is hidden by default.
- **Parent Id** - unique identifier of parent entity. Column is hidden by default.
- **Parent Name** - reference to parent entity.

The following actions are available:
* ![20](img/setting.svg) - Column setting.
* ![Redo|20](img/redo.svg) - Refresh.
* ![Download|20](img/cloud-download.svg) - Export Actions Logs.


Similar information is presented on "**Action details**" right panel, available by clicking respective table's record.

### Export to Excel

To export audit table to Excel file, find and click export ![Download|20](img/cloud-download.svg) button, select the required date range and confirm operation.

> ℹ️ **Note:** Resulted file will always have "Action date" values converted to **GMT** time zone.

### Refresh Logs

To refresh Audit table, simply click button ![Redo|20](img/redo.svg), presented in the menu or refresh the page itself.
