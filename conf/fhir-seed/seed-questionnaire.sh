#!/usr/bin/env bash
# Push CDSS client-registration Questionnaire + StructureMap to the FHIR store
# (idempotent PUT). Optionally refreshes Composition so remote app id "cdss"
# fetches both resources via fetchNonWorkflowConfigResources.
#
# Source of truth (defaults):
#   android/quest/src/main/assets/configs/cdss/resources/questionnaire/cdss-client-registration.json
#   android/quest/src/main/assets/configs/cdss/resources/structuremap/cdss-client-registration.json
#
# Also refreshes seed copies under:
#   conf/fhir-seed/resources/
#
# Usage:
#   # Public gateway (default POC host) — Questionnaire + StructureMap:
#   ./conf/fhir-seed/seed-questionnaire.sh
#
#   # Explicit host + refresh Composition (recommended after first StructureMap seed):
#   FHIR=https://194.182.171.88/fhir KEYCLOAK=https://194.182.171.88 \
#     UPDATE_COMPOSITION=1 ./conf/fhir-seed/seed-questionnaire.sh
#
#   # HAPI direct (docker network, no auth):
#   FHIR=http://hapi:8080/fhir SKIP_AUTH=1 UPDATE_COMPOSITION=1 \
#     ./conf/fhir-seed/seed-questionnaire.sh
#
#   # Custom files:
#   QUESTIONNAIRE_FILE=/path/to/q.json STRUCTUREMAP_FILE=/path/to/sm.json \
#     ./conf/fhir-seed/seed-questionnaire.sh
#
# Optional env:
#   KEYCLOAK_USER / KEYCLOAK_PASSWORD   (default demo/demo)
#   KEYCLOAK_CLIENT_ID / KEYCLOAK_CLIENT_SECRET
#   APP_ID                             (default cdss) — App-Id header
#   UPDATE_COMPOSITION=1               also PUT Composition (includes StructureMap section)
#   SKIP_STRUCTUREMAP=1                skip StructureMap PUT
#   SKIP_AUTH=1                        no Bearer token (HAPI direct only)

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
SKIP_STRUCTUREMAP="${SKIP_STRUCTUREMAP:-0}"
UPDATE_COMPOSITION="${UPDATE_COMPOSITION:-0}"
CLIENT_ID="${KEYCLOAK_CLIENT_ID:-fhir-core-client}"
CLIENT_SECRET="${KEYCLOAK_CLIENT_SECRET:-}"
USERNAME="${KEYCLOAK_USER:-demo}"
PASSWORD="${KEYCLOAK_PASSWORD:-}"

if [ "$SKIP_AUTH" != "1" ] && { [ -z "$CLIENT_SECRET" ] || [ -z "$PASSWORD" ]; }; then
  echo "Missing credentials. Copy .secrets.example to .secrets and fill it in," >&2
  echo "or export KEYCLOAK_CLIENT_SECRET and KEYCLOAK_PASSWORD." >&2
  exit 1
fi

QUESTIONNAIRE_FILE="${QUESTIONNAIRE_FILE:-$REPO_ROOT/android/quest/src/main/assets/configs/cdss/resources/questionnaire/cdss-client-registration.json}"
STRUCTUREMAP_FILE="${STRUCTUREMAP_FILE:-$REPO_ROOT/android/quest/src/main/assets/configs/cdss/resources/structuremap/cdss-client-registration.json}"
# Named after the resource id being seeded, not hardcoded: with QUESTIONNAIRE_FILE
# pointing elsewhere, fixed names overwrite another resource's seed copy.
res_id() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["id"])' "$1"; }
Q_SEED_COPY="$SEED_DIR/resources/Questionnaire-$(res_id "$QUESTIONNAIRE_FILE").json"
SM_SEED_COPY="$SEED_DIR/resources/StructureMap-$(res_id "$STRUCTUREMAP_FILE").json"
COMPOSITION_FILE="${COMPOSITION_FILE:-$SEED_DIR/Composition-cdss.json}"

if [ ! -f "$QUESTIONNAIRE_FILE" ]; then
  echo "ERROR: questionnaire file not found: $QUESTIONNAIRE_FILE" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "ERROR: jq is required" >&2
  exit 1
fi

QID=$(jq -r '.id // empty' "$QUESTIONNAIRE_FILE")
if [ -z "$QID" ] || [ "$QID" = "null" ]; then
  echo "ERROR: Questionnaire.id missing in $QUESTIONNAIRE_FILE" >&2
  exit 1
fi

RTYPE=$(jq -r '.resourceType // empty' "$QUESTIONNAIRE_FILE")
if [ "$RTYPE" != "Questionnaire" ]; then
  echo "ERROR: expected resourceType Questionnaire, got: $RTYPE" >&2
  exit 1
fi

SMID=""
if [ "$SKIP_STRUCTUREMAP" != "1" ]; then
  if [ ! -f "$STRUCTUREMAP_FILE" ]; then
    echo "ERROR: structuremap file not found: $STRUCTUREMAP_FILE" >&2
    echo "       set SKIP_STRUCTUREMAP=1 to push Questionnaire only" >&2
    exit 1
  fi
  SMID=$(jq -r '.id // empty' "$STRUCTUREMAP_FILE")
  if [ -z "$SMID" ] || [ "$SMID" = "null" ]; then
    echo "ERROR: StructureMap.id missing in $STRUCTUREMAP_FILE" >&2
    exit 1
  fi
  SMRTYPE=$(jq -r '.resourceType // empty' "$STRUCTUREMAP_FILE")
  if [ "$SMRTYPE" != "StructureMap" ]; then
    echo "ERROR: expected resourceType StructureMap, got: $SMRTYPE" >&2
    exit 1
  fi
fi

# Ensure app-id meta.tag so tag-based sync (Option A) finds content resources.
ensure_app_id_tag() {
  local file="$1"
  local app_id="$2"
  python3 - "$file" "$app_id" <<'PY'
import json, sys
path, app_id = sys.argv[1], sys.argv[2]
data = json.load(open(path))
meta = data.setdefault("meta", {})
tags = meta.setdefault("tag", [])
tag = {
    "system": "https://smartregister.org/app-id",
    "code": app_id,
    "display": f"{app_id} application",
}
if not any(t.get("system") == tag["system"] and t.get("code") == tag["code"] for t in tags):
    tags.append(tag)
    json.dump(data, open(path, "w"), indent=2)
    open(path, "a").write("\n")
    print(f"=== tagged   : meta.tag app-id={app_id} on {path}")
else:
    print(f"=== app-tag  : already present on {path}")
PY
}

ensure_app_id_tag "$QUESTIONNAIRE_FILE" "$APP_ID"
if [ "$SKIP_STRUCTUREMAP" != "1" ] && [ -f "$STRUCTUREMAP_FILE" ]; then
  ensure_app_id_tag "$STRUCTUREMAP_FILE" "$APP_ID"
fi

mkdir -p "$SEED_DIR/resources"
cp "$QUESTIONNAIRE_FILE" "$Q_SEED_COPY"
echo "=== questionnaire source  : $QUESTIONNAIRE_FILE"
echo "=== questionnaire seed copy: $Q_SEED_COPY"
echo "=== target  : $FHIR/Questionnaire/$QID"
echo "=== size    : $(wc -c < "$QUESTIONNAIRE_FILE") bytes"
jq -r '"=== title   : \(.title // "?")  status=\(.status // "?")  items=\((.item // []) | length)"' \
  "$QUESTIONNAIRE_FILE"

if [ "$SKIP_STRUCTUREMAP" != "1" ]; then
  cp "$STRUCTUREMAP_FILE" "$SM_SEED_COPY"
  echo "=== structuremap source  : $STRUCTUREMAP_FILE"
  echo "=== structuremap seed copy: $SM_SEED_COPY"
  echo "=== target  : $FHIR/StructureMap/$SMID"
  echo "=== size    : $(wc -c < "$STRUCTUREMAP_FILE") bytes"
  jq -r '"=== name    : \(.name // "?")  url=\(.url // "?")  groups=\((.group // []) | length)"' \
    "$STRUCTUREMAP_FILE"
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
  AUTH_HDR=(-H "Authorization: Bearer $TOKEN" -H "App-Id: $APP_ID")
fi

put_resource() {
  local path="$1"
  local file="$2"
  local code
  code=$(curl -skS -o /tmp/seed-q-resp.json -w "%{http_code}" -X PUT \
    "${AUTH_HDR[@]}" \
    -H "Content-Type: application/fhir+json" \
    -H "Prefer: return=representation" \
    --data-binary @"$file" \
    "$FHIR/$path")
  local vid
  vid=$(jq -r '.meta.versionId // empty' /tmp/seed-q-resp.json 2>/dev/null || true)
  local rtype
  rtype=$(jq -r '.resourceType // empty' /tmp/seed-q-resp.json 2>/dev/null || true)
  echo "PUT $path → HTTP $code (resourceType=$rtype versionId=${vid:-?})"
  if [ "$code" != "200" ] && [ "$code" != "201" ]; then
    head -c 600 /tmp/seed-q-resp.json; echo
    return 1
  fi
  if [ "$rtype" = "OperationOutcome" ]; then
    cat /tmp/seed-q-resp.json
    return 1
  fi
}

verify_resource() {
  local path="$1"
  local local_file="$2"
  local mode="${3:-exact}" # exact | structuremap
  local code
  code=$(curl -skS -o /tmp/seed-q-get.json -w "%{http_code}" \
    "${AUTH_HDR[@]}" \
    "$FHIR/$path")
  echo "GET $path → HTTP $code"
  if [ "$code" != "200" ]; then
    head -c 400 /tmp/seed-q-get.json; echo
    return 1
  fi
  python3 - "$local_file" "$mode" <<'PY'
import json, hashlib, sys

local_path, mode = sys.argv[1], sys.argv[2]
remote = json.load(open("/tmp/seed-q-get.json"))
local = json.load(open(local_path))

def norm(d):
    d = json.loads(json.dumps(d))
    d.pop("meta", None)
    return json.dumps(d, sort_keys=True, separators=(",", ":"))

def rule_names(rules, out=None):
    out = out or []
    for r in rules or []:
        out.append(r.get("name"))
        rule_names(r.get("rule"), out)
    return out

print(
    f"id={remote.get('id')} resourceType={remote.get('resourceType')} "
    f"versionId={remote.get('meta', {}).get('versionId')}"
)

if mode == "structuremap":
    # HAPI may rewrite StructureMap JSON (field order / empty defaults) while
    # preserving the transform graph. Compare identity + rule tree names.
    checks = [
        ("resourceType", local.get("resourceType"), remote.get("resourceType")),
        ("id", local.get("id"), remote.get("id")),
        ("url", local.get("url"), remote.get("url")),
        ("name", local.get("name"), remote.get("name")),
        ("status", local.get("status"), remote.get("status")),
    ]
    for label, a, b in checks:
        if a != b:
            print(f"ERROR: {label} mismatch local={a!r} remote={b!r}")
            sys.exit(1)
    local_groups = [g.get("name") for g in local.get("group") or []]
    remote_groups = [g.get("name") for g in remote.get("group") or []]
    if local_groups != remote_groups:
        print(f"ERROR: group names local={local_groups} remote={remote_groups}")
        sys.exit(1)
    local_rules = rule_names((local.get("group") or [{}])[0].get("rule"))
    remote_rules = rule_names((remote.get("group") or [{}])[0].get("rule"))
    if local_rules != remote_rules:
        print(f"ERROR: rule tree local={local_rules} remote={remote_rules}")
        sys.exit(1)
    print(f"structure match: True (groups={local_groups} rules={len(local_rules)})")
    print(f"url={remote.get('url')}")
else:
    nl, nr = norm(local), norm(remote)
    ok = nl == nr
    print(f"content match (sans meta): {ok}")
    print(f"local  sha256: {hashlib.sha256(nl.encode()).hexdigest()[:16]}")
    print(f"remote sha256: {hashlib.sha256(nr.encode()).hexdigest()[:16]}")
    if not ok:
        sys.exit(1)
PY
}

echo "=== PUT Questionnaire ==="
put_resource "Questionnaire/$QID" "$QUESTIONNAIRE_FILE"

if [ "$SKIP_STRUCTUREMAP" != "1" ]; then
  echo "=== PUT StructureMap ==="
  put_resource "StructureMap/$SMID" "$STRUCTUREMAP_FILE"
fi

if [ "$UPDATE_COMPOSITION" = "1" ]; then
  if [ ! -f "$COMPOSITION_FILE" ]; then
    echo "ERROR: composition file not found: $COMPOSITION_FILE" >&2
    exit 1
  fi
  COMP_ID=$(jq -r '.id' "$COMPOSITION_FILE")
  echo "=== PUT Composition/$COMP_ID (UPDATE_COMPOSITION=1) ==="
  put_resource "Composition/$COMP_ID" "$COMPOSITION_FILE"
fi

echo "=== verify GET ==="
verify_resource "Questionnaire/$QID" "$QUESTIONNAIRE_FILE" exact
if [ "$SKIP_STRUCTUREMAP" != "1" ]; then
  # HAPI rewrites StructureMap JSON slightly; verify transform graph, not byte-identity
  verify_resource "StructureMap/$SMID" "$STRUCTUREMAP_FILE" structuremap
fi

if [ "$UPDATE_COMPOSITION" = "1" ]; then
  COMP_ID=$(jq -r '.id' "$COMPOSITION_FILE")
  code=$(curl -skS -o /tmp/seed-comp-get.json -w "%{http_code}" \
    "${AUTH_HDR[@]}" \
    "$FHIR/Composition/$COMP_ID")
  echo "GET Composition/$COMP_ID → HTTP $code"
  if [ "$code" != "200" ]; then
    head -c 400 /tmp/seed-comp-get.json; echo
    exit 1
  fi
  python3 <<'PY'
import json
comp = json.load(open("/tmp/seed-comp-get.json"))

def walk(sections, acc=None):
    acc = acc or []
    for s in sections or []:
        focus = (s.get("focus") or {}).get("reference")
        if focus:
            acc.append(focus)
        walk(s.get("section"), acc)
    return acc

refs = walk(comp.get("section"))
print("composition focus refs:")
for r in refs:
    print(f"  - {r}")
needed = {
    "Questionnaire/cdss-client-registration",
    "StructureMap/cdss-client-registration",
}
missing = sorted(needed - set(refs))
if missing:
    print("ERROR: Composition missing refs:", ", ".join(missing))
    raise SystemExit(1)
print("Composition includes Questionnaire + StructureMap refs: OK")
PY
fi

echo "OK — Questionnaire pushed."
if [ "$SKIP_STRUCTUREMAP" != "1" ]; then
  echo "OK — StructureMap pushed. App must load StructureMap/cdss-client-registration for extraction."
fi
if [ "$UPDATE_COMPOSITION" = "1" ]; then
  echo "OK — Composition updated. Re-sync app id 'cdss' (or clear data) so the map is fetched."
fi
echo "Re-run this script after editing the JSON resources."
