# The Kubernetes target

`CIP_TARGET=k8s` runs the suite against a local cluster. The suite owns namespace `cip-e2e`: it
builds the images from the checkout, installs `infrastructure/helm/cip-dev` there with
`e2e/k8s/values.e2e.yaml`, and waits until every service answers. You prepare the cluster and what
runs cluster-wide once, with the bootstrap script or by hand, as this page describes. A run checks
for each piece in the order below and fails on the first one missing, naming the section here that
installs it.

Besides Docker and Maven, which a Compose run needs too, the host needs `kubectl` and `helm` on the
`PATH`, `istioctl` 1.31 or later for the Istio install below, and the `kind` or `k3d` CLI on those
clusters: the suite loads its images into the node with them.

```bash
cd e2e
npm run test:k8s
```

## Cluster

`e2e/k8s/bootstrap-cluster.sh` prepares a cluster for the suite. Run it from any directory:

```bash
e2e/k8s/bootstrap-cluster.sh
```

To create or reuse a separate kind cluster for end-to-end tests, even when your current context is
`docker-desktop`, run this from the repository root:

```bash
bash e2e/k8s/bootstrap-cluster.sh --kind
```

This selects `kind-qip` and installs the cluster components there. The existing Docker Desktop
cluster keeps its Istio installation. Linux, macOS, and Windows use the same command; on native
Windows, run it in Git Bash with the Windows CLIs on `PATH`. Then run `npm run test:k8s` from
`e2e/` in the same host environment, with `kind-qip` selected.

Docker Desktop supports both kubeadm and kind. The bootstrap and runner select the mesh from the
node identity and runtime on the `docker-desktop` context: the kubeadm node `docker-desktop` or a
Docker runtime selects sidecars; kind nodes with containerd select ambient. The kubeadm path uses the existing cluster and needs no Istio CNI or ztunnel. This avoids
`/var/run/netns is mounted on /run but it is not a shared or slave mount` without changing VM mounts.
Run the default bootstrap with `docker-desktop` selected to prepare that cluster; use `--kind` when
you want a separate cluster.

On the `docker-desktop` context, or on any `k3d-<name>` or `kind-<name>` context, the default command uses the
current cluster. On any other context, or with no kubeconfig at all, it creates the kind cluster
`kind-qip` from `kindest/node:v1.37.0` with the nine ports below mapped, and kind switches the
current context to it. Then it installs the Gateway API CRDs, Istio, the camel-k operator, and,
except on k3d, metrics-server, which the sections below install by hand. A second run with the same mode on a prepared cluster restarts nothing.

The suite reads the cluster kind from the current kube-context and supports three:

| Context | Cluster | Images reach the node through | Host ports reach the Services through | CoreDNS | UI dev server |
| --- | --- | --- | --- | --- | --- |
| `docker-desktop` | Docker Desktop's Kubernetes | the host daemon, nothing to load | LoadBalancer Services | `10.96.0.10` | `host.docker.internal` |
| `kind-<name>` | kind | `kind load docker-image` | NodePorts mapped at creation | `10.96.0.10` | `host.docker.internal` on Docker Desktop, otherwise the `kind` network gateway |
| `k3d-<name>` | k3d | `k3d image import` | NodePorts mapped at creation | `10.43.0.10` | `host.k3d.internal` |

Every host-side address is a fixed port:

| Port | What answers |
| --- | --- |
| 30080 | the nginx proxy: the `/api/` surface, the UI, and the `/e2e/` locations |
| 30091 | runtime-catalog |
| 30092 | the classic engine |
| 30093 | sessions-management |
| 30095 | the testing service |
| 30992 | Kafka, its `EXTERNAL` listener |
| 30672 | RabbitMQ, AMQP |
| 30085 | the Pub/Sub emulator |
| 30022 | the SFTP server |

The last four answer only when the run installs the brokers, which `E2E_BROKERS=0` turns off.

**Docker Desktop.** Enable Kubernetes in the settings. The kind-based cluster (node
`desktop-control-plane`) pulls host images through Docker Desktop's registry mirror, so an image the
suite builds with `docker build` runs with no loading step. It publishes a LoadBalancer Service's
port on the host and a NodePort not at all, so on this context the suite sets each service's
`loadBalancerPort` in the chart, which adds a LoadBalancer Service on the same fixed port.

On a workstation running Docker Desktop, three limits apply. Its built-in cluster cannot start
Kubernetes 1.37, so a 1.37 cluster there is a kind cluster. A Docker Desktop restart or update
deletes the `kind` network, and the node then does not start, so create the cluster again. A full
run at 8 workers can run the Docker Desktop VM out of memory, so run by phases with
`E2E_WORKERS=4`: `api`, `runtime`, and `k8s` with `E2E_KEEP=1`, then `global` against that corpus
with `--no-deps` and `E2E_SWEEP=never`, then `env`, `runtime-micro`, and `ui`, each on its own.
`global` and `env` never run side by side, because `env` restarts the catalog.
`specs/env/process-envelope.spec.ts` fails in such a partial run, because its sampler takes one
reading per service there.

**kind.** The script maps the nine ports when it creates `kind-qip`. A kind cluster created another
way needs the same `extraPortMappings` on its control-plane node, each `containerPort` equal to its
`hostPort`. kind fixes the mappings at creation, so a `kind-qip` created before the broker ports
were added has to be deleted and created again:

```bash
kind delete cluster --name qip
e2e/k8s/bootstrap-cluster.sh
```

**k3d.** The script does not create a k3d cluster. Create one with the ports mapped:

```bash
k3d cluster create qip \
  -p "30080:30080@server:0" -p "30091:30091@server:0" -p "30092:30092@server:0" \
  -p "30093:30093@server:0" -p "30095:30095@server:0" \
  -p "30992:30992@server:0" -p "30672:30672@server:0" -p "30085:30085@server:0" \
  -p "30022:30022@server:0"
```

The UI bundle is served by `vite preview` on port 4200 of your machine, and the proxy reaches it
through the name in the last column of the first table. Docker Desktop uses `host.docker.internal`
for kind too, because the bridge gateway is inside its VM. On kind and k3d, that address has to reach
port 4200, so a firewall that drops traffic from the Docker bridge takes the UI routes down, while
every `/api/` route keeps working.

## Gateway API

Install the standard channel:

```bash
kubectl apply --server-side --force-conflicts -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.6.3/standard-install.yaml
```

## Istio

`CIP_ISTIO_MODE` accepts `auto` (the default), `ambient`, or `sidecar`. Both the bootstrap and the
runner use it. Auto selects sidecars for Docker Desktop kubeadm and ambient for
Docker Desktop kind, standalone kind, and k3d. The run header records the namespace's actual mode.

The bootstrap installs Istio cluster-wide. On an existing cluster, it can upgrade the control plane;
the runner provisions only `cip-e2e`. Both modes use Istio 1.31 or later for the chart's Gateway API
routes.

### Ambient mode

From the repository root:

```bash
istioctl install -f infrastructure/helm/cip-dev/istio.yaml
```

The file installs the `ambient` profile: one istiod, plus `istio-cni-node` and `ztunnel` on each
node. It installs no default ingress or egress gateway. The platform chart creates its four gateways
and, with `gateways.waypoint.enabled: true` in the end-to-end values, one namespace waypoint.

The requests leave room on a 4-CPU node for the platform. istiod autoscaling is off:

| Component | CPU request | Memory request | Memory limit |
| --- | --- | --- | --- |
| istiod | 10m | 100Mi | 512Mi |
| CNI, per node | 10m | 64Mi | 256Mi |
| ztunnel, per node | 10m | 64Mi | 256Mi |
| Each gateway or waypoint | 10m | 40Mi | 256Mi |

On one node these eight mesh pods request 80m CPU and 428Mi memory and have a combined memory
limit of 2304Mi. Application pods have no sidecar. These are mesh budgets; the platform, brokers,
micro domains, Kubernetes, builds, and Playwright need resources too.

The chart's egress routes use `backendRefs` with `kind: Hostname`, which istiod 1.31.1 programs
with no feature flag set; this page is verified on Istio 1.31 only. The suite removes the namespace's
`istio-injection` label and sets `istio.io/dataplane-mode=ambient` and
`istio.io/use-waypoint=waypoint` before installing the chart. It waits for the waypoint to be programmed.

When converting an existing sidecar stand, install the ambient profile first. Enroll the namespace,
create its waypoint, and restart the old injected workloads before running the suite:

```bash
kubectl label namespace cip-e2e --overwrite istio-injection- istio.io/dataplane-mode=ambient istio.io/use-waypoint=waypoint
helm upgrade cip infrastructure/helm/cip-dev -n cip-e2e --reuse-values --set gateways.waypoint.enabled=true
kubectl wait --for=condition=Programmed gateway/waypoint -n cip-e2e --timeout=180s
kubectl rollout restart deployment -n cip-e2e
kubectl rollout status daemonset/istio-cni-node -n istio-system
kubectl rollout status daemonset/ztunnel -n istio-system
```

Check that the application pods have one container each and that the waypoint is programmed:

```bash
kubectl get pods -n cip-e2e
kubectl get gateway waypoint -n cip-e2e
istioctl ztunnel-config workloads -n istio-system
```

### Sidecar mode

On Docker Desktop kubeadm, the default bootstrap installs the sidecar profile. To install it by hand
from the repository root:

```bash
istioctl install -f infrastructure/helm/cip-dev/istio.yaml --set profile=default --set components.cni.enabled=false --set components.ztunnel.enabled=false
```

This profile includes istiod, the injector, and the default ingress gateway. It leaves Istio CNI and
ztunnel disabled. The suite labels `cip-e2e` with `istio-injection=enabled`, removes ambient and
waypoint enrollment, disables the chart's namespace waypoint, and waits for the application rollouts.
Application pods have an injected `istio-proxy`; the resource sampler counts the application
container. A mode annotation restarts the platform services when the selected mode changes.

To select sidecars explicitly, set the same mode for bootstrap and runner. In PowerShell, with the
repository root as the working directory:

```powershell
$env:CIP_ISTIO_MODE = "sidecar"
bash e2e/k8s/bootstrap-cluster.sh
cd e2e
npm run test:k8s
```

On a normal Docker Desktop kubeadm cluster, the automatic selection needs no environment variable.
The CI sidecar job runs the micro lane on kind with the sidecar profile, alongside the ambient jobs.
It checks the mesh behavior without requiring a Docker Desktop VM in CI.

## camel-k

The micro engine runs as a camel-k Integration, which the operator turns into a Deployment:

```bash
helm repo add camel-k https://apache.github.io/camel-k/charts/
helm install camel-k camel-k/camel-k --version 2.11.0 -n camel-k --create-namespace --set-string operator.global=true
```

`--set-string` matters. The chart compares the value with the string `"true"`
(`templates/rbacs-descoped.yaml`), and `--set 'operator.global="true"'` stores the quotes as part of
the value. The chart then renders neither the cluster-wide nor the namespaced RBAC, the operator
cannot list pods, and an Integration stays with no phase and no pod. An operator installed that way
is repaired in place. Pass the chart version `helm list -n camel-k` shows, so the upgrade keeps the
operator version:

```bash
helm upgrade camel-k camel-k/camel-k -n camel-k --version <chart version> --set-string operator.global=true
```

## metrics-server

The resource sampler reads `kubectl top pod --containers`, which needs the metrics API. k3d ships
metrics-server with k3s, so the bootstrap script skips this step there. On Docker Desktop and kind,
install it:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.0/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

The kubelets of Docker Desktop and kind serve self-signed certificates. Without
`--kubelet-insecure-tls`, metrics-server never scrapes a node and `kubectl top` fails. Applying
`components.yaml` again removes the flag, so the bootstrap script applies it only when metrics-server
is missing or runs another version.

## What a run does in `cip-e2e`

1. Builds the four platform images and the micro-engine image, each tagged `e2e-` and a hash over
   its sources: the module's `src/`, `pom.xml`, and `Dockerfile`, and `parents/spring/pom.xml` for the
   Spring services. The micro-engine hash also covers `parents/quarkus/pom.xml` and
   `parents/base/pom.xml`. The runtime-catalog hash also covers `integration-build-pipeline/src` and its
   `pom.xml`, the library linked into its jar, and the testing service's covers its whole directory.
   These are the sources the Compose provisioner compares. An image whose tag exists is not rebuilt, and one
   `mvn install -T 1C` runs for the modules whose image is missing. `E2E_BUILD` narrows the images
   built to the ones it lists; every other one is tagged `latest` and comes from GHCR. The micro engine is built with
   `-Dquarkus.profile=development,no-m2m`, the profiles `micro-engine/README.md` gives for a local
   stack, and the tag covers them too: Quarkus fixes part of its configuration at build time, and a
   `prod` build stops at startup with `auth method "cip-e2e" not found`, because it logs in to
   Consul through M2M and expects a DBaaS agent.
2. Loads the images it built into the node on kind and k3d.
3. Creates the namespace and labels it for sidecar injection.
4. Runs `helm upgrade --install cip infrastructure/helm/cip-dev -n cip-e2e -f e2e/k8s/values.e2e.yaml`
   with the tags, the micro image, the host ports, and the cluster's values. Helm leaves a Deployment whose tag did
   not change alone. The catalog reads the micro image once, at startup, so its pod carries the
   micro tag as an annotation and restarts when that tag changes. The same command installs the
   brokers: `<broker>.enabled` for each of the four, their host ports, the Kafka TLS files from
   `infrastructure/docker/kafka-ssl/server/` through `--set-file`, the CA from
   `infrastructure/docker/kafka-ssl/client/ca.crt` as `cip-engine.trustedCerts`, and the
   `cip-runtime-catalog.microDomainResources` and `microDomainEmptyDirs` values that mount it into
   a micro domain. Only the `enabled` switches follow `E2E_BROKERS`, so changing it between runs
   restarts no engine, catalog, or micro domain.
5. Waits for each rollout, then for `/actuator/health` of each JVM service, `/health` of the testing
   service, and a catalog route through the proxy.

`E2E_PROVISION=never` skips all of that and only checks that everything answers.

**A `cip-e2e` installed without `values.e2e.yaml` has to be deleted first.** Those values run every
platform Deployment with `strategy: Recreate`, and Helm cannot switch a rolling-update Deployment
to `Recreate`: the API server refuses the leftover `rollingUpdate` field. Delete the namespace and
let the next run install it again:

```bash
kubectl delete namespace cip-e2e
```

**A `cip-e2e` installed from a chart whose Postgres rolls has to lose that Deployment first.** The
chart now runs Postgres with `strategy: Recreate`, because a rolling update starts a second server on
the same data directory and corrupts its WAL. Helm refuses the switch for the same reason as above.
Delete the Deployment alone, which keeps the database on its PersistentVolumeClaim, and let the next
run create it again:

```bash
kubectl delete deployment cip-postgres -n cip-e2e
```

## A failed micro case

A micro domain is the camel-k Integration `cip-engine-<domain>-v1`, and its pod is deleted with the
domain. So a failed case on a micro project reads the pod while it still exists and attaches its
log, its description with the events, and the Integration with its status, as
[Reading a failure](../README.md#reading-a-failure) lists. The run then keeps its micro domains,
which the CI cluster dump reads after the suite, and which you read the same way:

```bash
kubectl logs -n cip-e2e deploy/cip-engine-<domain>-v1 -c integration
kubectl get integrations.camel.apache.org -n cip-e2e -o yaml
```

The next run's sweep deletes them.
