# Access Control

> ⛔️ This functionality is not available via the VS Code Extension.

## Description

---
This tab allows user to easily and quickly control access to every chain endpoint
(exposed by particular chain via [HTTP Trigger](../../01__Chains/1__Graph/1__Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md))
by managing the access control type and respective configurations.
Without opening each separate chain, user could use single **"Access Control"** page to view access control configurations,
change access control type (NONE <-> RBAC), change list of roles,
and apply changes via redeploying the chains, reusing previous deployment settings (logging, engines, etc.)

## User Interface

---
### View Table
Tab "Access Control" contains a table, representing the unique endpoint, that is exposed by particular chain via [HTTP Trigger](../../01__Chains/1__Graph/1__Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md), with next available columns and elements:
- **Endpoint** - specifies the endpoint, that is configured in HTTP Trigger for particular chain. When clicked, opens additional menu with all endpoint details.
- **Type** - Type of the endpoint (External/Internal).
- **Access Control Type** - specifies the type of access configured for the HTTP Trigger (_RBAC/ABAC/NONE_).
- **Roles** - list of roles, configured for HTTP Trigger. Applicable to display list of roles (as blue chips) only for _RBAC_ access control type; for _NONE/ABAC_ - displayed as hyphen (-).
- **Attributes** - set of applied parameters for attribute based access control endpoint (chain) configuration. Applicable to display parameters only for _ABAC_ access control type; for _NONE/RBAC_ - displayed as hyphen (-).
    > ℹ️ **Note:** Click **"Details"** reference on the respective row to open popover with ABAC parameters (_Operation_, _Resource Type_ and _Resource/Resource Map_) in read-only mode.
- **Chain** - name of the chain, that exposes the endpoint. Navigates to the chain by click.
- **Chain Status** - current status of the related chain.

Next capabilities are available above the table in the right top:
- **Search access control** - search box, provides ability to find respective data in the table.
- ![setting](img/setting.svg) - opens column settings.
- ![carry-out](img/carry-out.svg) - selects unsaved chains.
- ![send](img/send.svg) -  redeploys chain(s) with unsaved changes.
- ![plus](img/plus.svg) - opens pop-up that allows to add new role(s) to desired endpoints.
- ![minus](img/minus.svg) - opens pop-up that allows to remove the role(s) from desired endpoints.
- ![redo](img/redo.svg)  - refreshes the table.

### View Details
Click the specific table line, that corresponds to the endpoint in order to open additional "Endpoint Details" window. Besides the info, available in the table, this window additionally shows:
- **Unsaved Changes label** - label that indicates unsaved changes available for chain.
- **Entity Id** – element id that holds the endpoint (basically, element id of the HTTP Trigger).
- **Entity Name** – element name that holds the endpoint (basically, it is a name of the HTTP Trigger).
- **Chain Id** – identifier of the chain, that contains the http trigger.
- **Entity Update Date** – last update date of the endpoint (it is change date of HTTP Trigger).

### Add Roles
Mark required endpoint(s) with the checkbox and click ![plus](img/plus.svg) button to open an extra window, that helps to specify new role(s) in the respective box. There are following rules applied within the operation according to "Access Control Type" value:
- for _RBAC_ - added roles will be appended to the existing list, that already configured for selected endpoints.
- for _NONE_ - "Access Control Type" will be transitioned to "RBAC" (new value) with the applied list of roles.
- for _ABAC_ - operation will be prohibited and respective notification will occur if at least one row having this access control type is selected.

Use respective checkbox on the **"Add Roles"** window to apply the changes by redeploying related chain(s).

### Remove Roles
Mark required endpoint(s) with the checkbox and click ![minus](img/minus.svg) button to open an extra window, that will show full list of roles available for selected endpoints. There are following rules applied within the operation according to "Access Control Type" value:
- for _RBAC_ - removing the role from the box (and then saving the changes) will remove it from any endpoint that has been selected. If there are no assigned roles left for endpoint (chain), "Access Control Type" will be transitioned to "NONE".
- for _NONE_ - nothing is changed.
- for _ABAC_ - operation will be prohibited and respective notification will occur if at least one row having this access control type is selected.

Use respective checkbox on the **"Remove Roles"** window to apply the changes by redeploying related chain(s).

### Redeploy
Chain must be redeployed from **"Access Control"** or standard **"Deployment"** tab in order to apply the changes.
When working with **"Access Control"** table, only colored records that contain not deployed changes
and related to **Deployed** chains are available for redeployment (in other cases, respective button is disabled).
To quickly select all colored rows, simply click colored **"Select Unsaved Chains"**
marked with icon ![carry-out](img/carry-out.svg) on top of the table.


### Refresh
To update actual list of all endpoints and related data, use ![redo](img/redo.svg) button.
