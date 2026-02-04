# General Functions

## Notifications

Notifications <img src="docs/00__Overview/4__General_Functions/img/bell.svg" width="20" height="20"> are implemented to communicate interactively with the user. It makes an ability to track the process of system changes. Notification are divided into 3 types:

<span style="color:green; font-size: 20px;">&#x25A0</span> **Success**  - some process or operation was ended without errors and exceptions (e.g. service discovery was completed or chain was deployed successfully).<br>
<span style="color:yellow; font-size: 20px;">&#x25A0</span> **Warning** - warning messages in some operations (e.g. chain has Draft deployment status).<br>
<span style="color:red; font-size: 20px;">&#x25A0</span> **Error** - error messages (e.g. chain was not deployed or some required parameter is not filled in chain element).

Notification has the next general structure:
1. **Service name** (optional) - name of the QIP service where the change was occurred.
2. **Message** - the main content of the notifications.
3. **Stack trace** (optional) - in case of technical error provides the ability to check root cause of the error and helps to find the problem root cause.
4. **Date** - date and time of the notification.

## Qubership Integration Platform Help

Qubership Integration Platform Help is embedded to QIP UI detailed guide about how to use QIP. For each UI page or QIP element there is particular help page.

To open QIP Help:

1. Authorize in QIP UI.
2. Navigate to some page or popup of some QIP element. Click icon <img src="docs/00__Overview/4__General_Functions/img/question-circle.svg" width="20" height="20"> (for the page - top-right side, for the QIP element popup - top left side near the element type in curly brackets). There will be opened appropriate page in new browser tab.
3. Use **pages navigation tree** on the left side of QIP Helper to go through the pages and **"Search Documentation... <img src="docs/00__Overview/4__General_Functions/img/search.svg" width="20" height="20">"** text field to find particular information by fulltext search.

## Export/Import

QIP provides an ability to export <img src="docs/00__Overview/4__General_Functions/img/cloud-download.svg" width="20" height="20"> and import <img src="docs/00__Overview/4__General_Functions/img/cloud-upload.svg" width="20" height="20"> different entities. There is a table with common formats for QIP artifacts:

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

## Filters

There is filtering functionality available for most of the tables, utilized across different pages. Click button <img src="docs/00__Overview/4__General_Functions/img/filter.svg" width="20" height="20"> and enter next data on filter pop-up:
- Column
- Condition
- Value

It is possible to specify multiple filtering conditions via "**Add filter**" button. When filters are applied, button "**Filter**" will have a small counter indicator, showing quantity of active filters. It is also possible to remove particular filter via <img src="docs/00__Overview/4__General_Functions/img/delete.svg" width="20" height="20"> button or remove all filters via "**Clear All**" button.

## Table Settings

Most of the tables in the system can be adjusted not only by extending/shrinking column size, but also by controlling each column's visibility and sequence. To do so, click gear button <img src="docs/00__Overview/4__General_Functions/img/filter.svg" width="20" height="20"> on top of the table and adjust properties accordingly. Some of the columns can't be hidden or moved - this is explained for exact columns in respective design articles.

## Switch between Blue and Green versions

In order to switch between Blue and Green configuration versions in QIP, simply use selector on top right of the screen. This selector is only visible on environments, properly configured for Blue/Green approach.
