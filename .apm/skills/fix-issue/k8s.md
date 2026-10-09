# The Kubernetes target and micro-engine

Companion to `SKILL.md`, gates 2 and 4. Read it when the issue concerns micro-engine, a micro
domain, the Helm chart, Istio, or the Gateway API, or when the fix touches `engine` and its
`micro-engine` twin. Compose cannot run a micro domain; only the cluster can.

Setting the cluster up (Gateway API, Istio, camel-k, metrics-server, the NodePorts) is in
`e2e/k8s/README.md`. Changing a cluster needs the user's permission, because the auto-mode
classifier blocks workload and route changes; ask once at gate 0 when the issue needs the
cluster. Turning on Docker Desktop's Kubernetes restarts Docker and stops the Compose stack.

## Where things are

| What | Where |
|---|---|
| Namespace | `qip-e2e`, owned by the end-to-end suite; context `docker-desktop` |
| Catalog, classic engine, proxy | NodePorts 30091, 30092, 30080 |
| A route on a micro domain | `http://localhost:30080/e2e/svc/qip-engine-<domain>-v1/routes/<contextPath>` |
| The compiled Camel XML of a micro domain | ConfigMaps labeled `qip-domain=<domain>`, field `.data.content`; there is no `deployments/update` for micro |
| The Integration a domain runs | `kubectl get integration -n qip-e2e qip-engine-<domain>-v1` |

## Reproduce in a domain of your own

Seed chains through the catalog as on Compose, then deploy them to a micro domain named after the
run token, so no other session's domain changes under you:

```bash
curl -s -X POST localhost:30091/v1/cr/deploy-chains -H 'Content-Type: application/json' \
  -d '{"chainIds":["<id>"],"domains":["r<N>"]}'
curl -s -X DELETE localhost:30091/v1/cr/r<N>            # at the end: Integration, Service, ConfigMaps, routes
```

To run the branch, build the image with the profile the chart supports (`e2e/k8s/README.md`
says why) and point only your domain's Integration at it:

```bash
mvn -B -f "$WT/pom.xml" -pl micro-engine -am package -DskipTests -Dgpg.skip=true -Dquarkus.profile=development,no-m2m
docker build -t ghcr.io/netcracker/qubership-integration-micro-engine:r<N> "$WT/micro-engine"
kubectl patch integration -n qip-e2e qip-engine-r<N>-v1 --type merge \
  -p '{"spec":{"traits":{"container":{"image":"ghcr.io/netcracker/qubership-integration-micro-engine:r<N>"}}}}'
```

Leave the shared Deployments to Helm. `kubectl set image` on one of them makes the next
`helm upgrade` from the end-to-end suite fail with a field-ownership conflict, and a `helm upgrade` of
your own replaces what other sessions are measuring. Run specs with
`CIP_TARGET=k8s E2E_PROVISION=never`.

## Compare three columns

A fix that spans `engine` and `micro-engine` is verified on both, live. Present the result as one
table with a column each for classic, micro on `main`, and micro on the branch, so the reviewer
sees parity and the fix at once. `e2e/support/catalog.ts` and `e2e/support/kube.ts` hold the
helpers the suite uses for the same calls.
