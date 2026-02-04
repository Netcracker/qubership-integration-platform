# Live Exchanges (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>
## Description

---
Live Exchanges provides real-time monitoring and management of active (unfinished) exchanges within integration chains. Unfinished exchanges may block processing threads, degrading system performance and resource availability, so the tab helps to:
- **Identify resource-intensive exchanges**: detect hung/stuck exchanges consuming system resources.
- **Terminate unwanted processes**: forcefully stop non-productive exchanges.

## User Interface

---
### Live Exchanges Table View

The table displays a list of active exchanges, aggregated by session identifier. The following columns and elements are available in the table:
- **Session Id** - generated session UUID. If session logs are enabled in the UI, the value will be clickable, allowing users to view detailed information about the session.
- **Chain** - reference to the related chain currently being processed.
- **Session Duration** - total session duration in milliseconds. The table can be sorted by this parameter.
- **Exchange Duration** - duration of the specific exchange (sync/async thread) within the session in milliseconds.
- **Session Started** - start datetime of the session.
- **Main Thread** - a checkbox indicating whether the exchange is running on the main thread (checked) or asynchronous thread (unchecked)
- **Pod IP** - the IP address of the engine pod hosting the session, displayed in parentheses (e.g., 10.131.170.120)
- <img src="docs/03__Admin_Tools/8__Live_Exchanges/img/stop.svg" width="20" height="20"> - button (located at the extreme right of each row) allows users to terminate live exchange, halting its execution immediately.

At the right bottom it is available only one action - <img src="docs/03__Admin_Tools/8__Live_Exchanges/img/redo.svg" width="20" height="20"> - refresh the table.


### Control panel

The control panel is positioned at the top right of the table and provides the following capabilities:
- Placeholder **"exchanges per engine"** - allows user to adjust the number of exchanges displayed per engine instance.
- <img src="docs/03__Admin_Tools/8__Live_Exchanges/img/setting.svg" width="20" height="20"> - opens pop-up with table properties that allows to adjust visibility and sequence of columns except **Session Id**.


### Terminate Exchange

To terminate a live exchange, locate the relevant row in the Live Exchanges table, click  <img src="docs/03__Admin_Tools/8__Live_Exchanges/img/stop.svg" width="20" height="20"> ("Terminate" button) at the far right, and then click the "Yes" button to confirm your choice.

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Note:</b>
<br>Once the "Terminate" button is clicked, termination of exchange will occur only after ongoing chain element will be executed (not starting execution of next element). Ensure the exchange is no longer required before initiating termination.</div>