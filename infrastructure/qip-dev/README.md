# Qubership Integration Platform - Helm charts for local development

## Istio

`global.qip.controlPlane.meshType: Istio` (the default in `values.yaml`) makes the platform generate
Gateway API and Istio resources. Install Istio and the Gateway API CRDs, and label the target
namespace for sidecar injection, before you install the chart:

```sh
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.2.0/standard-install.yaml
istioctl install --set profile=minimal \
  --set values.pilot.env.PILOT_ENABLE_ALPHA_GATEWAY_API=true
kubectl create namespace qip
kubectl label namespace qip istio-injection=enabled
```

Sidecars are injected when a pod is created, so a namespace labeled after `helm install` leaves the
running pods without one. Run `kubectl rollout restart deployment -n qip` to recreate them if you get
the order wrong.

Egress routes use `backendRefs` with `kind: Hostname`, which `istiod` only honors when
`PILOT_ENABLE_ALPHA_GATEWAY_API` is set. Without it the egress `HTTPRoute` is accepted but never
programmed, and outgoing calls fail with no route.

The chart's gateways listen on these ports:

| Gateway | Service name | Port |
| --- | --- | --- |
| `public-gateway` | `public-gateway` | 80 |
| `private-gateway` | `private-gateway` | 80 |
| `internal-gateway` | `internal-gateway-service` | 8080 |
| `egress-gateway` | `egress-gateway` | 8080 |

`internal-gateway`'s Service name must stay in step with `qip.gateway.internal.name`, and
`egress-gateway`'s port with the port in `qip.gateway.egress.url` — both in `runtime-catalog`'s
`application.yml`. Change one side and change the other, or override the egress URL with
`QIP_EGRESS_GATEWAY_URL`.

## Installation

```sh
helm repo add camel-k https://apache.github.io/camel-k/charts/
helm install camel-k camel-k/camel-k -n camel-k --create-namespace --set 'operator.global="true"'
helm install --create-namespace --namespace qip qip .
```

## UI

The UI available on [http://localhost:30080/](http://localhost:30080/) via NodePort service.
You still need to serve the UI locally, since this Helm chart only installs an nginx-based proxy pointing back to your host.

## Remove namespace data

```bash
kubectl delete all,secrets,configmaps,pvc -n <NAMESPACE> --all
```
