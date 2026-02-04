# Deployments (Web UI only)

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>
## Description

---

To make a chain available for usage, its snapshot shall be deployed on the specific Engine via "**Deployments**" tab. For the chain only one deployment instance is applicable, but chain can be deployed on different engine domains.

## User Interface

---

### View Deployments Tab

Under the chain it is possible to navigate on "**Deployments**" tab. The following information about deployments are available:
* **Snapshot**: Displays the deployed version (e.g., _V1.2_ ).
- **Domain**: Shows the selected domain for deployment.
- **Status**: IP address indicating deployment success or failure via color-coded labels:
    - <span style="color:gray; font-size: 30px;">&#8226</span> **_Progressing_** - deployment is in progress. There are engines which haven't received finalized status yet.
    - <span style="color:green; font-size: 30px;">&#8226</span> **_Deployed_** - chain data has been successfully deployed on all requested engines.
    - <span style="color:red; font-size: 30px;">&#8226</span> **_Failed_** - deployment failed on one or multiple engines. Error details are available by hovering the mouse over engine's status.
- **Created By**: The user who initiated the deployment.
- **Created At**: The exact date and time of deployment.
- **Actions**: The only available action is **Delete Deployment** marked with <img src="docs/01__Chains/3__Deployments/img/delete.svg" width="20" height="20">, which removes the deployment record (note: this does not affect the deployed chain itself).

### Create Deployment

Click **"Create deployment"** button marked with <img src="docs/01__Chains/3__Deployments/img/plus.svg" width="20" height="20">. The window for setting deployment parameters will appear. Fill in the following deployment parameters and click **"Deploy"**:
- **Domain** - choose the engine domain for deployment from list of existing domains.
- **Snapshot** - version of chain you want to deploy.

    <div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
    <b>After manual <a href="doc/01__Chains/2__Snapshots/snapshots.md">snapshot</a> renaming, current parameter's value will not be changed automatically.</b> To change snapshot name on deployment, it is required to <b>redeploy</b> the chain.
    </div>

### Delete Deployment
If you want to **delete deployment**, click <img src="docs/01__Chains/3__Deployments/img/delete.svg" width="20" height="20"> on the right side of deployment.


<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Notes:</b><br><ul>
  <li>QIP user can do chain <b>redeploy</b> - specific maintenance operation for Production usage in high load Chains, that gracefully stop chain, process all sessions from queue, change required chain settings and start it again.</li>
  <li>User will be notified if deployment removal fails due to inability to delete MaaS entities. Problematic MaaS entity will be also mentioned in notification.</li>
</ul></div>