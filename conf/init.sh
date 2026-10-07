#!/bin/sh
set -e

LOCK_FILE="/conf/.setup.lock"

# Allow re-render when FORCE_INIT_SETUP=1 (e.g. after GlitchTip DSN appears)
if [ -f "$LOCK_FILE" ] && [ "${FORCE_INIT_SETUP:-0}" != "1" ]; then
  echo "Setup already completed – skipping (FORCE_INIT_SETUP=1 to re-run)."
  exit 0
fi

echo "=== Installing tools ==="
apk add --no-cache curl gettext > /dev/null

echo "=== Running OpenSRP HAPI config setup ==="

echo "Waiting for opensrp realm to be imported..."
i=0
until [ "$(curl -s -o /dev/null -w '%{http_code}' http://keycloak:8080/realms/opensrp)" = "200" ]; do
  echo "Waiting... (attempt $i)"
  sleep 4
  i=$((i+1))
  if [ $i -ge 60 ]; then
    echo "ERROR: Timeout waiting for realm!"
    exit 1
  fi
done

echo "Realm ready!"

export KEYCLOAK_CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-fhir-core-client-secret-2025}"
export POSTGRES_DB="${POSTGRES_DB:-hapi}"
export POSTGRES_USER="${POSTGRES_USER:-hapi}"
export POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-hapi123!}"

# Prefer internal Traefik HTTPS DSN for HAPI (Java → https://traefik/logs/...)
if [ -f /conf/glitchtip/public-dsn.hapi.internal.txt ]; then
  export SENTRY_DSN_HAPI="$(tr -d '\n' < /conf/glitchtip/public-dsn.hapi.internal.txt)"
  export SENTRY_ENABLED=true
  echo "Sentry/GlitchTip DSN for HAPI: internal (traefik HTTPS)"
elif [ -f /conf/glitchtip/public-dsn.hapi.txt ]; then
  export SENTRY_DSN_HAPI="$(tr -d '\n' < /conf/glitchtip/public-dsn.hapi.txt)"
  export SENTRY_ENABLED=true
  echo "Sentry/GlitchTip DSN for HAPI: public"
else
  export SENTRY_DSN_HAPI=""
  export SENTRY_ENABLED=false
  echo "Sentry/GlitchTip DSN for HAPI: not found (sentry disabled)"
fi

mkdir -p /conf/hapi

echo "=== Generating application.yaml from template ==="
envsubst '${KEYCLOAK_CLIENT_SECRET} ${POSTGRES_DB} ${POSTGRES_USER} ${POSTGRES_PASSWORD} ${SENTRY_DSN_HAPI} ${SENTRY_ENABLED}' \
  < /conf/hapi/application.yaml.template \
  > /conf/hapi/application.yaml

echo "=== Configuration generated ==="
echo "   DB     : ${POSTGRES_USER}@postgres-hapi/${POSTGRES_DB}"
echo "   Sentry : enabled=${SENTRY_ENABLED}"

touch "$LOCK_FILE"
echo "Setup finished successfully!"
