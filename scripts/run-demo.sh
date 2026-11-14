#!/usr/bin/env bash
# Run the actual demo CLI twice: once with a client identity and once without it.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
umask 077
mkdir -p build
run_dir=$(mktemp -d build/demo.XXXXXX)
echo "Demo logs: $run_dir"
export VPN_STORE_PASSWORD
VPN_STORE_PASSWORD=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
bash scripts/create-demo-certs.sh "$run_dir/certs"
bash scripts/build.sh
certs="$run_dir/certs"
demo=(java -cp build/classes vpn.demo.TunnelDemo)
server_pid=
cleanup() {
  if [[ -n $server_pid ]]; then
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

run_case() {
  local mode=$1 port= server_log="$run_dir/$1-server.log" client_log="$run_dir/$1-client.log"
  local server_status=0 client_status=0
  local identity=()
  if [[ $mode == trusted ]]; then identity=("$certs/client.p12"); fi
  "${demo[@]}" server 127.0.0.1 0 "$certs/server.p12" "$certs/trust-client.p12" >"$server_log" 2>&1 &
  server_pid=$!
  for ((i=0; i<150; i++)); do
    port=$(sed -n 's/^READY port=\([0-9][0-9]*\)\r*$/\1/p' "$server_log")
    [[ -n $port ]] && break
    kill -0 "$server_pid" 2>/dev/null || break
    sleep 0.1
  done
  if [[ -z $port ]]; then cat "$server_log" >&2; return 1; fi
  "${demo[@]}" client 127.0.0.1 "$port" "$certs/trust-server.p12" "${identity[@]}" >"$client_log" 2>&1 || client_status=$?
  # A client that exits before connecting leaves the one-shot listener waiting.
  for ((i=0; i<100; i++)); do
    kill -0 "$server_pid" 2>/dev/null || break
    sleep 0.1
  done
  if kill -0 "$server_pid" 2>/dev/null; then cat "$client_log" >&2; return 1; fi
  wait "$server_pid" || server_status=$?
  server_pid=
  cat "$server_log" "$client_log"
  if [[ $mode == trusted ]]; then
    [[ $server_status == 0 && $client_status == 0 ]]
    grep -q '^DONE packets=3' "$server_log"
    [[ $(grep -c '^PASS packet=' "$client_log") == 3 ]]
  else
    [[ $server_status == 1 && $client_status == 1 ]]
    grep -q '^Demo failed: SSLHandshakeException' "$server_log"
    if grep -q '^ECHO packet=' "$server_log"; then return 1; fi
  fi
  printf 'PASS demo: %s\n' "$mode"
}
run_case trusted
run_case missing-cert
