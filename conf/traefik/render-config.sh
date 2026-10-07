#!/usr/bin/env bash
# Render Traefik configs for Let's Encrypt IP certs.
# Usage:
#   PUBLIC_IP=194.182.171.88 ACME_EMAIL=you@example.com ./conf/traefik/render-config.sh
#   ACME_STAGING=1 ...   # use LE staging directory
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
PUBLIC_IP="${PUBLIC_IP:-${1:-}}"
ACME_EMAIL="${ACME_EMAIL:-admin@example.com}"
ACME_STAGING="${ACME_STAGING:-0}"

if [[ -z "$PUBLIC_IP" ]]; then
  echo "Usage: PUBLIC_IP=x.x.x.x ACME_EMAIL=you@example.com $0" >&2
  exit 1
fi

export PUBLIC_IP ACME_EMAIL

if [[ "$ACME_STAGING" == "1" ]]; then
  CA_LINE='caServer: https://acme-staging-v02.api.letsencrypt.org/directory'
else
  CA_LINE='# caServer: production (default)'
fi

# dynamic.yml
if command -v envsubst >/dev/null 2>&1; then
  envsubst '${PUBLIC_IP}' < "$DIR/dynamic.yml.template" > "$DIR/dynamic.yml"
else
  sed "s/\${PUBLIC_IP}/${PUBLIC_IP}/g" "$DIR/dynamic.yml.template" > "$DIR/dynamic.yml"
fi

# traefik.yml
if command -v envsubst >/dev/null 2>&1; then
  envsubst '${ACME_EMAIL}' < "$DIR/traefik.yml.template" | \
    sed "s|# \${ACME_CA_SERVER_LINE}|${CA_LINE}|" > "$DIR/traefik.yml"
else
  sed "s/\${ACME_EMAIL}/${ACME_EMAIL}/g" "$DIR/traefik.yml.template" | \
    sed "s|# \${ACME_CA_SERVER_LINE}|${CA_LINE}|" > "$DIR/traefik.yml"
fi

touch "$DIR/acme.json"
chmod 600 "$DIR/acme.json"

echo "Rendered Traefik config:"
echo "  PUBLIC_IP=${PUBLIC_IP}"
echo "  ACME_EMAIL=${ACME_EMAIL}"
echo "  ACME_STAGING=${ACME_STAGING}"
echo "  dynamic.yml + traefik.yml + acme.json (600)"
