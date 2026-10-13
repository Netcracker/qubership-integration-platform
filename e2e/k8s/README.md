# The Kubernetes target

`CIP_TARGET=k8s` runs the suite against a local cluster. The suite owns namespace `qip-e2e`: it
builds the images from the checkout, installs `infrastructure/qip-dev` there with
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

On the `docker-desktop` context, or on any `k3d-<name>` or `kind-<name>` context, it uses the
current cluster. On any other context, or with no kubeconfig at all, it creates the kind cluster
`kind-qip` from `kindest/node:v1.37.0` with the five ports below mapped, and kind switches the
current context to it. Then it installs the Gateway API CRDs, Istio, the camel-k operator, and,
except on k3d, metrics-server, which the sections below install by hand. A second run on a cluster
the script has prepared restarts nothing.

The suite reads the cluster kind from the current kube-context and supports three:

| Context | Cluster | Images reach the node through | Host ports reach the Services through | CoreDNS | UI dev server |
| --- | --- | --- | --- | --- | --- |
| `docker-desktop` | Docker Desktop's Kubernetes | the host daemon, nothing to load | LoadBalancer Services | `10.96.0.10` | `host.docker.internal` |
| `kind-<name>` | kind | `kind load docker-image` | NodePorts mapped at creation | `10.96.0.10` | the gateway of the `kind` Docker network |
| `k3d-<name>` | k3d | `k3d image import` | NodePorts mapped at creation | `10.43.0.10` | `host.k3d.internal` |

Every host-side address is a fixed port:

| Port | What answers |
| --- | --- |
| 30080 | the nginx proxy: the `/api/` surface, the UI, and the `/e2e/` locations |
| 30091 | runtime-catalog |
| 30092 | the classic engine |
| 30093 | sessions-management |
| 30095 | the testing service |

**Docker Desktop.** Enable Kubernetes in the settings. The kind-based cluster (node
`desktop-control-plane`) pulls host images through Docker Desktop's registry mirror, so an image the
suite builds with `docker build` runs with no loading step. It publishes a LoadBalancer Service's
port on the host and a NodePort not at all, so on this context the suite sets each service's
`loadBalancerPort` in the chart, which adds a LoadBalancer Service on the same fixed port.

**kind.** The script maps the five ports when it creates `kind-qip`. A kind cluster created another
way needs the same `extraPortMappings` on its control-plane node, each `containerPort` equal to its
`hostPort`.

**k3d.** The script does not create a k3d cluster. Create one with the ports mapped:

```bash
k3d cluster create qip \
  -p "30080:30080@server:0" -p "30091:30091@server:0" -p "30092:30092@server:0" \
  -p "30093:30093@server:0" -p "30095:30095@server:0"
```

The UI bundle is served by `vite preview` on port 4200 of your machine, and the proxy reaches it
through the name in the last column of the first table. On kind and k3d, that address has to reach
port 4200, so a firewall that drops traffic from the Docker bridge takes the UI routes down, while
every `/api/` route keeps working.

## Gateway API

Install the standard channel:

```bash
kubectl apply --server-side --force-conflicts -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.6.3/standard-install.yaml
```

## Istio

From the repository root:

```bash
istioctl install -f infrastructure/qip-dev/istio.yaml
```

The file takes the `minimal` profile and lowers the requests to 10m CPU and 100Mi for istiod and to
10m CPU and 40Mi for each sidecar and gateway pod, because the `minimal` defaults, 500m for istiod
and 100m per sidecar, reserve CPU that a 4-CPU node needs for the run's own pods. On a cluster with
the `demo` profile, the same command replaces it and removes the `istio-ingressgateway` and
`istio-egressgateway` that `demo` installs and nothing in the chart uses.

The chart's egress routes use `backendRefs` with `kind: Hostname`, which istiod 1.31.1 programs
with no feature flag set; this page is verified on Istio 1.31 only. The suite labels `qip-e2e` with
`istio-injection=enabled` before it installs the chart, so every pod starts with a sidecar.

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

## What a run does in `qip-e2e`

1. Builds the four platform images and the micro-engine image, each tagged `e2e-` and a hash over
   its sources: the module's `src/`, `pom.xml`, and `Dockerfile`, and `parent/pom.xml` for the
   Spring services. The runtime-catalog hash also covers `integration-build-pipeline/src` and its
   `pom.xml`, the library linked into its jar, and the testing service's covers its whole directory.
   These are the sources the Compose provisioner compares. An image whose tag exists is not rebuilt, and `mvn install` runs only for the
   modules whose image is missing. The micro engine is built with
   `-Dquarkus.profile=development,no-m2m`, the profiles `micro-engine/README.md` gives for a local
   stack, and the tag covers them too: Quarkus fixes part of its configuration at build time, and a
   `prod` build stops at startup with `auth method "qip-e2e" not found`, because it logs in to
   Consul through M2M and expects a DBaaS agent.
2. Loads the images into the node on kind and k3d.
3. Creates the namespace and labels it for sidecar injection.
4. Runs `helm upgrade --install qip infrastructure/qip-dev -n qip-e2e -f e2e/k8s/values.e2e.yaml`
   with the tags, the micro image, the host ports, and the cluster's values. Helm leaves a Deployment whose tag did
   not change alone. The catalog reads the micro image once, at startup, so its pod carries the
   micro tag as an annotation and restarts when that tag changes.
5. Waits for each rollout, then for `/actuator/health` of each JVM service, `/health` of the testing
   service, and a catalog route through the proxy.

`E2E_PROVISION=never` skips all of that and only checks that everything answers.

**A `qip-e2e` installed without `values.e2e.yaml` has to be deleted first.** Those values run every
platform Deployment with `strategy: Recreate`, and Helm cannot switch a rolling-update Deployment
to `Recreate`: the API server refuses the leftover `rollingUpdate` field. Delete the namespace and
let the next run install it again:

```bash
kubectl delete namespace qip-e2e
```
