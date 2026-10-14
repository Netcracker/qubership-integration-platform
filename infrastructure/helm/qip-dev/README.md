# Qubership Integration Platform - Helm charts for local development

## Istio

`global.qip.controlPlane.meshType: Istio` (the default in `values.yaml`) makes the platform generate
Gateway API and Istio resources. Install Istio and the Gateway API CRDs, and label the target
namespace for sidecar injection, before you install the chart:

```sh
kubectl apply --server-side -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.6.2/experimental-install.yaml
istioctl install --set profile=demo \
  --set values.pilot.env.PILOT_ENABLE_ALPHA_GATEWAY_API=true
kubectl create namespace qip
kubectl label namespace qip istio-injection=enabled
```

Install the Gateway API CRDs from the **experimental** channel. With
`PILOT_ENABLE_ALPHA_GATEWAY_API=true`, istiod watches `UDPRoute` v1alpha2, which the standard channel
does not serve, and istiod then never becomes ready. If the standard channel is already installed,
delete its `safe-upgrades.gateway.networking.k8s.io` ValidatingAdmissionPolicy and its binding
first, because the policy refuses the experimental CRDs; `e2e/k8s/README.md` gives the commands.

Sidecars are injected when a pod is created, so a namespace labeled after `helm install` leaves the
running pods without one. Run `kubectl rollout restart deployment -n qip` to recreate them if you get
the order wrong.

Egress routes use `backendRefs` with `kind: Hostname`, which `istiod` only honors when
`PILOT_ENABLE_ALPHA_GATEWAY_API` is set. Without it the egress `HTTPRoute` is accepted but never
programmed, and outgoing calls fail with no route.

`CIP_ISTIO_HOST_RESOURCES_ENABLED` (default `true`) controls whether `runtime-catalog` and
`engine` generate the `ServiceEntry` and `DestinationRule` those routes depend on. Turn it off
only when something else supplies them. The `HTTPRoute` still names its target with
`kind: Hostname`, which Istio resolves through a `ServiceEntry`, so without one every egress call
fails while the route itself still looks healthy. HTTPS targets need the `DestinationRule` too,
because it originates TLS and nothing else in the generated configuration does.

The chart's gateways listen on these ports:

| Gateway | Service name | Port |
| --- | --- | --- |
| `public-gateway` | `public-gateway` | 80 |
| `private-gateway` | `private-gateway` | 80 |
| `internal-gateway` | `internal-gateway-service` | 8080 |
| `egress-gateway` | `egress-gateway` | 8080 |

`internal-gateway`'s Service name must stay in step with `cip.gateway.internal.name`, and
`egress-gateway`'s port with the port in `cip.gateway.egress.url` — both in `runtime-catalog`'s
`application.yml`. Change one side and change the other, or override the egress URL with
`CIP_EGRESS_GATEWAY_URL`. `EndpointHelperSource` reads that URL at build time and bakes it into the
generated Camel source in the snapshot ConfigMap, so setting it saves a rebuild but takes effect
only once you redeploy every chain.

## Installation

```sh
helm repo add camel-k https://apache.github.io/camel-k/charts/
helm install camel-k camel-k/camel-k -n camel-k --create-namespace --set-string operator.global=true
helm install --create-namespace --namespace qip qip .
```

Pass `operator.global` with `--set-string`. The camel-k chart renders its RBAC only when the value
is the string `true` (`templates/rbacs-descoped.yaml`), and `--set 'operator.global="true"'` stores
the quotes as part of the value. The operator then cannot list pods, and a micro domain's
Integration stays with no phase and no pod. Repair an operator installed that way in place, at the
chart version `helm list -n camel-k` shows:

```sh
helm upgrade camel-k camel-k/camel-k -n camel-k --version <chart version> --set-string operator.global=true
```

## Values for the platform services

`qip-engine`, `qip-runtime-catalog`, `qip-sessions-management`, and `qip-testing-service` each take
these values under their own key. Every one is optional, and the defaults render the manifests the
chart rendered before the value existed.

| Value | Default | What it does |
| --- | --- | --- |
| `image.tag` | `latest` | The tag of the service's `ghcr.io/netcracker/qubership-integration-<service>` image. |
| `nodePort` | unset | A fixed node port for port 8080. Setting it makes the Service a NodePort and drops the debug port 5005 from it, where the service has one, because a NodePort Service opens every port it lists on each node. Reach 5005 with `kubectl port-forward` instead. |
| `loadBalancerPort` | unset | The port a second Service, `<release>-<service>-v1-lb` of type LoadBalancer, publishes and forwards to port 8080. Docker Desktop's kind-based cluster publishes a LoadBalancer port on the host and a NodePort not at all. |
| `strategy` | `Recreate` for the engine, `{}` (a rolling update) for the others | The Deployment strategy. |
| `extraEnv` | `[]` | Container environment entries added after the chart's own, in the Deployment's `env` form. |
| `podAnnotations` | `{}` | Annotations on the pod template, such as `sidecar.istio.io/inject: "false"`. |

The engine and the catalog also take `jvm.maxHeap`, `jvm.metaspaceSize`, and
`jvm.maxMetaspaceSize`, which become `-Xmx`, `-XX:MetaspaceSize`, and `-XX:MaxMetaspaceSize`. The
defaults are `832m`, `384m`, and `384m` for the engine, and `642m`, `192m`, and `192m` for the
catalog. Both services still run with `-XX:+ExitOnOutOfMemoryError`.

`qip-runtime-catalog.serviceAliases` lists extra ClusterIP Service names for the catalog's port
8080, for a caller that addresses the catalog by another name.

The proxy takes `global.qip.ui.loadBalancerPort` and `global.qip.ui.podAnnotations`, with the same
meaning as above, and `global.qip.ui.e2eLocations` (default `false`) adds two nginx locations for
the end-to-end suite:

- `/e2e/svc/<service>/<path>` reaches port 8080 of any Service in the release's namespace, so a
  test can call a micro domain's Service, `qip-engine-<domain>-v1`, from the host.
- `/e2e/gateway/<path>` reaches `public-gateway` on port 80.

Their upstreams are built from variables and resolved through `global.qip.ui.resolver`, so a
Service that does not exist yet fails the request with 502 instead of stopping nginx at startup. A
502 or 504 that nginx produces reaches the caller as it is, and a 5xx the upstream returns passes
through unchanged. Leave the locations off for anything but the suite.

`e2e/k8s/values.e2e.yaml` is the suite's own values file for namespace `qip-e2e`:
`Recreate` everywhere, JVM sizes above the chart defaults, the environment variables the specs read,
and the `/e2e/` proxy locations. The suite sets the image tags, the micro-engine image, the NodePorts, and the
LoadBalancer ports on the command line; `e2e/k8s/README.md` describes the install it makes.

## UI

The UI available on [http://localhost:30080/](http://localhost:30080/) via NodePort service.
You still need to serve the UI locally, since this Helm chart only installs an nginx-based proxy pointing back to your host.
Docker Desktop's kind-based cluster publishes no NodePort on the host; set
`global.qip.ui.loadBalancerPort=30080` there, and the proxy answers on the same address.

The proxy reaches your machine by name, and the name differs per cluster. The default,
`host.docker.internal`, is what Docker Desktop provides; minikube offers `host.minikube.internal`,
and on kind you have to name the host's own address:

```sh
helm install ... --set global.qip.ui.devServer.host=host.minikube.internal
```

A name the cluster cannot resolve costs you the UI routes and nothing else — they answer 502 while
every API route keeps working. Set `global.qip.ui.resolver` as well if your distribution puts
CoreDNS somewhere other than `10.96.0.10` (k3s and k3d use `10.43.0.10`).

## Remove namespace data

```bash
kubectl delete all,secrets,configmaps,pvc -n <NAMESPACE> --all
```
