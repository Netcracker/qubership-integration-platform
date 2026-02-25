# Domains [Web UI only]

<div style="border-left: 6px dashed #cc0000; padding: 10px">
This functionality is not available via the VS Code Extension.
</div>

## Description

---
Chain should be deployed at least on one domain to enable processing of integration flow. Engine domain is a K8S deployment, which has one or more engine pods, so chain, that is deployed on a particular domain correspondingly will be deployed on each Engine (pod) under particular domain.

Qubership Integration Platform is an engine domain orchestrator in inner namespace, hence for Operator's pod (Catalog or other microservice) there should be a service account automatically created in K8S. Through this account all engines are going to be managed.

Qubership Integration Platform provides view-only window where domain's information could be seen:
- Increasing the number of engine domains is available **only via deployment descriptor during the deployment** (not in runtime).
- **Scaling** (increase/decrease count of engines) is available for each domain independently via configuration on K8S side before installation.

>**Useful links:** <ul><li>[K8S Operator pattern](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/)</li><li>[K8S Java client](https://github.com/kubernetes-client/java)</li>  <li>[API Kubernetes](https://kubernetes.io/ru/docs/concepts/overview/kubernetes-api/)</li>  <li>[Pod Lifecycle](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)</li></ul>

## User Interface

---

### View Domains

**"Domains"** tab is intended to provide the ability for monitoring of currently working engine domains and tracking the information about which chains on which engine domains were deployed (with snapshot and deployment status). To adjust domain workload, Admin is able to redeploy particular chains or scale up/down (not from QIP UI) particular engine domain.

**Domain card** structure:
- **Domain** - name of the engine domain.
- **Version** - current build of Qubership Integration Platform.
- **Desired engines** - count of engines under domain.
- **Namespace** - K8S namespace.
- ![Plus](img/plus.svg) - expands/collapses the domain card.

### View Engines

To expand the domain tree and **see child engines**, click ![Plus](img/plus.svg) icon, available for specific domain card.

**Engine card** structure:
- **Engine** - engine name.
- **Pod address** - address of the Pod for Engine.
- **State** - state of Pod. Possible values:
	- 🟡 _Not Ready_
	- 🟢 _Ready_
- **Pod status** - status of the Pod. Possible values:
	- 🟡 _Pending_
	- 🟢 _Running_
	- 🔴 _Failed_
- ![Plus](img/plus.svg) - expands/collapses the engine card.

### Chain Deployment Card View

To expand the engine tree and see **chain deployments**, click ![Plus](img/plus.svg) icon, available for specific engine card.

**Chain deployment card**  structure:
- **Chain name** - name of the chain deployed on current engine.
- **Snapshot Name** - name of the deployed [Snapshot](../../01__Chains/2__Snapshots/snapshots.md).
- **Status** - deployment status. Detailed information is available in [Deployments page](../../01__Chains/3__Deployments/deployments.md).
