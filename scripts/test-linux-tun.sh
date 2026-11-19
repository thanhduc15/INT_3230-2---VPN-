#!/usr/bin/env bash
# A self-contained Ubuntu lab. Only the temporary namespaces get VPN routes.
set -euo pipefail
cd -- "$(dirname -- "$0")/.."
if [[ $(uname -s) != Linux || $EUID != 0 ]]; then
  echo 'Run with sudo on Linux (network namespaces and /dev/net/tun required).' >&2
  exit 2
fi
for tool in ip ping curl python3 java javac keytool; do command -v "$tool" >/dev/null; done
if [[ ${VPN_CAPTURE:-0} == 1 ]]; then command -v tcpdump >/dev/null; fi
[[ -c /dev/net/tun ]] || { echo 'Missing /dev/net/tun' >&2; exit 2; }
umask 077
mkdir -p build
run=$(mktemp -d build/tun.XXXXXX)
echo "Lab logs: $run"
tag=${run##*.}
client=vpn-c-$tag
server=vpn-s-$tag
created=()
pids=()
cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  for pid in "${pids[@]}"; do wait "$pid" 2>/dev/null || true; done
  for ns in "${created[@]}"; do
    while read -r pid; do
      [[ -z $pid ]] || kill -KILL "$pid" 2>/dev/null || true
    done < <(ip netns pids "$ns")
    ip netns del "$ns"
  done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
bash scripts/build.sh
export VPN_STORE_PASSWORD
VPN_STORE_PASSWORD=$(od -An -N24 -tx1 /dev/urandom | tr -d ' \n')
bash scripts/create-demo-certs.sh "$run/certs" 192.0.2.1
for ns in "$client" "$server"; do
  ip netns add "$ns"
  created+=("$ns")
  ip -n "$ns" link set lo up
done
ip -n "$server" link add wire0 type veth peer name wire0 netns "$client"
ip -n "$server" addr add 192.0.2.1/30 dev wire0
ip -n "$client" addr add 192.0.2.2/30 dev wire0
for ns in "$client" "$server"; do
  ip -n "$ns" link set wire0 up
  ip netns exec "$ns" ip tuntap add dev vpn0 mode tun
  ip -n "$ns" link set vpn0 mtu 1400 up
done
ip -n "$server" addr add 10.8.0.1/30 dev vpn0
ip -n "$client" addr add 10.8.0.2/30 dev vpn0
mkdir "$run/www"
printf 'VPN-LAB-%s\n' "$tag" >"$run/www/index.html"
ip netns exec "$server" python3 -m http.server 8000 --bind 10.8.0.1 --directory "$run/www" >"$run/http.log" 2>&1 &
pids+=("$!")
cli=(java -cp build/classes vpn.demo.TunVpn)
certs="$run/certs"
ip netns exec "$server" "${cli[@]}" server 192.0.2.1 8443 vpn0 "$certs/server.p12" "$certs/trust-client.p12" >"$run/server.log" 2>&1 &
pids+=("$!")
wait_log() {
  local process=$1 log=$2 marker=$3
  for ((i=0; i<200; i++)); do
    if grep -q "$marker" "$log"; then return 0; fi
    kill -0 "$process" 2>/dev/null || break
    sleep 0.1
  done
  cat "$log" >&2
  return 1
}
wait_log "$!" "$run/server.log" '^READY'
# Capture the encrypted transport and the corresponding inner packets for Wireshark.
if [[ ${VPN_CAPTURE:-0} == 1 ]]; then
  ip netns exec "$server" tcpdump -U -n -i wire0 -w "$run/outer-tls.pcap" 'tcp port 8443' >"$run/capture-outer.log" 2>&1 &
  pids+=("$!")
  wait_log "$!" "$run/capture-outer.log" 'listening on'

fi
ip netns exec "$client" "${cli[@]}" client 192.0.2.1 8443 vpn0 "$certs/client.p12" "$certs/trust-server.p12" >"$run/client.log" 2>&1 &
client_pid=$!
pids+=("$client_pid")
wait_log "$client_pid" "$run/client.log" '^TUN connected'
ip -n "$client" route get 10.8.0.1 | grep -w vpn0
ip netns exec "$client" ping -n -c 3 -W 2 10.8.0.1
url=http://10.8.0.1:8000/
fetch_page() { ip netns exec "$client" curl --noproxy '*' -fsS --max-time 4 "$url"; }
[[ $(fetch_page) == "VPN-LAB-$tag" ]]
echo 'PASS: internal HTTP over the tunnel'
kill "$client_pid"
wait "$client_pid" || true
kill -0 "${pids[0]}"  # The HTTP service is still running on the other side.
if fetch_page >/dev/null 2>&1; then echo 'HTTP still reachable after disconnect' >&2; exit 1; fi
echo 'PASS: internal HTTP unreachable after VPN disconnect'
