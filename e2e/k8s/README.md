# The Kubernetes target

`CIP_TARGET=k8s` runs the suite against a local cluster. The suite owns namespace `qip-e2e`: it
builds the images from the checkout, installs `infrastructure/qip-dev` there with
`e2e/k8s/values.e2e.yaml`, and waits until every service answers. You install the cluster and what
runs cluster-wide, once, as this page describes. A run checks for each piece in the order below and
fails on the first one missing, naming the section here that installs it.

Besides Docker and Maven, which a Compose run needs too, the host needs `kubectl` and `helm` on the
`PATH`, `istioctl` for the Istio install below, and the `kind` or `k3d` CLI on those clusters: the
suite loads its images into the node with them.

```bash
cd e2e
npm run test:k8s
```

## Cluster

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

**kind.** Map the NodePorts when you create the cluster:

```bash
cat > kind-qip.yaml <<'EOF'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraPortMappings:
      - { containerPort: 30080, hostPort: 30080 }
      - { containerPort: 30091, hostPort: 30091 }
      - { containerPort: 30092, hostPort: 30092 }
      - { containerPort: 30093, hostPort: 30093 }
      - { containerPort: 30095, hostPort: 30095 }
EOF
kind create cluster --name qip --config kind-qip.yaml
```

**k3d.**

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

Install the **experimental** channel. With `PILOT_ENABLE_ALPHA_GATEWAY_API=true`, istiod watches
`UDPRoute` v1alpha2, which the standard channel does not serve, and istiod then never becomes ready.

```bash
kubectl apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.6.2/experimental-install.yaml
```

If the standard channel is already installed, delete its `safe-upgrades.gateway.networking.k8s.io`
ValidatingAdmissionPolicy first. It refuses to install the experimental CRDs over the standard ones:

```bash
kubectl delete validatingadmissionpolicybinding safe-upgrades.gateway.networking.k8s.io --ignore-not-found
kubectl delete validatingadmissionpolicy safe-upgrades.gateway.networking.k8s.io
```

## Istio

```bash
istioctl install --set profile=demo \
  --set values.pilot.env.PILOT_ENABLE_ALPHA_GATEWAY_API=true
```

The chart's egress routes use `backendRefs` with `kind: Hostname`, which istiod programs only with
`PILOT_ENABLE_ALPHA_GATEWAY_API` set. The suite labels `qip-e2e` with `istio-injection=enabled`
before it installs the chart, so every pod starts with a sidecar.

## camel-k

The micro engine runs as a camel-k Integration, which the operator turns into a Deployment:

```bash
helm repo add camel-k https://apache.github.io/camel-k/charts/
helm install camel-k camel-k/camel-k -n camel-k --create-namespace --set-string operator.global=true
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
metrics-server with k3s; on Docker Desktop and kind, install it:

```bash
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/download/v0.9.0/components.yaml
kubectl -n kube-system patch deployment metrics-server --type=json \
  -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
```

The kubelets of Docker Desktop and kind serve self-signed certificates. Without
`--kubelet-insecure-tls`, metrics-server never scrapes a node and `kubectl top` fails.

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
