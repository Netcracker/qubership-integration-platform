# Audit (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>
## Description

---
Audit allows tracking most of the actions in UI. Click on the expandable section below to check the list of audit log types grouped by the entity:

<style>
summary {
  display: list-item;
  list-style: disclosure-closed inside;
  cursor: pointer;
}
details[open] > summary {
  list-style: disclosure-open inside;
}
</style>
<details><summary>Audit log types</summary>
<table>
    <thead>
        <tr>
            <th>QIP Entity</th>
            <th>Operation</th>
            <th>Cases when action is registered</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td rowspan="7">Chain</td>
            <td>Create </td>
            <td>
                <ul>
                    <li>Create new chain.</li>
                    <li>Import new chain.</li>
                </ul>
            </td>
        </tr>
        <tr>
            <td>Update </td>
            <td>
                <ul>
                    <li>Update chain name, description or labels.</li>
                    <li>Import new version of existing chain.</li>
                </ul>
            </td>
        </tr>
        <tr>
            <td>Delete </td>
            <td>Delete chain.</td>
        </tr>
        <tr>
            <td>Copy </td>
            <td>
                <ul>
                    <li>Duplicate chain.</li>
                    <li>Copy chain to another folder.</li>
                </ul>
            </td>
        </tr>
        <tr>
            <td>Move </td>
            <td>Move chain to another folder.</td>
        </tr>
        <tr>
            <td>Export  </td>
            <td>Export chain.  </td>
        </tr>
        <tr>
            <td>Import</td>
            <td>Import chain.</td>
        </tr>
        <tr>
            <td rowspan="4">Snapshot</td>
            <td>Create </td>
            <td>Create snapshot.</td>
        </tr>
        <tr>
            <td>Update</td>
            <td>Change snapshot name or labels.</td>
        </tr>
        <tr>
            <td>Delete</td>
            <td>Delete snapshot.</td>
        </tr>
        <tr>
            <td>Revert</td>
            <td>Revert chain to previous state via saved snapshot.</td>
        </tr>
        <tr>
            <td rowspan="2">Deployment</td>
            <td>Create</td>
            <td>
                <ul>
                    <li>Deploy chain.</li>
                    <li>Redeploy chain.</li>
                </ul>
            </td>
        </tr>
        <tr>
            <td>Delete</td>
            <td>Delete deployment.</td>
        </tr>
        <tr>
            <td rowspan="5">Element</td>
            <td>Create </td>
            <td> Add new chain element (HTTP Trigger, Service Call, etc.) in Graph.</td>
        </tr>
        <tr>
            <td>Update</td>
            <td>Update chain element in Graph.</td>
        </tr>
        <tr>
            <td>Delete</td>
            <td>Delete element from Graph.</td>
        </tr>
        <tr>
            <td>Group</td>
            <td>Group multiple elements in Graph.</td>
        </tr>
        <tr>
            <td>Ungroup</td>
            <td>Ungroup elements in Graph.</td>
        </tr>
        <tr>
            <td rowspan="3">Chain Runtime Properties</td>
            <td>Create</td>
            <td>Override logging settings for chain.</td>
        </tr>
        <tr>
            <td>Update</td>
            <td>Override logging settings for chain.</td>
        </tr>
        <tr>
            <td>Delete</td>
            <td>Switch to Consul's logging settings for chain. </td>
        </tr>
        <tr>
            <td rowspan="3">Masked field</td>
            <td>Create</td>
            <td> Add new masking field. </td>
        </tr>
        <tr>
            <td>Update</td>
            <td>Change masking field name. </td>
        </tr>
        <tr>
            <td>Delete</td>
            <td>Delete masking field. </td>
        </tr>
        <tr>
            <td rowspan="5">External/<br/>Inner Cloud/<br/>Implemented</td>
                    <td>Create</td>
                    <td>
                        <ul>
                            <li>Create new service.</li>
                            <li>Import new service.</li>
                        </ul>
                    </td>
                <tr>
                    <td>Update</td>
                    <td>
                        <ul>
                            <li>Change service name, description or labels.</li>
                            <li>Reactivate environment.</li>
                            <li>Update environment.</li>
                            <li>Import new version of existing service.</li>
                        </ul>
                    </td>
                </tr>
                <tr>
                    <td>Delete</td>
                    <td>Delete service. </td>
                </tr>
                <tr>
                    <td>Export  </td>
                    <td>Export service.  </td>
                </tr>
                <tr>
                    <td>Import</td>
                    <td>Import service.</td>
                </tr>
                <tr>
                    <td rowspan="3">Environment</td>
                    <td>Create</td>
                    <td>
                        <ul>
                            <li>Add new environment manually.</li>
                            <li>Create environment by service discovery.</li>
                        </ul>
                    </td>
                </tr>
                <tr>
                    <td>Update</td>
                    <td>Update environment data.</td>
                </tr>
                <tr>
                    <td>Delete</td>
                    <td>Delete environment (for external service). </td>
                </tr>
                <tr>
                    <td rowspan="5">API Specification</td>
                    <td>Create</td>
                    <td>
                        <ul>
                            <li>Import API Specification manually.</li>
                            <li>Import API Specification via service discovery.</li>
                        </ul>
                    </td>
                </tr>
                <tr>
                    <td>Update</td>
                    <td>
                        <ul>
                            <li>Run service discovery for Inner Cloud Services.</li>
                            <li>Change labels.</li>
                        </ul>
                    </td>
                </tr>
                <tr>
                    <td>Deprecate</td>
                    <td>Deprecate specification.</td>
                </tr>
                <tr>
                    <td>Delete</td>
                    <td>Delete specification.</td>
                </tr>
                <tr>
                    <td>Export</td>
                    <td>Export specification.</td>
                </tr>
                <tr>
                    <td rowspan="3">Specification Group</td>
                    <td>Create</td>
                    <td>
                        <ul>
                            <li>Add new specification group manually. </li>
                            <li>Create specification group by service discovery.</li>
                        </ul>
                    </td>
                </tr>
                <tr>
                    <td>Update</td>
                    <td>Change labels.</td>
                </tr>
                <tr>
                    <td>Delete</td>
                    <td>Delete Specification Group.</td>
                </tr>
                <tr>
                    <td rowspan="2">Service discovery</td>
                    <td>Start</td>
                    <td> When service discovery was started.</td>
                </tr>
                <tr>
                    <td>Execute</td>
                    <td>When service discovery was completed. </td>
                </tr>
                <tr>
                    <td rowspan="5">Common/<br/>Secured Variable</td>
                        <td>Create</td>
                        <td>Create secured variable.</td>
                    <tr>
                        <td>Update</td>
                        <td>Change secured variable value.</td>
                    </tr>
                    <tr>
                        <td>Delete</td>
                        <td>Delete secured variable.</td>
                    </tr>
                    <tr>
                        <td>Export</td>
                        <td>Export secured variable(s).</td>
                    </tr>
                    <tr>
                        <td>Import</td>
                        <td>Import secured variable(s).</td>
                    </tr>
                    <tr>
                        <td rowspan="1">Secret</td>
                        <td>Create</td>
                        <td>Create new secret on secured variables tab.</td>
                    </tr>
                    <tr>
                        <td rowspan="2">Template</td>
                        <td>Create</td>
                        <td>Upload new document template into the system.</td>
                    </tr>
                    <tr>
                        <td>Delete</td>
                        <td>Delete template from the system.</td>
                    </tr>
                    <tr>
                        <td rowspan="4">Import Instructions</td>
                        <td>Create</td>
                        <td>Create new import instruction.</td>
                    </tr>
                    <tr>
                        <td>Update</td>
                        <td>Update existing import instruction.</td>
                    </tr>
                    <tr>
                        <td>Delete</td>
                        <td>Delete import instruction.</td>
                    </tr>
                    <tr>
                        <td>Import</td>
                        <td>Upload import instructions.</td>
                    </tr>
                    <tr>
                        <td>Exchange</td>
                        <td>Delete</td>
                        <td>Terminate the exchange manually on <a href="docs/03__Admin_Tools/8__Live_Exchanges/live_exchanges.md">"Live Exchanges"</a> tab.</td>
                    </tr>
    </tbody>
</table>
</details>

There is a specific **"Audit"** tab available in QIP UI, that could be utilized by user to view the logs.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Notes:</b><br>
<li>There are retention policy settings, that could be configured as part of system installation. Following the settings, action logs might be removed after some period of time.</li>
<li>In case of bulk operations, audit page will register separate action per each entity (e.g. bulk chain import/export, bulk snapshot deletion, etc.)</li>
<li>For manual import of complex entities, such as chains, services, etc. system may register both IMPORT and CREATE/UPDATE operations, depending on how imported entities are handled.</li>
<li>In some scenarios, when the platform does not identify the exact operation type, it sets "Create and Update" as a value for the record.</li>
</br>
</div>

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

The following actions are available via Action Menu <img src="docs/03__Admin_Tools/3__Audit/img/more.svg" width="20" height="20">:
* <img src="docs/03__Admin_Tools/3__Audit/img/cloud-download.svg" width="20" height="20"> - Export Actions Logs.
* <img src="docs/03__Admin_Tools/3__Audit/img/redo.svg" width="20" height="20"> - Refresh.

Similar information is presented on "**Action details**" right panel, available by clicking respective table's record.

### Export to Excel

To export audit table to Excel file, find and click export <img src="docs/03__Admin_Tools/3__Audit/img/cloud-download.svg" width="20" height="20"> button from tha Action Menu, select the required date range and confirm operation.
<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
Resulted file will always have "Action date" values converted to <b>GMT</b> time zone.
</div>
<br>

### Refresh Logs

To refresh Audit table, simply click button <img src="docs/03__Admin_Tools/3__Audit/img/redo.svg" width="20" height="20">, presented in the menu or refresh the page itself.