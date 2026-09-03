# Qubership Integration Platform - Helm charts for local development

## Installation

```sh
helm repo add camel-k https://apache.github.io/camel-k/charts/
helm install camel-k camel-k/camel-k -n camel-k --create-namespace --set 'operator.global="true"'
helm install --create-namespace --namespace qip qip .
```

## UI

The UI available on [http://localhost:30080/](http://localhost:30080/) via NodePort service.
You still need to serve the UI locally, since this Helm chart only installs an nginx-based proxy pointing back to your host.

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
