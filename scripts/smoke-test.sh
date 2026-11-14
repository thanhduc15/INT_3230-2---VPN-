#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "$0")/.."

mkdir -p build
test_dir=$(mktemp -d build/smoke.XXXXXX)
mkdir -p "$test_dir/classes"

for alias in server other; do
  keytool -genkeypair -alias "$alias" -keyalg EC -groupname secp256r1 \
    -dname "CN=java-vpn-smoke" -ext SAN=ip:127.0.0.1 -validity 7 \
    -storetype PKCS12 -keystore "$test_dir/$alias.p12" -storepass demo-only
  keytool -exportcert -alias "$alias" -keystore "$test_dir/$alias.p12" \
    -storepass demo-only -file "$test_dir/$alias.cer"
done

keytool -importcert -noprompt -alias server -file "$test_dir/server.cer" \
  -storetype PKCS12 -keystore "$test_dir/trusted.p12" -storepass demo-only
keytool -importcert -noprompt -alias other -file "$test_dir/other.cer" \
  -storetype PKCS12 -keystore "$test_dir/untrusted.p12" -storepass demo-only

mapfile -d '' sources < <(find src/main/java src/test/java -name '*.java' -print0)
javac --release 17 -d "$test_dir/classes" "${sources[@]}"
java -cp "$test_dir/classes" vpn.core.CoreSmoke "$test_dir"
java -cp "$test_dir/classes" vpn.core.PacketFramesTest
