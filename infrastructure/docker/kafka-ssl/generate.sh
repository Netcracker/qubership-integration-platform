#!/usr/bin/env bash
#
# Generate the test TLS material for the Kafka TLS listeners: a CA, and a server certificate for the
# names kafka and localhost signed by it. Test material only, committed so every run trusts the same CA.
# The CA key is discarded, so running the script again replaces all three files.
#
# Writes client/ca.crt (the engines trust it), server/kafka.crt, and server/kafka.key (unencrypted PKCS#8).
#
# Usage: infrastructure/docker/kafka-ssl/generate.sh

set -euo pipefail

days=36500
dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$dir/client" "$dir/server"

openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$work/ca.key"
openssl req -x509 -new -key "$work/ca.key" -subj "/CN=QIP e2e test CA" -days "$days" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out "$dir/client/ca.crt"

openssl genpkey -quiet -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$dir/server/kafka.key"
openssl req -new -key "$dir/server/kafka.key" -subj "/CN=kafka" -out "$work/kafka.csr"
cat > "$work/kafka.ext" << 'EOF'
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:kafka,DNS:localhost
EOF
openssl x509 -req -in "$work/kafka.csr" -CA "$dir/client/ca.crt" -CAkey "$work/ca.key" \
    -CAcreateserial -CAserial "$work/ca.srl" -days "$days" -extfile "$work/kafka.ext" \
    -out "$dir/server/kafka.crt"
