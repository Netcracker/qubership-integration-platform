#!/usr/bin/env bash
# Temporary instrumentation for the missing response in PR #1179.
set -u
base=${E2E_HTTP_DIAGNOSTIC_DIR:?}
mkdir -p "$base"
script=$(realpath "$0")
collect_access() {
  timeout 10 kubectl -n cip-e2e exec deploy/cip-runtime-catalog-v1 -- cat /tmp/http-access/access.log > "$1/access.log" 2>&1
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
    finish() {
      if [ -n "$host_capture" ]; then sudo -n kill "$host_capture" 2>/dev/null || true; fi
      if [ -n "$node_capture" ]; then sudo -n kill "$node_capture" 2>/dev/null || true; fi
      wait || true
    }
    trap finish EXIT
    trap 'exit 0' TERM INT
    sudo -n timeout 3300 tcpdump -nn -tttt -q -l -i any 'tcp port 30091' > "$base/host-packets.txt" 2>&1 &
    host_capture=$!
    for ((i=0; i<3300; i++)); do
      if [ -z "$node_capture" ]; then
        pod_ip=$(kubectl -n cip-e2e get pods -l app=cip-runtime-catalog -o jsonpath='{.items[0].status.podIP}' 2>/dev/null || true)
        if [ -n "$pod_ip" ]; then
          node_pid=$(docker inspect -f '{{.State.Pid}}' qip-control-plane)
          sudo -n timeout 3300 nsenter -t "$node_pid" -n tcpdump -nn -tttt -q -l -i any "tcp port 30091 or (host $pod_ip and tcp port 8080)" > "$base/node-packets.txt" 2>&1 &
          node_capture=$!
        fi
      fi
      for trigger in "$base"/slow-*.trigger; do
        if [ -f "$trigger" ] && mkdir "$base/slow-snapshot" 2>/dev/null; then
          cp "$trigger" "$base/slow-snapshot/request-id.txt"
          snapshot "$base/slow-snapshot" &
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
    collect_access "$base/final" || true
    ;;
  snapshot)
    snapshot "$base/manual-snapshot"
    ;;
esac
