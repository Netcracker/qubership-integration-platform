# Domains (Web UI only)

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

<div style="background-color: #e7f3fe; border-left: 6px solid #2196F3; padding: 10px">
<b>Useful links:</b><br>
<ul>
  <li><a href="https://kubernetes.io/docs/concepts/extend-kubernetes/operator/">K8S Operator pattern</a></li>
  <li><a href="https://github.com/kubernetes-client/java">K8S Java client</a></li>
  <li><a href="https://kubernetes.io/ru/docs/concepts/overview/kubernetes-api/">API Kubernetes</a></li>
  <li><a href="https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/">Pod Lifecycle</a></li></ul>
</div>

## User Interface

---

### View Domains

**"Domains"** tab is intended to provide the ability for monitoring of currently working engine domains and tracking the information about which chains on which engine domains were deployed (with snapshot and deployment status). To adjust domain workload, Admin is able to redeploy particular chains or scale up/down (not from QIP UI) particular engine domain.

**Domain card** structure:
- **Domain** - name of the engine domain.
- **Version** - current build of Qubership Integration Platform.
- **Desired engines** - count of engines under domain.
- **Namespace** - K8S namespace.
- <img src="docs/03__Admin_Tools/1__Domains/img/plus.svg" width="20" height="20"> - expands/collapses the domain card.

### View Engines

To expand the domain tree and **see child engines**, click <img src="docs/03__Admin_Tools/1__Domains/img/plus.svg" width="20" height="20"> icon, available for specific domain card.

**Engine card** structure:
- **Engine** - engine name.
- **Pod address** - address of the Pod for Engine.
- **State** - state of Pod. Possible values:
	- <span style="color:yellow; font-size: 30px;">&#8226</span> _Not Ready_
	- <span style="color:green; font-size: 30px;">&#8226</span> _Ready_
- **Pod status** - status of the Pod. Possible values:
	- <span style="color:yellow; font-size: 30px;">&#8226</span> _Pending_
	- <span style="color:green; font-size: 30px;">&#8226</span> _Running_
	- <span style="color:red; font-size: 30px;">&#8226</span> _Failed_
- <img src="docs/03__Admin_Tools/1__Domains/img/plus.svg" width="20" height="20"> - expands/collapses the engine card.

### Chain Deployment Card View

To expand the engine tree and see **chain deployments**, click <img src="docs/03__Admin_Tools/1__Domains/img/plus.svg" width="20" height="20"> icon, available for specific engine card.

**Chain deployment card**  structure:
- **Chain name** - name of the chain deployed on current engine.
- **Snapshot Name** - name of the deployed [Snapshot](docs/01__Chains/2__Snapshots/snapshots.md).
- **Status** - deployment status. Detailed information is available in [Deployments page](docs/01__Chains/3__Deployments/deployments.md).
