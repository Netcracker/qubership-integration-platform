# Cloud Integration Platform - Helm charts for local development

## Installation

```sh
helm repo add camel-k https://apache.github.io/camel-k/charts/
helm install camel-k camel-k/camel-k -n camel-k --create-namespace --set 'operator.global="true"'
helm install --create-namespace --namespace qip qip .
```

## UI

The UI available on http://localhost:30080/ via NodePort service.
You still need to serve the UI locally, since this Helm chart only installs an nginx-based proxy pointing back to your host.

## Remove namespace data
kubectl delete all,secrets,configmaps,pvc -n <NAMESPACE> --all
