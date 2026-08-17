# Domains

> ⛔️ This functionality is not available via the VS Code Extension.

## Description

---
Chain should be deployed at least on one domain to enable processing of integration flow. Engine domain is a K8S deployment, which has one or more engine pods, so chain, that is deployed on a particular domain correspondingly will be deployed on each Engine (pod) under particular domain.

Qubership Integration Platform is an engine domain orchestrator in inner namespace, hence for Operator's pod (Catalog or other microservice) there should be a service account automatically created in K8S. Through this account all engines are going to be managed.

Engine domains are of two types:
- **Classic** - a domain with engine pods pre-configured via the deployment descriptor.
- **Micro** - a domain created on demand: deploying chains under a domain name that does not exist yet provisions the domain as a Camel K custom resource running only the deployed chain(s). A **`micro`** tag marks this domain type wherever a domain is shown, and micro domains can be deleted directly from the **"Domains"** tab.

> ℹ️ **Note**: Availability of the **Classic** and **Micro** domain types is controlled independently via configuration of specific environment parameters (for the correct parameter names, contact your system administrator). If a domain type is disabled, no domains of that type are available for deployment.

Qubership Integration Platform provides view-only window where domain's information could be seen:
- Increasing the number of **Classic** engine domains is available **only via deployment descriptor during the deployment** (not in runtime). **Micro** domains, in contrast, are created directly from the deployment dialogs — see [Deployments](../../01__Chains/3__Deployments/deployments.md).
- **Scaling** (increase/decrease count of engines) is available for each domain independently via configuration on K8S side before installation.

> **Useful links:**
>
> - [K8S Operator pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/)
> - [K8S Java client](https://github.com/kubernetes-client/java)
> - [API Kubernetes](https://kubernetes.io/ru/docs/concepts/overview/kubernetes-api/)
> - [Pod Lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)

## User Interface

---

### View Domains
**"Domains"** tab is intended to provide the ability for monitoring of currently working engine domains and tracking the information about which chains on which engine domains were deployed (with snapshot and deployment status). To adjust domain workload, Admin is able to redeploy particular chains or scale up/down (not from <ins>Web UI</ins>) particular engine domain.

**Domain table** structure:
- **Domain** - name of the engine domain. Domains of **Micro** type display a **`micro`** tag and a ![delete](img/delete.svg) button next to the name; click the button to delete the micro domain.
- **Version** - current build of Qubership Integration Platform.
- **Desired engines** - count of engines under domain.
- **Namespace** - K8S namespace.
- ![20](img/down.svg) - expands/collapses the domain.

**Control panel**

At the top of the table the following options are available:
  - **Search domains** - search box, provides ability to find particular domain(s).
  - ![Table settings icon](img/setting.svg) - opens pop-up with table properties that allows adjusting visibility and order of the columns.

### View Engines
To expand the domain tree and **see child engines**, click ![20](img/down.svg) icon, available for specific domain card.

**Engine table** structure:
- **Engine** - engine name.
- **Pod address** - address of the Pod for Engine.
- **State** - state of Pod. Possible values:
  - 🟡 _Not Ready_
  - 🟢 _Ready_
- **Pod status** - status of the Pod. Possible values:
  - 🟡 _Pending_
  - 🟢 _Running_
  - 🔴 _Failed_
- ![20](img/down.svg) - expands/collapses the engine card.

### Chain Deployment Card View
To expand the engine tree and see **chain deployments**, click ![20](img/down.svg) icon, available for specific engine card.

**Chain deployment card**  structure:
- **Chain name** - name of the chain deployed on current engine.
- **Snapshot Name** - name of the deployed [Snapshot](../../01__Chains/2__Snapshots/snapshots.md).
- **Status** - deployment status. Detailed information is available in [Deployments page](../../01__Chains/3__Deployments/deployments.md).
