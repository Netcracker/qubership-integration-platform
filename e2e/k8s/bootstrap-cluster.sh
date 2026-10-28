#!/usr/bin/env bash
#
# Prepare a cluster for the k8s target of the e2e suite: Gateway API, Istio, the camel-k operator,
# and metrics-server (k3s ships its own). Creates the kind cluster kind-qip when the current context
# is not Docker Desktop, k3d, or kind. Safe to run again on a prepared cluster.
#
# Usage: e2e/k8s/bootstrap-cluster.sh

set -euo pipefail

kind_node_image="kindest/node:v1.37.0@sha256:a1ed56cfb0e7b93589bdf97c8cd566405a265939e3620fc4f5de89adff580ae5"
gateway_api_version="v1.6.3"
camel_k_chart_version="2.11.0"
metrics_server_version="v0.9.0"

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"

require() {
    for tool in "$@"; do
        command -v "$tool" > /dev/null || {
            echo "$tool is not on PATH" >&2
            exit 1
        }
    done
}

require kubectl helm istioctl

context="$(kubectl config current-context 2> /dev/null || true)"
case "$context" in
    docker-desktop | k3d-* | kind-*)
        echo "Cluster: using context $context, creation skipped"
        ;;
    *)
        require kind
        echo "Cluster: creating kind-qip"
        kind create cluster --name qip --image "$kind_node_image" --config - << 'EOF'
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
# The services log at DEBUG for the suite, and the kubelet's default 10Mi rotation left the cluster
# dump of run 40378789767 36 lines of the catalog's log.
kubeadmConfigPatches:
  - |
    kind: KubeletConfiguration
    containerLogMaxSize: 500Mi
nodes:
  - role: control-plane
    extraPortMappings:
      - { containerPort: 30080, hostPort: 30080 }
      - { containerPort: 30091, hostPort: 30091 }
      - { containerPort: 30092, hostPort: 30092 }
      - { containerPort: 30093, hostPort: 30093 }
      - { containerPort: 30095, hostPort: 30095 }
      - { containerPort: 30992, hostPort: 30992 }
      - { containerPort: 30672, hostPort: 30672 }
      - { containerPort: 30085, hostPort: 30085 }
      - { containerPort: 30022, hostPort: 30022 }
EOF
        ;;
esac

echo "Gateway API: applying the standard channel $gateway_api_version"
kubectl apply --server-side --force-conflicts \
    -f "https://github.com/kubernetes-sigs/gateway-api/releases/download/$gateway_api_version/standard-install.yaml"

echo "Istio: installing from infrastructure/helm/qip-dev/istio.yaml"
istioctl install -y -f "$repo_root/infrastructure/helm/qip-dev/istio.yaml"
echo # istioctl leaves its last line unterminated

echo "camel-k: installing chart $camel_k_chart_version"
helm repo add --force-update camel-k https://apache.github.io/camel-k/charts/ > /dev/null
helm upgrade --install --wait camel-k camel-k/camel-k --version "$camel_k_chart_version" \
    -n camel-k --create-namespace --set-string operator.global=true

if [[ "$context" == k3d-* ]]; then
    echo "metrics-server: k3s ships its own, skipped"
else
    # Reapplying the manifest reverts the --kubelet-insecure-tls patch, so apply only a missing or different version.
    image="$(kubectl -n kube-system get deployment metrics-server --ignore-not-found \
        -o jsonpath='{.spec.template.spec.containers[0].image}')"
    if [[ "$image" == *":$metrics_server_version" ]]; then
        echo "metrics-server: $metrics_server_version already installed"
    else
        echo "metrics-server: applying $metrics_server_version"
        kubectl apply -f "https://github.com/kubernetes-sigs/metrics-server/releases/download/$metrics_server_version/components.yaml"
    fi
    args="$(kubectl -n kube-system get deployment metrics-server -o jsonpath='{.spec.template.spec.containers[0].args}')"
    if [[ "$args" == *--kubelet-insecure-tls* ]]; then
        echo "metrics-server: --kubelet-insecure-tls already set"
    else
        echo "metrics-server: adding --kubelet-insecure-tls"
        kubectl -n kube-system patch deployment metrics-server --type=json \
            -p '[{"op":"add","path":"/spec/template/spec/containers/0/args/-","value":"--kubelet-insecure-tls"}]'
    fi
    kubectl -n kube-system rollout status deployment metrics-server --timeout=180s
fi
