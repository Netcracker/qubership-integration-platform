# Update dependencies
helm dependency update

# Install chart into namespace `default`
helm install <RELEASE_NAME> . -n default
# Example with some custom params
helm install <RELEASE_NAME> . -n default \
--set postgres.storage=50Gi \
--set opensearch.replicas=3

# Remove release and all related resources
helm uninstall <RELEASE_NAME> -n default
# Remove with PersistentVolumeClaims(database data)
helm uninstall <RELEASE_NAME> -n default && kubectl delete pvc -n default --all

# Configuration update
helm upgrade <RELEASE_NAME> . -n default
# Reset settings to values.yaml
helm upgrade <RELEASE_NAME> . -n default --reset-values

# Install subchart
helm install postgres ./postgres -n default --set storage=20Gi

helm install opensearch ./opensearch -n default --set replicas=2

# Releases list
helm list -n default

# Modification history
helm history <RELEASE_NAME> -n default

# Rollback to version
helm rollback <RELEASE_NAME> 1 -n default


# Work with kubectl 
# Get pods
kubectl get pods -n default -w

# Get pod logs
kubectl logs -n default <POD_NAME> --tail=100 -f

# Apply manifest
kubectl apply -f <PATH_TO_FILE> -n default

# Remove namespace data
kubectl delete all,secrets,configmaps,pvc -n default --all