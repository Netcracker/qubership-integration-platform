# General Functions

## Notifications

Notifications ![Bell](img/bell.svg) are implemented to communicate interactively with the user. It makes an ability to track the process of system changes. Notification are divided into 3 types:

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
2. Navigate to some page or popup of some QIP element. Click icon ![Question](img/question-circle.svg) (for the page - top-right side, for the QIP element popup - top left side near the element type in curly brackets). There will be opened appropriate page in new browser tab.
3. Use **pages navigation tree** on the left side of QIP Helper to go through the pages and **"Search Documentation... ![Search](img/search.svg)"** text field to find particular information by fulltext search.

## Export/Import

QIP provides an ability to export ![Download](img/cloud-download.svg) and import ![Upload](img/cloud-upload.svg) different entities. The export function supports both **legacy** and **new** formats for QIP artifacts. Switching between legacy and new format option for export is possible via configuration of specific environment parameter (for the correct parameter name, please, contact system administrator).
The next set of tables specifies the structure for QIP artifacts in the different supported formats.

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
<details><summary>Export - New Format</summary>
<table cellspacing="2" border="1" cellpadding="5">
    <thead>
        <tr>
            <th colspan="6">Folder structure</th>
            <th>Content</th>
            <th>Cardinality</th>
            <th>Sample</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td colspan=9>Project QIP configurations root folder at git/SVN (shouldn't be present in zip-archive)</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td colspan="5"> **chains**</td>
            <td> container for chains</td>
            <td> 1..1</td>
            <td>chains </td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td colspan="4">{chain-id}</td>
            <td>UUID of the chain</td>
            <td>0..N</td>
            <td>ca82633e-7255-4be7-87bb-b63d65b11179</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">chain-{chain_id}.yaml</td>
            <td>yaml configuration of the chain</td>
            <td>1..1</td>
            <td>chain-ca82633e-7255-4be7-87bb-b63d65b11179.yaml</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">script-{module_id}.groovy</td>
            <td>groovy file for script module (if chain configuration contains script)</td>
            <td>0..N</td>
            <td>script-96ba0514-8033-4796-b355-9c22839a530f.groovy</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">script-before-{service_call_id}.groovy</td>
            <td>groovy file for embedded scripting module in Service Call for preparing of request</td>
            <td>0..N</td>
            <td>script-before-96ba0514-8033-4796-b355-9c22839a530f.groovy</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">script-{response code}-{module_id}.groovy</td>
            <td>groovy file for embedded scripting module in Service Call for response handling (by particular response code)</td>
            <td>0..N</td>
            <td>script-404-96ba0514-8033-4796-b355-9c22839a530f.groovy</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">mappingDescription-{module_id}.json</td>
            <td>json file with mapping configuration (if chain configuration contains mapper)</td>
            <td>0..N</td>
            <td>mappingDescription-647b944c-3385-0987-c5a0-97a0988d076c.json</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">mappingDescription-before-{module_id}.json</td>
            <td>json file for embedded Mapper in Service Call for preparing of request</td>
            <td>0..N</td>
            <td>mappingDescription-before-467b844c-3385-0987-c5a0-97a0988d076c.json</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">mappingDescription-{response code}-{module_id}.json</td>
            <td>json file for embedded Mapper in Service Call for response handling (by particular response code)</td>
            <td>0..N</td>
            <td>mappingDescription-200-467b844c-3385-0987-c5a0-97a0988d076c.json</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td colspan="5"> **services**</td>
            <td> container for services</td>
            <td> 1..1</td>
            <td>services</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td colspan="4">{service_id}</td>
            <td>UUID of the service</td>
            <td>1..N</td>
            <td>4985c997-955e-4edb-b137-dfda539b87a1</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">service-{service_id}.yaml</td>
            <td>yaml configuration of the chain (incl. env configs)</td>
            <td>1..1</td>
            <td>service-4985c997-955e-4edb-b137-dfda539b87a1.yaml</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">specGroup-{specification_group_id}.yaml</td>
            <td>yaml configuration of the specification group</td>
            <td>0..N</td>
            <td>specGroup-4985c997-955e-4edb-b137-dfda539b87a1-groupName.yaml</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">specification-{specification_id}.yaml</td>
            <td>yaml configuration of the API Specification</td>
            <td>0..N</td>
            <td>specification-4985c997-955e-4edb-b137-dfda539b87a1-groupName-1.0.0.yaml</td>
        </tr>
         <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">**source-{specification_id}**</td>
            <td>container for specifications of the group</td>
            <td>0..N</td>
            <td>source-4985c997-955e-4edb-b137-dfda539b87a1-groupName-1.0.0</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
        <td></td>
            <td colspan="2">{specification_name}.{json | yaml | wsdl | xsd}</td>
            <td>swagger | asyncAPI | wsdl specification source file</td>
            <td>1..N</td>
            <td>default.json</td>
        </tr>
         <tr>
            <td width="1"></td>
            <td colspan="5"> **variables**</td>
            <td>common QIP variables</td>
            <td> 1..1</td>
            <td>variables</td>
        </tr>
         <tr>
            <td width="1"></td>
            <td></td>
            <td colspan="4">common-variables.yaml</td>
            <td>yaml file with list of common variables (will be exported every time if exist at least one variable)</td>
            <td>0..1</td>
            <td>common-variables.yaml</td>
        </tr>
         <tr>
            <td width="1"></td>
            <td colspan="5">qip-import-instructions.yaml</td>
            <td>yaml configuration of the import instructions</td>
            <td> 0..1</td>
            <td>qip-import-instructions.yaml</td>
        </tr>
    </tbody>
</table>
</details>
<br>


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
<details><summary>Export - Legacy Format</summary>
<table style="width:90%" cellspacing="2" border="1" cellpadding="5">
    <thead>
        <tr>
            <th style="width:30%" colspan="6">Folder structure</th>
            <th>Content</th>
            <th>Cardinality</th>
            <th>Sample</th>
        </tr>
    </thead>
    <tbody>
        <tr>
            <td colspan=9>Project QIP configurations root folder at git (shouldn't be present in zip-archive)</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td colspan="5"> <b>chains</b></td>
            <td> container for chains</td>
            <td> 1..1</td>
            <td>chains </td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td colspan="4">{chain-id}</td>
            <td>UUID of the chain</td>
            <td>0..N</td>
            <td>ca82633e-7255-4be7-87bb-b63d65b11179</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">chain-{chain_id}.yaml</td>
            <td>yaml configuration of the chain</td>
            <td>1..1</td>
            <td>chain-ca82633e-7255-4be7-87bb-b63d65b11179.yaml</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">script-{module_id}.groovy</td>
            <td>groovy file for script module (if chain configuration contains script)</td>
            <td>0..N</td>
            <td>script-96ba0514-8033-4796-b355-9c22839a530f.groovy</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">script-before-{service_call_id}.groovy</td>
            <td>groovy file for embedded scripting module in Service Call for preparing of request</td>
            <td>0..N</td>
            <td>script-before-96ba0514-8033-4796-b355-9c22839a530f.groovy</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">script-{response code}-{module_id}.groovy</td>
            <td>groovy file for embedded scripting module in Service Call for response handling (by particular response code)</td>
            <td>0..N</td>
            <td>script-404-96ba0514-8033-4796-b355-9c22839a530f.groovy</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">mappingDescription-{module_id}.json</td>
            <td>json file with mapping configuration (if chain configuration contains mapper)</td>
            <td>0..N</td>
            <td>mappingDescription-647b944c-3385-0987-c5a0-97a0988d076c.json</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">mappingDescription-before-{module_id}.json</td>
            <td>json file for embedded Mapper in Service Call for preparing of request</td>
            <td>0..N</td>
            <td>mappingDescription-before-467b844c-3385-0987-c5a0-97a0988d076c.json</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">mappingDescription-{response code}-{module_id}.json</td>
            <td>json file for embedded Mapper in Service Call for response handling (by particular response code)</td>
            <td>0..N</td>
            <td>mappingDescription-200-467b844c-3385-0987-c5a0-97a0988d076c.json</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td colspan="5"> <b>services</b></td>
            <td> container for services</td>
            <td> 1..1</td>
            <td>services</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td colspan="4">{service_id}</td>
            <td>UUID of the service</td>
            <td>1..N</td>
            <td>4985c997-955e-4edb-b137-dfda539b87a1</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">service-{service_id}.yaml</td>
            <td>yaml configuration of the chain (incl. env configs)</td>
            <td>1..1</td>
            <td>service-4985c997-955e-4edb-b137-dfda539b87a1.yaml</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">specGroup-{specification_group_id}.yaml</td>
            <td>yaml configuration of the specification group</td>
            <td>0..N</td>
            <td>specGroup-4985c997-955e-4edb-b137-dfda539b87a1-groupName.yaml</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3">specification-{specification_id}.yaml</td>
            <td>yaml configuration of the API Specification</td>
            <td>0..N</td>
            <td>specification-4985c997-955e-4edb-b137-dfda539b87a1-groupName-1.0.0.yaml</td>
        </tr>
         <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
            <td colspan="3"><b>source-{specification_id}</b></td>
            <td>container for specifications of the group</td>
            <td>0..N</td>
            <td>source-4985c997-955e-4edb-b137-dfda539b87a1-groupName-1.0.0</td>
        </tr>
        <tr>
            <td width="1"></td>
            <td></td>
            <td></td>
        <td></td>
            <td colspan="2">{specification_name}.{json | yaml | wsdl | xsd}</td>
            <td>swagger | asyncAPI | wsdl specification source file</td>
            <td>1..N</td>
            <td>default.json</td>
        </tr>
         <tr>
            <td width="1"></td>
            <td colspan="5"> <b>variables</b></td>
            <td>common QIP variables</td>
            <td> 1..1</td>
            <td>variables</td>
        </tr>
         <tr>
            <td width="1"></td>
            <td></td>
            <td colspan="4">common-variables.yaml</td>
            <td>yaml file with list of common variables (will be exported every time if exist at least one variable)</td>
            <td>0..1</td>
            <td>common-variables.yaml</td>
        </tr>
         <tr>
            <td width="1"></td>
            <td colspan="5">qip-import-instructions.yaml</td>
            <td>yaml configuration of the import instructions</td>
            <td> 0..1</td>
            <td>qip-import-instructions.yaml</td>
        </tr>
    </tbody>
</table>
</details>
<br>

## Filters

There is filtering functionality available for most of the tables, utilized across different pages. Click button ![Filter](img/filter.svg) and enter next data on filter pop-up:
- Column
- Condition
- Value

It is possible to specify multiple filtering conditions via "**Add filter**" button. When filters are applied, button "**Filter**" will have a small counter indicator, showing quantity of active filters. It is also possible to remove particular filter via ![Delete](img/delete.svg) button or remove all filters via "**Clear All**" button.

## Table Settings

Most of the tables in the system can be adjusted not only by extending/shrinking column size, but also by controlling each column's visibility and sequence. To do so, click gear button ![Filter](img/filter.svg) on top of the table and adjust properties accordingly. Some of the columns can't be hidden or moved - this is explained for exact columns in respective design articles.

## Switch between Blue and Green versions

In order to switch between Blue and Green configuration versions in QIP, simply use selector on top right of the screen. This selector is only visible on environments, properly configured for Blue/Green approach.
