#!/usr/bin/env bash
set -euo pipefail
umask 077

[[ $# == 3 ]] || {
    echo 'Usage: hosted_prepare.sh TRUSTED_CHECKOUT CANDIDATE_CHECKOUT HOSTED_DIR' >&2
    exit 2
}
trusted=$(realpath -e -- "$1")
candidate=$(realpath -e -- "$2")
hosted=$(realpath -e -- "$3")
[[ "$trusted/" != "$candidate/"* && "$candidate/" != "$trusted/"* &&
    "$hosted/" != "$candidate/"* && "$candidate/" != "$hosted/"* &&
    "$hosted/" != "$trusted/"* && "$trusted/" != "$hosted/"* ]] || {
    echo 'The trusted checkout, candidate checkout, and hosted directory must be separate.' >&2
    exit 1
}

read_pin() {
    local value
    value=$(sed -nE "s/^[[:space:]]+$1: ([^[:space:]]+)$/\\1/p" "$trusted/.github/workflows/_e2e-kind.yaml")
    [[ "$value" =~ $2 ]] || {
        echo "Invalid $1 pin in _e2e-kind.yaml." >&2
        return 1
    }
    printf '%s\n' "$value"
}

admitted_sha=$(jq -er '.base_sha | select(test("^[0-9a-f]{40}$"))' "$hosted/assignment/issue.json")
[[ "$(git -C "$candidate" rev-parse HEAD)" == "$admitted_sha" && -z "$(git -C "$candidate" status --porcelain)" ]] || {
    echo 'The candidate checkout must be clean and match the admitted base commit.' >&2
    exit 1
}

: "${JAVA_HOME:?Set Java 21 before preparing the hosted worker}"
: "${GITHUB_RUN_ID:?}" "${GITHUB_RUN_ATTEMPT:?}"
if [[ -n "${QIP_PUBLIC_MAVEN_REPOSITORY:-}" ]]; then
    maven_settings="$HOME/maven-settings.xml"
    printf '<settings />\n' > "$maven_settings"
    chmod 400 "$maven_settings"
    maven_args="-B -Dgpg.skip=true -Dmaven.repo.local=$QIP_PUBLIC_MAVEN_REPOSITORY -s $maven_settings -gs $maven_settings"
else
    maven_user=${MAVEN_GITHUB_USERNAME:?Set the Maven GitHub Packages username}
    maven_token=${MAVEN_GITHUB_PASSWORD:?Set the read-only Maven GitHub Packages token}
    maven_args="-B -Dgpg.skip=true -Dmaven.repo.local=$HOME/.m2/repository -s $trusted/.github/maven-settings.xml"
fi
unset MAVEN_GITHUB_USERNAME MAVEN_GITHUB_PASSWORD

kind_version=$(read_pin KIND_VERSION '^v[0-9]+\.[0-9]+\.[0-9]+$')
kind_hash=$(read_pin KIND_SHA256 '^[a-f0-9]{64}$')
istio_version=$(read_pin ISTIO_VERSION '^[0-9]+\.[0-9]+\.[0-9]+$')
istio_hash=$(read_pin ISTIOCTL_SHA256 '^[a-f0-9]{64}$')
mkdir -p "$hosted/bin" "$hosted/preparation"
(
    cd "$hosted/bin"
    curl --retry 3 --retry-delay 2 -fsSL -o kind "https://github.com/kubernetes-sigs/kind/releases/download/$kind_version/kind-linux-amd64"
    printf '%s  kind\n' "$kind_hash" | sha256sum -c -
    chmod +x kind
    curl --retry 3 --retry-delay 2 -fsSL -o istioctl.tar.gz "https://github.com/istio/istio/releases/download/$istio_version/istioctl-$istio_version-linux-amd64.tar.gz"
    printf '%s  istioctl.tar.gz\n' "$istio_hash" | sha256sum -c -
    tar -xzf istioctl.tar.gz istioctl
    rm istioctl.tar.gz
)

export PATH="$hosted/bin:$PATH"
export KUBECONFIG="$hosted/kubeconfig"
export PLAYWRIGHT_BROWSERS_PATH="$hosted/playwright"
export npm_config_cache="$HOME/.npm"
export CIP_TARGET=k8s CIP_REPO_ROOT="$candidate" E2E_BUILD=all E2E_WORKERS=4
export E2E_BOOTSTRAP_LOG="$hosted/preparation/bootstrap.log"
export E2E_BROKERS="${QIP_AGENT_BROKERS:-1}"

started=$(date +%s)
df -h "$candidate" > "$hosted/preparation/disk.txt"
(
    result=0
    bash "$trusted/e2e/k8s/bootstrap-cluster.sh" > "$E2E_BOOTSTRAP_LOG" 2>&1 || result=$?
    printf '%s\n' "$result" > "$E2E_BOOTSTRAP_LOG.tmp"
    mv "$E2E_BOOTSTRAP_LOG.tmp" "$E2E_BOOTSTRAP_LOG.exit"
) < /dev/null &
bootstrap_pid=$!

(
    npm --prefix "$candidate" ci --legacy-peer-deps
    npm --prefix "$candidate/e2e" ci
    cd "$candidate/e2e"
    npx --no-install playwright install --with-deps chromium
) 2>&1 | tee "$hosted/preparation/dependencies.log"

provisioner="$trusted/tools/autonomous-fixer/scripts/hosted_provision.mjs"
if [[ -n "${QIP_PUBLIC_MAVEN_REPOSITORY:-}" ]]; then
    printf '%s\n' 'Revision preparation uses the restored Maven cache and public repositories. Run QIP agent revision cache on the default branch if a private dependency is missing.'
    MAVEN_ARGS="$maven_args" node --experimental-transform-types "$provisioner" stand "$candidate" 2>&1 | tee "$hosted/preparation/stand.log"
else
    MAVEN_ARGS="$maven_args" MAVEN_GITHUB_USERNAME="$maven_user" MAVEN_GITHUB_PASSWORD="$maven_token" \
        node --experimental-transform-types "$provisioner" stand "$candidate" 2>&1 | tee "$hosted/preparation/stand.log"
fi
wait "$bootstrap_pid"

elapsed=$(($(date +%s) - started))
printf '\nAfter setup (%s seconds):\n' "$elapsed" >> "$hosted/preparation/disk.txt"
df -h "$candidate" >> "$hosted/preparation/disk.txt"
jq -n \
    --arg environment_id "kind-qip-$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT" \
    --arg workspace "$candidate" \
    --arg path "$PATH" \
    --arg java "$JAVA_HOME" \
    --arg kubeconfig "$KUBECONFIG" \
    --arg browsers "$PLAYWRIGHT_BROWSERS_PATH" \
    --arg npm_cache "$npm_config_cache" \
    --arg maven_args "$maven_args" \
    --arg brokers "$E2E_BROKERS" \
    '{environment_id: $environment_id, workspace: $workspace, path: $path, environment: {
      JAVA_HOME: $java, KUBECONFIG: $kubeconfig, PLAYWRIGHT_BROWSERS_PATH: $browsers,
      npm_config_cache: $npm_cache, MAVEN_ARGS: $maven_args, CIP_TARGET: "k8s",
      E2E_PROVISION: "never", E2E_BUILD: "all", E2E_WORKERS: "4", E2E_BROKERS: $brokers
    }}' > "$hosted/runner.json"
