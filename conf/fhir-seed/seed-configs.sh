#!/usr/bin/env bash
# Push the generated config Binaries and the Composition for app id "cdss".
#
# build-binaries.sh turns android/quest/src/main/assets/configs/cdss/ into
# generated/Binary-<uuid>.json plus Composition-cdss.json; this uploads them.
# seed-questionnaire.sh covers Questionnaire/StructureMap, not these.
#
# Env:
#   FHIR / KEYCLOAK                     default the shared host
#   KEYCLOAK_USER / KEYCLOAK_PASSWORD
#   KEYCLOAK_CLIENT_ID / KEYCLOAK_CLIENT_SECRET
#   APP_ID                              default cdss
#   SKIP_AUTH=1                         no Bearer token
#   DRY_RUN=1                           list what would be sent, send nothing
set -euo pipefail

SEED_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SEED_DIR/../.." && pwd)"

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
DRY_RUN="${DRY_RUN:-0}"
CLIENT_ID="${KEYCLOAK_CLIENT_ID:-fhir-core-client}"
CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-}"
USERNAME="${KEYCLOAK_USER:-demo}"
PASSWORD="${KEYCLOAK_PASSWORD:-}"

if [ "$SKIP_AUTH" != "1" ] && { [ -z "$CLIENT_SECRET" ] || [ -z "$PASSWORD" ]; }; then
  echo "Missing credentials. Copy .secrets.example to .secrets and fill it in," >&2
  echo "or export KEYCLOAK_CLIENT_SECRET and KEYCLOAK_PASSWORD." >&2
  exit 1
fi

COMPOSITION_FILE="$SEED_DIR/Composition-cdss.json"
GENERATED="$SEED_DIR/generated"

[ -f "$COMPOSITION_FILE" ] || { echo "missing $COMPOSITION_FILE - run build-binaries.sh"; exit 1; }
shopt -s nullglob
BINARIES=("$GENERATED"/Binary-*.json)
[ ${#BINARIES[@]} -gt 0 ] || { echo "no binaries in $GENERATED - run build-binaries.sh"; exit 1; }

echo "=== target $FHIR (app id $APP_ID) ==="
echo "=== ${#BINARIES[@]} Binary + 1 Composition ==="

if [ "$DRY_RUN" = "1" ]; then
  for f in "${BINARIES[@]}"; do echo "would PUT Binary/$(basename "$f" .json | sed 's/^Binary-//')"; done
  echo "would PUT Composition/$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$COMPOSITION_FILE")"
  exit 0
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
    -d "password=$PASSWORD" \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
  AUTH_HDR=(-H "Authorization: Bearer $TOKEN" -H "App-Id: $APP_ID")
fi

put_resource() {
  local path="$1" file="$2" code rtype
  code=$(curl -skS -o /tmp/seed-cfg-resp.json -w "%{http_code}" -X PUT \
    "${AUTH_HDR[@]}" \
    -H "Content-Type: application/fhir+json" \
    --data-binary @"$file" \
    "$FHIR/$path")
  rtype=$(python3 -c 'import json,sys;print(json.load(open("/tmp/seed-cfg-resp.json")).get("resourceType",""))' 2>/dev/null || true)
  echo "PUT $path -> HTTP $code ($rtype)"
  if [ "$code" != "200" ] && [ "$code" != "201" ] || [ "$rtype" = "OperationOutcome" ]; then
    head -c 500 /tmp/seed-cfg-resp.json; echo
    return 1
  fi
}

for f in "${BINARIES[@]}"; do
  put_resource "Binary/$(basename "$f" .json | sed 's/^Binary-//')" "$f"
done

COMP_ID=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["id"])' "$COMPOSITION_FILE")
put_resource "Composition/$COMP_ID" "$COMPOSITION_FILE"

echo "=== verifying every Composition section resolves ==="
curl -skS "${AUTH_HDR[@]}" "$FHIR/Composition/$COMP_ID" -o /tmp/seed-cfg-comp.json
python3 - "$FHIR" "${AUTH_HDR[@]}" <<'PY'
import json, subprocess, sys
fhir = sys.argv[1]
auth = sys.argv[2:]  # the -H pairs, so the check runs as the same principal
comp = json.load(open("/tmp/seed-cfg-comp.json"))
refs = []
def walk(sections):
    for s in sections:
        f = s.get("focus")
        if f and f.get("reference"):
            refs.append((f["reference"], f.get("identifier", {}).get("value", "?")))
        walk(s.get("section", []))
walk(comp.get("section", []))
bad = []
for ref, name in refs:
    code = subprocess.run(
        ["curl", "-skS", "-o", "/dev/null", "-w", "%{http_code}", *auth, f"{fhir}/{ref}"],
        capture_output=True, text=True).stdout.strip()
    print(f"  {name:<20} {ref:<50} HTTP {code}")
    if code not in ("200",):
        bad.append((name, ref, code))
print(f"{len(refs)} section references, {len(bad)} unresolved")
sys.exit(1 if bad else 0)
PY
echo "Done. Re-sync app id '$APP_ID' on the device to pick the new configs up."
