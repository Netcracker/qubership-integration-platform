# Sessions (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>
## Description

---
Session represents chain processing step by step. Chain session is being created when the chain was triggered successfully.

### Async Request & Callback Linkage in Sessions

There are technical specifics available for asynchronous integrations, that can cause losing linkage between request and callback. Due to the fact that callback for such integration type is being provided with a significant delay, it leads to the challenges related to identifying of the original request. To deal with this challenge, Qubership Integration Platform links request and callbacks with correlation id. This also gives an ability to group up sessions with the same id on the table **"Sessions"**, available for each chain.

Correlation id can be passed by the following chain modules:
- [HTTP Trigger](docs/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md)
- [Service Call](docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call.md)
- [HTTP Sender](docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/4__HTTP_Sender/http_sender.md)
- [GraphQL Sender](docs/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/7__GraphQL_Sender/graphql_sender.md)

In case of asynchronous request during chain design time user have to configure correlation id source and its name (key).

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b><br>
Correlation id will be added to the list of camel exchange properties in Runtime.
</div>

## User Interface

---

### Sessions Table View

Table contains current chain's sessions, aggregated by correlation identifier, if it is available, and by parent session identifier for retry cases. Next columns and elements are available for the table:

- **ID** - generated session UUID. The parameter value is clickable.
- **Status** - status of the session. Possible values:
	- <span style="color:green; font-size: 30px;">&#8226</span> _**Completed Normally**_ - session has been completed without issues. 
	- <span style="color:red; font-size: 30px;">&#8226</span> _**Completed with Errors**_ - session failed. Error details are available on tab "Errors" under failed session element.
	- <span style="color:yellow; font-size: 30px;">&#8226</span> _**Completed With Warnings**_ - session has been completed with warnings or exceptions, successfully handled within proper elements, such as try-catch-finally, etc. Error details are also available on tab "Errors" under failed session element.
	- <span style="color:blue; font-size: 30px;">&#8226</span> _**In Progress**_ - session is in progress. Finalized status will be available in some time.
	- <span style="color:gray; font-size: 30px;">&#8226</span> _**Cancelled Or Unknown**_ - session processing has been interrupted by chain itself. For example, this status might indicate that one of the Split element branches failed while this element has option "Stop On Exception" selected, which caused interruption of all other branches.
* **Start Time** - start datetime of the session.
- **Finish Time** - finish datetime of the session
- **Session level** - shows level of logging for specific session.
- **Duration** - shows 2 time values: 1st one is a duration of synchronous main session thread, 2nd one (in brackets) is summary duration of all synchronous and asynchronous threads. In case value is more than 1 second, it will be displayed in seconds, otherwise in milliseconds.
- **Snapshot** - snapshot version of deployment.
    <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
    <b>After manual <a href="doc/01__Chains/2__Snapshots/snapshots.md">snapshot</a> renaming, current parameter's value will not be updated automatically (even for the new sessions)</b>. To see updated snapshot name, it is required to redeploy the chain.
    </div>
- **Engine** - name of the session engine domain with pod address (without port) in parentheses.

The actions could be done under sessions are:
* <img src="docs/01__Chains/4__Sessions/img/delete.svg" width="20" height="20"> - Delete selected sessions.
* <img src="docs/01__Chains/4__Sessions/img/cloud-download.svg" width="20" height="20"> - Export selected sessions.
* <img src="docs/01__Chains/4__Sessions/img/redo.svg" width="20" height="20"> - Retry selected sessions.

### Session view

Click **Session ID value** in the respective row of sessions table to see the list of logged chain's elements, that are related to the same session. To expand or collapse compound element simply click the element itself or use <img src="docs/01__Chains/4__Sessions/img/plus.svg" width="20" height="20">/ <img src="docs/01__Chains/4__Sessions/img/minus.svg" width="20" height="20"> button to expand/collapse all elements at once. Next columns and elements are available for the table:
- **Element Name** - name of the element, participated in the processing. Click <img src="docs/01__Chains/4__Sessions/img/plus.svg" width="20" height="20"> to open chain element and respective tab in the configuration graph. Reference to the [Chain Call](docs/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md) will open related configuration graph instead of chain element.
- **Status** - processing status.
- **Duration** - processing duration in milliseconds.
- **Start Time** - processing start datetime.
- **Finish Time** - processing end datetime.
- **Element Type** - type of the element, according to the library of elements.

### Session's element view

Under each element it is possible to get additional information including its state before and after it has been executed. Click on <img src="docs/01__Chains/4__Sessions/img/plus.svg" width="20" height="20"> to expand.

Next information is available, when element's name clicked and window with its details presented:
- **Previous/Next** buttons - navigation buttons, that allow to open previous or next session element.
- **Body** tab - contains before/after states of request body, participated in the processing.
- **Headers** tab - contains the list of headers and their before/after values. Slider **"Only modified"** filters out unmodified headers.
    <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
    For <b>HTTP Sender</b> and <b>Service Call</b> header <b>"CamelHttpUri"</b> will contain full URI, with resource and query parameters in it.
    </div>
- **Exchange properties** tab - contains list of exchange properties. There are specific properties, available for failed elements in sessions, please refer to the [Building Logic Around Failed Elements](docs/00__Overview/6__Building_Logic_Around_Failed_Elements/failed_elements_logic.md) article for more details.
- **Technical context** tab - contains the list of context headers, that has been received by the chain.

There is also "**View diff**" switch, available for "**Headers**", "**Exchange properties**" and "**Technical context**" tabs, that could be used to only show records that were modified during the processing.

### Retry Failed Session
To retry failed session, find it in the table and click retry <img src="docs/01__Chains/4__Sessions/img/redo.svg" width="20" height="20"> button. Retry can only be performed if at least one [[docs/01__Chains/1__Graph/1__QIP_Elements_Library/3__Composite_Triggers/1__Checkpoint/checkpoint|Checkpoint]] element was configured in the chain at the time of session failure.

### Export Sessions
To export session(s) to a **json** file, please mark all required sessions via checkbox and click **Export** button <img src="docs/01__Chains/4__Sessions/img/cloud-download.svg" width="20" height="20">. To limit the amount of exported data, **Export** button is disabled when all sessions are marked via global checkbox on top of the table. Export is also possible from sessions details window, where all session's steps are presented.