# Testing

> ⛔️ This functionality is not available via the VS Code Extension.

## Description

---
The **Testing** group is the cross-chain view of the testing feature. Where the chain-level [Testing](../../01__Chains/8__Testing/testing.md) tab shows the test cases and endpoint mocks of one chain, this group shows them for every chain at once, and it adds the **Test Runs** list, which exists nowhere else.

A **test run** is a set of test cases started together. It is created whenever test cases are run - from a chain, or from the cross-chain list here - and it records one **test case run** per test case it holds. Because the cross-chain list is not limited to a single chain, a run started from it can hold test cases of several different chains.

> ℹ️ **Note:** The **Testing** group appears only where the testing service is deployed, reachable and reporting a non-production mode. Non-production mode is opt-in: a testing service that is not configured for it reports production, so a freshly deployed service leaves the group hidden until an operator switches the mode. On a production installation the group is hidden, the chain-level **Testing** tab is hidden with it, and a direct link to a testing address lands on the "not found" page. Testing is a development and verification feature; it is not intended for production installations.

## User Interface

---
Click the **Admin Tools** main tab and expand **Testing** in the left menu. It carries three entries:

- **Test Cases** - the test cases of every chain.
- **Endpoint Mocks** - the endpoint mocks of every chain.
- **Test Runs** - the runs and, through them, their test case runs and validation errors.

Every table here works the way the chain-level ones do: filters, a search field and column sorting all applied by the service, resizable columns, column settings that survive a page reload, rows loaded page by page as the table is scrolled, and a **Select all that match the filters** option in the selection menu while rows are still unloaded. That option covers the current search text as well as the filters, and changing a filter, the search text or the sort clears the selection. Clicking a row opens a read-only details side panel, except where the cell is a link.

### Test Cases Table View
The same table as on the chain tab, with one column added and two toolbar differences.

- **Chain** - the chain the test case belongs to, a clickable reference to it. This column appears only here, since the chain-level list is already limited to one chain.
- **Import test cases** - available only here.
- **Create a test case** - **not** available here. Creating a test case needs a chain to pick a trigger from, so it is done from the chain tab.

The remaining columns - **Name**, **Description**, **Element**, **Enabled**, **Readiness**, **Rules**, **Active Rules** and the audit fields - and the remaining toolbar actions - **Refresh**, **Run selected test cases**, **Export selected test cases** and **Delete selected test cases** - are described in [Testing](../../01__Chains/8__Testing/testing.md), including the overcounted rule columns and the unsortable **Readiness**.

> ℹ️ **Note:** A test case opened from this list is **read-only**. Its tabs, its fields and its validation rules table are all disabled, and there is no save toolbar. To change a test case, open it from the **Testing** tab of its chain.

### Endpoint Mocks Table View
The cross-chain list of endpoint mocks, with the same **Chain** column and the same asymmetry: **Import endpoint mocks** is available here, **Create an endpoint mock** is not. Mocks opened from this list are read-only as well.

### Import Test Cases or Endpoint Mocks
Click **Import test cases** or **Import endpoint mocks**. The dialog works in two steps:

1. Drag one or more **.zip** archives into the upload area, or click it and select them. Each archive holds one exported entity per file. Click **Import**.
2. The result table appears, with one row per file:
   - **Archive** - name of the archive the file came from.
   - **File Name** - name of the file inside it.
   - **Id** and **Name** - identifier and name of the imported entity.
   - **Result** - **_Created_**, **_Updated_** or **_Error_**. An entity whose identifier already exists is updated in place.
   - **Error** - the failure message, for rows that failed.

An archive that cannot be read at all is reported as a single **_Error_** row rather than as a failed request. The list behind the dialog is refreshed only when something was actually created or updated.

### Test Runs Table View
The table lists the runs. The following columns are available:

- **Id** - identifier of the run, a clickable reference to its test case runs.
- **Status** - aggregate status, derived from the case runs the run holds. The first of these rules that applies wins:
  - 🟡 **_Canceled_** - at least one case run was canceled.
  - 🟢 **_Finished_** - no case run was canceled, and at least one has finished or was skipped.
  - 🔵 **_Running_** - every case run is still queued or in progress.
- **Start** - start datetime of the run.
- **Finish** - finish datetime of the run.
- **Test Cases** - number of test cases the run holds.
- **Test Cases With Errors** - number of those test cases whose case run recorded at least one validation error. It counts failing **test cases**, not the individual errors they recorded.
- **Created At**, **Created By**, **Updated At**, **Updated By** - audit fields, hidden by default and enabled through the column settings. The updated pair cannot be sorted on.

A run therefore reads **_Finished_** as soon as its first case run finishes, while the rest are still queued, and it reads **_Canceled_** when a single one of its case runs was canceled. Open the run to read the status of each case run. **_Pending_** and **_Skipped_** never reach a run, so the **Status** filter offers only the three values above; the case-run list offers all five.

**Control panel**

- **Refresh** - reloads the table.
- **Restart selected test runs** - creates a new run over the same test cases and refreshes the table, since the new run lands in it.
- **Cancel selected test runs** - cancels the checked runs.
- **Export selected test runs** - downloads the checked runs as a CSV file.
- **Delete selected test runs** - deletes the checked runs after a confirmation. A run is deleted whatever its state, and its test case runs go with it.

> ℹ️ **Note:** Canceling reaches only the case runs that have not started yet. A case run already in progress keeps running, and the confirmation says so.

Clicking a row opens the **Test Run Details** side panel.

### Test Case Runs and Validation Errors
Click the **Id** of a run to open its test case runs. The table matches the chain-level one, except that it shows a **Chain** column instead of a **Test Run** column: a run may hold cases of several chains, while the chain-level list is already limited to one.

From there, the **Id** of a case run, or an **Errors** count above zero, opens its validation errors. A zero count is plain text. Both tables, their columns and their toolbars are described in [Testing](../../01__Chains/8__Testing/testing.md).

### Assemble a Run From Several Chains
The cross-chain **Test Cases** list is what makes a run over several chains possible:

1. Open **Admin Tools** › **Testing** › **Test Cases**.
2. Filter or search the list, then check the test cases to include. They may belong to any number of chains - nothing here limits the selection to one. With **Select all that match the filters** active, the run also covers rows that were never loaded into the page.
3. Click **Run selected test cases**.
4. A notification confirms that the run has started and links to it in the **Test Runs** list.

The same notification appears when test cases are run from a chain, but there it only names the run identifier. The runs list exists only under **Admin Tools**, which chain rights alone do not open, so the chain-level notification does not link into it.

## Execution Order

---
Test case runs are executed by the testing service, which processes several of them at a time:

- **Test cases inside one run execute sequentially.** The service hands out at most one case run per run, in the order the run recorded them, and takes the next one only when the previous one has finished. A run therefore never calls two of its own chains at once.
- **Separate runs execute in parallel.** Different runs progress at the same time, up to the number of concurrent case runs the service is configured for.

> ℹ️ **Note:** Runs are isolated from each other, but test cases are not. The sequential guarantee holds **within** a run, keyed on the run itself. If the same test case is placed in two different runs and both are executing, its two case runs can therefore execute at the same time, and the chain is called twice concurrently. Keep a test case that cannot tolerate that in one run at a time.

A case run that stops making progress - because the worker that held it went away - is returned to the queue and executed again, and the validation errors of the abandoned attempt are discarded with it.

## Permissions

---
Every screen in this group is gated by the **Admin Tools** rights: `read` to open a list, a details panel or an editor, `update` to delete, `execute` to run, restart or cancel, `import` to import and `export` to export. The chain-level **Testing** tab asks for the same rights on the chain instead, and the two sets are independent - full rights on a chain do not open this group. The table is in [Testing](../../01__Chains/8__Testing/testing.md).

## Constraints

---

- Test cases and endpoint mocks cannot be created from this section, and the editors reached from it are read-only. Both are done from the **Testing** tab of the chain they belong to.
- Cancel reaches only the case runs that have not started yet, on both the run list and the case-run list.
- Running an empty selection does nothing: the service refuses an empty run rather than starting one over every test case.
