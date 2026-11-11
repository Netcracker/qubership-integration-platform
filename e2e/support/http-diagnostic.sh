#!/usr/bin/env bash
# Temporary instrumentation for the missing response in PR #1179.
set -u
base=${E2E_HTTP_DIAGNOSTIC_DIR:?}
mkdir -p "$base"
script=$(realpath "$0")
collect_access() {
  timeout 10 kubectl -n cip-e2e exec deploy/cip-runtime-catalog-v1 -- cat /tmp/http-access/access.log > "$1/access.log" 2>&1
}
pod_netns() {
  local pod sandbox relative node_pid
  pod=$(kubectl -n cip-e2e get pods -l app=cip-runtime-catalog-v1 -o jsonpath='{.items[0].metadata.name}')
  sandbox=$(docker exec qip-control-plane crictl pods --namespace cip-e2e --name "$pod" -q | head -1)
  [ -n "$sandbox" ] || return 1
  docker exec qip-control-plane crictl inspectp "$sandbox" > "$base/pod-sandbox.json"
  relative=$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(next(n["path"] for n in d["info"]["runtimeSpec"]["linux"]["namespaces"] if n["type"]=="network"))' "$base/pod-sandbox.json")
  [ -n "$relative" ] || return 1
  node_pid=$(docker inspect -f '{{.State.Pid}}' qip-control-plane)
  printf '/proc/%s/root%s\n' "$node_pid" "$relative"
}
snapshot() {
  local out=$1
  mkdir -p "$out"
  date -u +%FT%T.%NZ > "$out/time.txt"
  collect_access "$out" &
  timeout 12 kubectl -n cip-e2e exec deploy/cip-runtime-catalog-v1 -- sh -c 'pid=$(jcmd -l | awk "/qip-runtime-catalog.jar/ {print \$1; exit}"); jcmd "$pid" Thread.print -l' > "$out/threads.txt" 2>&1 &
  timeout 12 kubectl -n cip-e2e exec deploy/cip-postgres -- sh -c 'PGPASSWORD="$POSTGRES_PASSWORD" psql -X -U "$POSTGRES_USER" -d "$POSTGRES_DB" -P pager=off -c "SELECT pid, application_name, client_addr, client_port, xact_start, query_start, state, wait_event_type, wait_event, query_id, pg_blocking_pids(pid) AS blockers FROM pg_stat_activity; SELECT pid, locktype, relation::regclass, mode, granted FROM pg_locks;"' > "$out/postgres.txt" 2>&1 &
  timeout 8 ss -tinop > "$out/host-sockets.txt" 2>&1 &
  local node_pid
  node_pid=$(docker inspect -f '{{.State.Pid}}' qip-control-plane)
  timeout 8 sudo -n nsenter -t "$node_pid" -n ss -tinop > "$out/node-sockets.txt" 2>&1 &
  timeout 8 sudo -n nsenter -t "$node_pid" -n sh -c 'cat /proc/net/stat/nf_conntrack; sysctl net.netfilter.nf_conntrack_count net.netfilter.nf_conntrack_max' > "$out/conntrack.txt" 2>&1 &
  if [ -s "$base/pod-netns.path" ]; then
    timeout 8 sudo -n nsenter --net="$(cat "$base/pod-netns.path")" ss -tinop > "$out/pod-sockets.txt" 2>&1 &
    timeout 8 sudo -n nsenter --net="$(cat "$base/pod-netns.path")" iptables-save > "$out/pod-iptables.txt" 2>&1 &
  fi
  timeout 10 kubectl -n istio-system logs ds/ztunnel --all-containers --since=30m --tail=10000 > "$out/ztunnel.log" 2>&1 &
  timeout 10 kubectl -n istio-system logs ds/istio-cni-node --all-containers --since=30m --tail=1000 > "$out/cni.log" 2>&1 &
  wait
}
case ${1:?} in
  start)
    nohup bash "$script" watch > "$base/watch.log" 2>&1 < /dev/null &
    echo "$!" > "$base/monitor.pid"
    ;;
  watch)
    host_capture=
    node_capture=
    pod_capture=
    finish() {
      if [ -n "$host_capture" ]; then sudo -n kill "$host_capture" 2>/dev/null || true; fi
      if [ -n "$node_capture" ]; then sudo -n kill "$node_capture" 2>/dev/null || true; fi
      if [ -n "$pod_capture" ]; then sudo -n kill "$pod_capture" 2>/dev/null || true; fi
      wait || true
    }
    trap finish EXIT
    trap 'exit 0' TERM INT
    sudo -n timeout 3300 tcpdump -nn -tttt -q -l -i any 'tcp port 30091' > "$base/host-packets.txt" 2>&1 &
    host_capture=$!
    for ((i=0; i<3300; i++)); do
      if [ -z "$node_capture" ]; then
        pod_ip=$(kubectl -n cip-e2e get pods -l app=cip-runtime-catalog-v1 -o jsonpath='{.items[0].status.podIP}' 2>/dev/null || true)
        if [ -n "$pod_ip" ]; then
          node_pid=$(docker inspect -f '{{.State.Pid}}' qip-control-plane)
          sudo -n timeout 3300 nsenter -t "$node_pid" -n tcpdump -nn -tttt -q -l -i any "tcp port 30091 or (host $pod_ip and tcp port 8080)" > "$base/node-packets.txt" 2>&1 &
          node_capture=$!
        fi
      fi
      if [ -z "$pod_capture" ]; then
        netns=$(pod_netns 2>/dev/null || true)
        if [ -n "$netns" ]; then
          echo "$netns" > "$base/pod-netns.path"
          sudo -n timeout 3300 nsenter --net="$netns" tcpdump -nn -tttt -q -l -i any 'tcp port 8080 or tcp port 15006 or tcp port 15008' > "$base/pod-packets.txt" 2>&1 &
          pod_capture=$!
        fi
      fi
      for trigger in "$base"/slow-*.trigger; do
        stage=$(basename "$trigger" | cut -d- -f2)
        if [ -f "$trigger" ] && mkdir "$base/slow-$stage-snapshot" 2>/dev/null; then
          cp "$trigger" "$base/slow-$stage-snapshot/request-id.txt"
          snapshot "$base/slow-$stage-snapshot" &
        fi
      done
      sleep 1
    done
    ;;
  stop)
    if [ -f "$base/monitor.pid" ]; then kill "$(cat "$base/monitor.pid")" 2>/dev/null || true; fi
    for ((i=0; i<150; i++)); do
      if [ ! -f "$base/monitor.pid" ] || ! kill -0 "$(cat "$base/monitor.pid")" 2>/dev/null; then break; fi
      sleep 0.1
    done
    mkdir -p "$base/final"
    snapshot "$base/final"
    if [ -f "$base/forward.pid" ]; then kill "$(cat "$base/forward.pid")" 2>/dev/null || true; fi
    grep -q 'rid=diag-' "$base/final/access.log" || exit 1
    test -s "$base/node-packets.txt" || exit 1
    test -s "$base/host-packets.txt" || exit 1
    grep -q '^[0-9].* IP' "$base/pod-packets.txt" || exit 1
    ;;
  forward)
    kubectl -n cip-e2e port-forward --address 127.0.0.1 deploy/cip-runtime-catalog-v1 39091:8080 > "$base/port-forward.log" 2>&1 &
    echo "$!" > "$base/forward.pid"
    for ((i=0; i<30; i++)); do
      if curl --max-time 1 -fsS http://127.0.0.1:39091/actuator/health/readiness > /dev/null; then exit 0; fi
      sleep 1
    done
    exit 1
    ;;
  snapshot)
    snapshot "$base/manual-snapshot"
    ;;
esac
