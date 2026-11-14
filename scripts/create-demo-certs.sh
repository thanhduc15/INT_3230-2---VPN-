#!/usr/bin/env bash
# Create fresh loopback identities for this lab run.
set -euo pipefail
umask 077
if [[ $# != 1 || -z ${VPN_STORE_PASSWORD:-} ]]; then
  echo "Usage: set VPN_STORE_PASSWORD and run $0 NEW_DIRECTORY" >&2
  exit 2
fi
dest=$1
mkdir -- "$dest"  # Refuse to overwrite an existing identity.
for role in server client; do
  extensions=(-ext EKU=clientAuth)
  if [[ $role == server ]]; then
    extensions=(-ext EKU=serverAuth -ext SAN=ip:127.0.0.1)
  fi
  keytool -genkeypair -alias "$role" -keyalg EC -groupname secp256r1 \
    -dname "CN=VPN Lab $role" "${extensions[@]}" -validity 7 \
    -storetype PKCS12 -keystore "$dest/$role.p12" -storepass:env VPN_STORE_PASSWORD
  keytool -exportcert -alias "$role" -keystore "$dest/$role.p12" \
    -storepass:env VPN_STORE_PASSWORD -file "$dest/$role.cer"
done
for role in server client; do
  keytool -importcert -noprompt -alias "$role" -file "$dest/$role.cer" \
    -storetype PKCS12 -keystore "$dest/trust-$role.p12" -storepass:env VPN_STORE_PASSWORD
done
