# Deployments
> ⛔️ This functionality is not available via the VS Code Extension.

## Description

---

To make a chain available for usage, its snapshot shall be deployed on the specific Engine via "**Deployments**" tab. For the chain only one deployment instance is applicable, but chain can be deployed on different engine domains.

## User Interface

---

### View Deployments Tab
Under the chain it is possible to navigate on "**Deployments**" tab. The following information about deployments are available:
- **Snapshot**: Displays the deployed version (e.g., _V1.2_ ).
- **Domain**: Shows the selected domain for deployment.
- **Status**: IP address indicating deployment success or failure via color-coded labels:
  - ![sync-blue](img/sync-blue.svg) **_Progressing_** - deployment is in progress. There are engines which haven't received finalized status yet.
  - ![check-circle-green](img/check-circle-green.svg) **_Deployed_** - chain data has been successfully deployed on all requested engines.
  - ![close-circle-red](img/close-circle-red.svg) **_Failed_** - deployment failed on one or multiple engines. Error details are available by hovering the mouse over engine's status.
  - ![exclamation-circle-warn](img/exclamation-circle-warn.svg) **_Warning_** - deployment completed with warnings. Additional details are available by hovering over the corresponding engine's status.
  - ![minus-circle](img/minus-circle.svg) **_Removed_** - deployment has been removed and is no longer available on the selected engine(s).
  - ![clock-circle](img/clock-circle.svg) **_Draft_** - deployment is saved as a draft and has not been deployed to any engine yet.
- **Created By**: The user who initiated the deployment.
- **Created At**: The exact date and time of deployment.


### Create Deployment
Click **"Create deployment"** button marked with ![plus](img/plus.svg). The window for setting deployment parameters will appear. Fill in the following deployment parameters and click **"Deploy"**:
- **Domains** - choose the engine domain for deployment from list of existing domains.
- **Snapshot** - version of chain you want to deploy.

> ℹ️ **Note**: **After manual [snapshot](../2__Snapshots/snapshots.md) renaming, current parameter's value will not be changed automatically.** To change snapshot name on deployment, it is required to **redeploy** the chain.

### Delete Deployment
If you want to **delete deployment**, click ![delete](img/delete.svg) on the right side of deployment.

> ℹ️ **Notes:**
>
> - QIP user can do chain **redeploy** - specific maintenance operation for Production usage in high load Chains, that gracefully stop chain, process all sessions from queue, change required chain settings and start it again.
> - User will be notified if deployment removal fails due to inability to delete MaaS entities. Problematic MaaS entity will be also mentioned in notification.
