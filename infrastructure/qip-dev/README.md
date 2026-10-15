# Qubership Integration Platform - Helm charts for local development

## Istio

`global.qip.controlPlane.meshType: Istio` (the default in `values.yaml`) makes the platform generate
Gateway API and Istio resources. Install Istio 1.31 or later and the Gateway API CRDs, and label
the target namespace for sidecar injection, before you install the chart. From this directory:

```sh
kubectl apply --server-side --force-conflicts -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.6.3/standard-install.yaml
istioctl install -f istio.yaml
kubectl create namespace qip
kubectl label namespace qip istio-injection=enabled
```

`istio.yaml` installs Istio's `minimal` profile with resource requests small enough for a
single-node local cluster. A cluster installed earlier from the experimental Gateway API channel
with `PILOT_ENABLE_ALPHA_GATEWAY_API=true` keeps working and needs no reinstall.

Sidecars are injected when a pod is created, so a namespace labeled after `helm install` leaves the
running pods without one. Run `kubectl rollout restart deployment -n qip` to recreate them if you get
the order wrong.

Egress routes use `backendRefs` with `kind: Hostname`, which `istiod` 1.31.1 programs with no
feature flag set.

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

`qip-engine.trustedCerts` maps a file name to a PEM certificate. The chart puts the map in Secret
`<release>-engine-certs` and mounts it at `/app/cert/defaultsslcertificate`, where the engine's
`development` profile reads the certificates it trusts. From this directory:

```sh
helm install ... --set-file 'qip-engine.trustedCerts.ca\.crt=../kafka-ssl/client/ca.crt'
```

`qip-runtime-catalog.microDomainResources` and `qip-runtime-catalog.microDomainEmptyDirs` become
`MICRO_DOMAIN_RESOURCES` and `MICRO_DOMAIN_EMPTY_DIRS`: the camel-k mount resources and emptyDir
volumes of every micro domain. These two give a micro domain the same certificates, and an emptyDir
for the truststore it builds from them, because its root file system is read-only:

```sh
helm install ... \
  --set 'qip-runtime-catalog.microDomainResources={secret:<release>-engine-certs@/app/cert/defaultsslcertificate}' \
  --set 'qip-runtime-catalog.microDomainEmptyDirs={truststore:/app/truststore}'
```

The chart refuses to render a mount of `<release>-engine-certs` while `qip-engine.trustedCerts` is
empty, because a micro pod that mounts a missing Secret stays in `ContainerCreating`.

`qip-runtime-catalog.microDomainMetrics` (default `false`) sets `CIP_METRICS_ENABLED` in every micro
domain, which then serves the chain meters on `/metrics`. It creates no ServiceMonitor.

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

## Brokers

The chart can install the brokers of the Compose overlay files, for chains that use them. Each is off
by default and runs as one pod with no sidecar and no persistent volume, so its data lasts as long as the pod.

```sh
helm install ... --set kafka.enabled=true,rabbitmq.enabled=true,pubsub.enabled=true,sftp.enabled=true
```

Each broker gets a ClusterIP Service under its Compose service name, so a chain addresses it the same
way on both targets, and a Service for clients on the host, `<name>-host`. That one is a NodePort
Service, with the port from `<name>.nodePort` if you set one. Set `<name>.loadBalancerPort` instead
on Docker Desktop, where it becomes a LoadBalancer Service on that port.

| Broker | Value key | In the cluster | Host Service | Credentials |
| --- | --- | --- | --- | --- |
| Kafka (`confluentinc/cp-kafka:7.5.0`) | `kafka` | `kafka:29092`, see the listeners below | `kafka-host`, to port 9092 | SASL user `e2e`, password `e2e-secret` |
| RabbitMQ (`rabbitmq:4.3.6-management-alpine`) | `rabbitmq` | `rabbitmq:5672`, management on 15672 | `rabbitmq-host`, to port 5672 | `guest` / `guest` |
| Pub/Sub emulator (`google/cloud-sdk:587.0.0-emulators`) | `pubsub` | `pubsub:8085`, project `test-project` | `pubsub-host`, to port 8085 | none |
| SFTP (`atmoz/sftp:alpine`) | `sftp` | `sftp-server:22` | `sftp-host`, to port 22 | `e2e` / `e2epass`, writable directory `/upload` |

Kafka has the listener layout of `docker-compose.kafka.yml`:

| Listener | Port | Protocol | Advertised as |
| --- | --- | --- | --- |
| `INTERNAL` | 29092 | PLAINTEXT | `kafka:29092` |
| `EXTERNAL` | 9092 | PLAINTEXT | `localhost:<loadBalancerPort, nodePort, or 9092>` |
| `AUTH` | 29094 | SASL_PLAINTEXT | `kafka:29094` |
| `TLS` | 29095 | SSL | `kafka:29095` |
| `AUTHTLS` | 29096 | SASL_SSL | `kafka:29096` |
| `TLS13` | 29097 | SSL, TLSv1.3 only | `kafka:29097` |

`AUTH` and `AUTHTLS` accept PLAIN, SCRAM-SHA-256, and SCRAM-SHA-512. `TLS`, `AUTHTLS`, and `TLS13`
exist only when you pass the server certificate and its unencrypted key; the CA that signed them is
`../kafka-ssl/client/ca.crt`, which the engines trust through the values in "Values for the platform
services". From this directory:

```sh
helm install ... --set kafka.enabled=true \
  --set-file kafka.tls.serverCert=../kafka-ssl/server/kafka.crt,kafka.tls.serverKey=../kafka-ssl/server/kafka.key
```

Both engines run the `development` profile, which points the Pub/Sub components at the emulator on
`pubsub:8085`. Add `CAMEL_PUBSUB_PREDEPLOY_CHECK_ENABLED=false` to `qip-engine.extraEnv`, as
`docker-compose.yml` does: the predeploy check ignores the emulator and calls Google's API.

Without a host port, forward one from your machine. Kafka advertises `localhost:9092` on `EXTERNAL` then:

```sh
kubectl port-forward -n qip deploy/kafka 9092
kubectl port-forward -n qip deploy/rabbitmq 5672 15672
kubectl port-forward -n qip deploy/pubsub 8085
kubectl port-forward -n qip deploy/sftp 2222:22
```

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
