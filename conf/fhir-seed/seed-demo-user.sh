#!/usr/bin/env bash
# Seed FHIR resources that link Keycloak demo user → PractitionerDetail.
#
# After Keycloak login the Android app calls:
#   GET {FHIR_BASE}/PractitionerDetail?keycloak-uuid={Keycloak sub}
# The gateway looks up Practitioner.identifier = that UUID (use=secondary).
#
# OpenSRP does NOT create Practitioner records in the gateway admin UI.
# Production uses fhir-web for user provisioning; this script is the POC path.
#
# Usage:
#   # Via public gateway (needs demo JWT with FHIR write roles):
#   FHIR=https://194.182.171.88/fhir KEYCLOAK=https://194.182.171.88 \
#     ./conf/fhir-seed/seed-demo-user.sh
#
#   # Via HAPI direct (internal docker network, no auth):
#   FHIR=http://hapi:8080/fhir SKIP_AUTH=1 ./conf/fhir-seed/seed-demo-user.sh
#
# Optional env:
#   KEYCLOAK_USER / KEYCLOAK_PASSWORD  (default demo/demo)
#   KEYCLOAK_CLIENT_ID / KEYCLOAK_CLIENT_SECRET
#   APP_ID  (default cdss) — App-Id header for gateway ACL
#   KC_SUB  override Keycloak subject UUID written on Practitioner.identifier

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"

# Credentials live in .secrets at the repo root, which git ignores; see .secrets.example.
SECRETS_FILE="${SECRETS_FILE:-$REPO_ROOT/.secrets}"
if [ -f "$SECRETS_FILE" ]; then
  # shellcheck disable=SC1090
  . "$SECRETS_FILE"
fi
FHIR="${FHIR:-https://194.182.171.88/fhir}"
KEYCLOAK="${KEYCLOAK:-https://194.182.171.88}"
APP_ID="${APP_ID:-cdss}"
SKIP_AUTH="${SKIP_AUTH:-0}"
KC_SUB="${KC_SUB:-8920acad-dd7e-4f82-90ea-dd1d76712747}"
CLIENT_ID="${KEYCLOAK_CLIENT_ID:-fhir-core-client}"
CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-}"
USERNAME="${KEYCLOAK_USER:-demo}"
PASSWORD="${KEYCLOAK_PASSWORD:-}"

if [ "$SKIP_AUTH" != "1" ] && { [ -z "$CLIENT_SECRET" ] || [ -z "$PASSWORD" ]; }; then
  echo "Missing credentials. Copy .secrets.example to .secrets and fill it in," >&2
  echo "or export KEYCLOAK_CLIENT_SECRET and KEYCLOAK_PASSWORD." >&2
  exit 1
fi

AUTH_HDR=()
if [ "$SKIP_AUTH" != "1" ]; then
  echo "=== token ($USERNAME @ $KEYCLOAK) ==="
  TOKEN=$(curl -skS -X POST "$KEYCLOAK/realms/opensrp/protocol/openid-connect/token" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    -d "grant_type=password" \
    -d "client_id=$CLIENT_ID" \
    -d "client_secret=$CLIENT_SECRET" \
    -d "username=$USERNAME" \
    -d "password=$PASSWORD" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
  # Prefer live sub from token when available
  LIVE_SUB=$(echo "$TOKEN" | python3 -c '
import sys,base64,json
t=sys.stdin.read().strip().split(".")[1]
t+=("="*((4-len(t)%4)%4))
print(json.loads(base64.urlsafe_b64decode(t)).get("sub",""))
')
  if [ -n "$LIVE_SUB" ]; then
    KC_SUB="$LIVE_SUB"
  fi
  AUTH_HDR=(-H "Authorization: Bearer $TOKEN" -H "App-Id: $APP_ID")
fi

echo "=== Keycloak sub (Practitioner secondary identifier) = $KC_SUB ==="

# Ensure Practitioner JSON uses current sub
PRAC_TMP=$(mktemp)
python3 - "$ROOT/demo-user/Practitioner-poc-demo.json" "$KC_SUB" "$PRAC_TMP" <<'PY'
import json, sys
src, sub, dst = sys.argv[1:4]
with open(src) as f:
    p = json.load(f)
for ident in p.get("identifier", []):
    if ident.get("use") == "secondary":
        ident["value"] = sub
with open(dst, "w") as f:
    json.dump(p, f, indent=2)
    f.write("\n")
PY

put() {
  local type_id="$1"
  local file="$2"
  local id
  id=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$file")
  echo -n "PUT $type_id/$id ... "
  code=$(curl -skS -o /tmp/seed-resp.json -w "%{http_code}" -X PUT \
    "${AUTH_HDR[@]}" \
    -H "Content-Type: application/fhir+json" \
    --data-binary @"$file" \
    "$FHIR/$type_id/$id")
  echo "$code"
  if [ "$code" != "200" ] && [ "$code" != "201" ]; then
    head -c 400 /tmp/seed-resp.json; echo
    return 1
  fi
}

# Order matters: orgs/locations before roles that reference them
put Organization "$ROOT/demo-user/Organization-poc-demo.json"
put Location "$ROOT/demo-user/Location-poc-demo.json"
put Practitioner "$PRAC_TMP"
put PractitionerRole "$ROOT/demo-user/PractitionerRole-poc-demo.json"
put CareTeam "$ROOT/demo-user/CareTeam-poc-demo.json"
put OrganizationAffiliation "$ROOT/demo-user/OrganizationAffiliation-poc-demo.json"

rm -f "$PRAC_TMP"

if [ "$SKIP_AUTH" != "1" ]; then
  echo "=== verify PractitionerDetail ==="
  curl -skS -o /tmp/pd-verify.json -w "HTTP %{http_code}\n" \
    "${AUTH_HDR[@]}" \
    "$FHIR/PractitionerDetail?keycloak-uuid=$KC_SUB"
  python3 - <<'PY'
import json
with open("/tmp/pd-verify.json") as f:
    d = json.load(f)
if d.get("resourceType") == "OperationOutcome":
    print(json.dumps(d, indent=2)[:800])
    raise SystemExit(1)
total = d.get("total", len(d.get("entry") or []))
print(f"Bundle total={total}")
if total < 1:
    raise SystemExit("PractitionerDetail empty — check identifier / roles")
entry = d["entry"][0]["resource"]
print("id=", entry.get("id"))
print("OK — re-login on the Android app as demo/demo")
PY
else
  echo "OK — seeded via HAPI direct (skip auth verify)"
fi
