# Sessions (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>
## Description

---
In order to have a full picture of processed sessions, the Qubership Integration Platform provides a specialized window **"Sessions"**. By operating with the window, user or admin are able to view every logged session and search for the specific one.

## User Interface

---
### Sessions Table View

Table contains sessions, aggregated by correlation identifier, if it is available, and by parent session identifier for retry cases. Next columns and elements are available for the table:
- **ID** - generated session UUID. The parameter value is clickable.
- **Chain** - reference to the related chain.
- **Status** - status of the session completing. Possible values:
	- <span style="color:green; font-size: 30px;">&#8226</span> _**Completed Normally**_ - session has been completed without issues.
	- <span style="color:red; font-size: 30px;">&#8226</span> _**Completed with Errors**_ - session failed. Error details are available on tab "Errors" under failed session element.
	- <span style="color:yellow; font-size: 30px;">&#8226</span> _**Completed With Warnings**_ - session has been completed with warnings or exceptions, successfully handled within proper elements, such as try-catch-finally, etc. Error details are also available on tab "Errors" under failed session element.
	- <span style="color:blue; font-size: 30px;">&#8226</span> _**In Progress**_ - session is in progress. Finalized status will be available in some time.
	- <span style="color:gray; font-size: 30px;">&#8226</span> _**Cancelled Or Unknown**_ - session processing has been interrupted by chain itself. For example, this status might indicate that one of the Split element branches failed while this element has option "Stop On Exception" selected, which caused interruption of all other branches.
- **Start Time** - start datetime of the session.
- **Finish Time** - finish datetime of the session.
- **Session level** - shows level of logging for specific session.
- **Duration** - shows 2 time values: 1st one is a duration of synchronous main session thread, 2nd one (in brackets) is summary duration of all synchronous and asynchronous threads. In case value is more than 1 second, it will be displayed in seconds, otherwise in milliseconds.
- **Snapshot** - snapshot version of deployment.
- **Engine** - name of the session engine domain with pod address (without port) in parentheses.
- **Control panel** - panel, placed on the right bottom marked with <img src="docs/03__Admin_Tools/5__Sessions/img/more.svg" width="20" height="20"> of the table. Provides next capabilities:
	- **Search field** - search box, provides ability to find particular session(s) by body field name, body field value, header name or header value.
    <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
    When searching for long or complex entity name, please consider specifying its <b>full name</b> or <b>first part of the name</b> for proper search result.
    </div>
	* <img src="docs/03__Admin_Tools/5__Sessions/img/delete.svg" width="20" height="20"> - deletes selected session(s).
	- <img src="docs/03__Admin_Tools/5__Sessions/img/cloud-download.svg" width="20" height="20"> - exports selected sessions.
	- <img src="docs/03__Admin_Tools/5__Sessions/img/cloud-upload.svg" width="20" height="20"> - opens pop-up for session import.
	- <img src="docs/03__Admin_Tools/5__Sessions/img/redo.svg" width="20" height="20"> - refreshes session table.
<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b>
<br>Imported sessions will be highlighted in the table. For such sessions, references to the chain and chain elements won't be available.</div>

Additionally at the top of the table there is search box providing ability to find particular session(s) by body field name, body field value, header name or header value.

### Session View

Click **Session ID value** in the respective row of sessions table to see the list of logged chain's elements, that are related to the same session. To expand or collapse compound element simply click the element itself or use <img src="docs/03__Admin_Tools/5__Sessions/img/plus.svg" width="20" height="20"> button to expand/collapse all elements at once. Next columns and elements are available for the table:
- **Element Name** - name of the element, participated in the processing. Click <img src="docs/03__Admin_Tools/5__Sessions/img/link.svg" width="20" height="20"> to open chain element and respective tab in the configuration graph. Reference to the [Chain Call](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md) will open related configuration graph instead of chain element.
- **Status** - processing status.
- **Duration** - processing duration in milliseconds.
- **Start Time** - processing start datetime.
- **Finish Time** - processing end datetime.
- **Element Type** - type of the element, according to the library of elements.

Only one available actions is to Export session <img src="docs/03__Admin_Tools/5__Sessions/img/cloud-download.svg" width="20" height="20">.

To go back, click "**To Sessions**" button or use "breadcrumb" navigation element.

### Session's element view

Click element's name to open additional window with detailed element's information, including its state before and after it has been executed. For convenient navigation between elements, use "**Next**" and "**Previous**" buttons.
Next information is available, when element's name clicked and window with its details presented:
- **Name** - full name of the session element or its sub-operation. Click <img src="docs/03__Admin_Tools/5__Sessions/img/link.svg" width="20" height="20"> to open chain element and respective tab in the configuration graph. Reference to the [Chain Call](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md) will open related configuration graph instead of chain element.
- **Previous/Next** buttons - navigation buttons, that allow to open previous or next session element.
- **Body** tab - contains before/after states of request body, participated in the processing.
- **Headers** tab - contains the list of headers and their before/after values. Slider **"Only modified"** filters out unmodified headers.
    <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
    For <b>HTTP Sender</b> and <b>Service Call</b> header <b>"CamelHttpUri"</b> will contain full URI, with resource and query parameters in it.
    </div>
- **Exchange properties** tab - contains list of exchange properties. There are specific properties, available for failed elements in sessions, please refer to the [Building Logic Around Failed Elements](docs/00__Overview/6__Building_Logic_Around_Failed_Elements/failed_elements_logic.md) article for more details.
- **Technical context** tab - contains the list of context headers, that has been received by the chain.

There is also "**Only modified**" switch, available for "**Headers**", "**Exchange properties**" and "**Technical context**" tabs, that could be used to only show records that were modified during the processing.

The following operations are available via <img src="docs/03__Admin_Tools/5__Sessions/img/more.svg" width="20" height="20"> button located in the right bottom:
* <img src="docs/03__Admin_Tools/5__Sessions/img/redo.svg" width="20" height="20"> - Retry selected sessions.
* <img src="docs/03__Admin_Tools/5__Sessions/img/cloud-download.svg" width="20" height="20"> - Export selected sessions.
* <img src="docs/03__Admin_Tools/5__Sessions/img/delete.svg" width="20" height="20"> - Delete selected sessions.

### Retry Session

To retry any session, find it in the table and click retry <img src="docs/03__Admin_Tools/5__Sessions/img/redo.svg" width="20" height="20"> button. Retry can only be performed if at least one [[docs/01__Chains/1__Graph/1__QIP_Elements_Library/3__Composite_Triggers/1__Checkpoint/checkpoint|Checkpoint]] element was configured in the chain at the time of session failure.

### Export Session(s)

To export session(s) to a **json** file, please mark all required sessions via checkbox and click **Export** button <img src="docs/03__Admin_Tools/5__Sessions/img/cloud-download.svg" width="20" height="20">. To limit the amount of exported data, **Export** button is disabled when all sessions are marked via global checkbox on top of the table. Export is also possible from sessions details window, where all session's steps are presented.

### Import Session(s)
To import session(s), please click <img src="docs/03__Admin_Tools/5__Sessions/img/cloud-upload.svg" width="20" height="20">. Imported sessions will be available for view in read-only mode without ability to navigate to the exact elements.

### Delete Session(s)

To delete session(s), please, mark all required sessions from the table and click <img src="docs/03__Admin_Tools/5__Sessions/img/delete.svg" width="20" height="20">. 