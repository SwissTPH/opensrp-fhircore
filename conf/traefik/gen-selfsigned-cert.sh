#!/usr/bin/env bash
# Generate a self-signed TLS cert + Java truststore for Traefik / internal Sentry.
# Usage: ./conf/traefik/gen-selfsigned-cert.sh [PUBLIC_IP]
set -euo pipefail

IP="${1:-127.0.0.1}"
DIR="$(cd "$(dirname "$0")" && pwd)/certs"
mkdir -p "$DIR"

# Include compose DNS names so HAPI/gateway can use https://traefik/logs/... (Sentry DSN)
SAN="IP:${IP},IP:127.0.0.1,DNS:localhost,DNS:traefik,DNS:opensrp-traefik"

openssl req -x509 -nodes -newkey rsa:2048 -days 825 \
  -keyout "$DIR/cert.key" \
  -out "$DIR/cert.crt" \
  -subj "/CN=${IP}/O=OpenSRP-POC/C=CH" \
  -addext "subjectAltName=${SAN}"

chmod 644 "$DIR/cert.crt"
chmod 600 "$DIR/cert.key"

# Truststore for Java Sentry clients inside the compose network
STOREPASS="${TRUSTSTORE_PASSWORD:-changeit}"
rm -f "$DIR/truststore.jks"
keytool -importcert -noprompt \
  -alias opensrp-traefik \
  -file "$DIR/cert.crt" \
  -keystore "$DIR/truststore.jks" \
  -storepass "$STOREPASS" >/dev/null

chmod 644 "$DIR/truststore.jks"

echo "Wrote $DIR/cert.crt, cert.key, truststore.jks"
echo "SAN: ${SAN}"
echo "Truststore password: ${STOREPASS}"
echo "Note: browsers will warn (self-signed). Let's Encrypt needs a DNS name."
