# Update dependencies
helm dependency update

# Install chart into namespace `<NAMESPACE_NAME>`
helm install <RELEASE_NAME> . -n <NAMESPACE_NAME>
# Example with some custom params
helm install <RELEASE_NAME> . -n <NAMESPACE_NAME> \
--set postgres.storage=50Gi \
--set opensearch.replicas=3

# Remove release and all related resources
helm uninstall <RELEASE_NAME> -n <NAMESPACE_NAME>
# Remove with PersistentVolumeClaims(database data)
helm uninstall <RELEASE_NAME> -n <NAMESPACE_NAME> && kubectl delete pvc -n <NAMESPACE_NAME> --all

# Configuration update
helm upgrade <RELEASE_NAME> . -n <NAMESPACE_NAME> --create-namespace
# Reset settings to values.yaml
helm upgrade <RELEASE_NAME> . -n <NAMESPACE_NAME> --reset-values

# Install subchart
helm install postgres ./postgres -n <NAMESPACE_NAME> --set storage=20Gi

helm install opensearch ./opensearch -n <NAMESPACE_NAME> --set replicas=2

# Releases list
helm list -n <NAMESPACE_NAME>

# Modification history
helm history <RELEASE_NAME> -n <NAMESPACE_NAME>

# Rollback to version
helm rollback <RELEASE_NAME> 1 -n <NAMESPACE_NAME>


# Work with kubectl 
# Get pods
kubectl get pods -n <NAMESPACE_NAME> -w

# Get pod logs
kubectl logs -n <NAMESPACE_NAME> <POD_NAME> --tail=100 -f

# Apply manifest
kubectl apply -f <PATH_TO_FILE> -n <NAMESPACE_NAME>

# Remove namespace data
kubectl delete all,secrets,configmaps,pvc,roles,networkpolicy,rolebinding -n <NAMESPACE_NAME> --all

# Remove release
helm uninstall dev -n <NAMESPACE_NAME>

# Forward ports
kubectl port-forward svc/qip-ui 8080:8080 -n dev
