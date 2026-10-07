#!/bin/sh
# Idempotent Keycloak POC setup via kcadm (roles + demo user mapping).
# Run inside the keycloak container or: docker exec keycloak sh /path/to/setup-poc-roles.sh
set -e

KC_URL="${KC_URL:-http://127.0.0.1:8080}"
KC_ADMIN="${KEYCLOAK_ADMIN:-admin}"
KC_PASS="${KEYCLOAK_ADMIN_PASSWORD:-admin}"
REALM="${KEYCLOAK_REALM:-opensrp}"
CLIENT_ID="${KEYCLOAK_CLIENT_ID:-fhir-core-client}"

echo "=== kcadm login ==="
/opt/keycloak/bin/kcadm.sh config credentials \
  --server "$KC_URL" --realm master --user "$KC_ADMIN" --password "$KC_PASS"

# Common FHIR resources used by OpenSRP apps
# OrganizationAffiliation links org → location (required for Location sync strategy).
# PractitionerDetail is the gateway custom endpoint used right after login.
# Keep aligned with cdss sync_config + TRICC content (ActivityDefinition, ValueSet, …).
# Gateway PermissionAccessChecker expects realm roles METHOD_RESOURCETYPE (uppercased).
RESOURCES="Patient Encounter Observation Condition Task CarePlan Questionnaire QuestionnaireResponse Group List Location Organization Practitioner PractitionerRole CareTeam RelatedPerson Binary Composition StructureMap PlanDefinition Library Measure MeasureReport Flag Immunization Bundle ValueSet CodeSystem OrganizationAffiliation PractitionerDetail ActivityDefinition MedicationRequest"
METHODS="GET POST PUT DELETE"

echo "=== ensure realm roles ==="
for r in $RESOURCES; do
  R=$(echo "$r" | tr '[:lower:]' '[:upper:]')
  for m in $METHODS; do
    ROLE="${m}_${R}"
    /opt/keycloak/bin/kcadm.sh create roles -r "$REALM" -s name="$ROLE" 2>/dev/null \
      && echo "  created $ROLE" \
      || true
  done
done
/opt/keycloak/bin/kcadm.sh create roles -r "$REALM" -s name=ALL_LOCATIONS 2>/dev/null || true
# Gateway treats these as realm roles (JwtUtils reads realm_access.roles only)
/opt/keycloak/bin/kcadm.sh create roles -r "$REALM" -s name=ANDROID_CLIENT 2>/dev/null || true
/opt/keycloak/bin/kcadm.sh create roles -r "$REALM" -s name=WEB_CLIENT 2>/dev/null || true

echo "=== enable unmanaged user attributes (Keycloak 24+) ==="
# fhir_core_app_id is a custom attribute; must be allowed by user profile
/opt/keycloak/bin/kcadm.sh update "users/profile" -r "$REALM" \
  -s 'unmanagedAttributePolicy=ENABLED' 2>/dev/null || true

echo "=== ensure PROVIDER group ==="
GROUP_ID=$(/opt/keycloak/bin/kcadm.sh get groups -r "$REALM" -q search=PROVIDER --fields id,name \
  | sed -n 's/.*"id" : "\([^"]*\)".*/\1/p' | head -1)
if [ -z "$GROUP_ID" ]; then
  /opt/keycloak/bin/kcadm.sh create groups -r "$REALM" -s name=PROVIDER
  GROUP_ID=$(/opt/keycloak/bin/kcadm.sh get groups -r "$REALM" -q search=PROVIDER --fields id,name \
    | sed -n 's/.*"id" : "\([^"]*\)".*/\1/p' | head -1)
fi
echo "  PROVIDER group id=$GROUP_ID"

echo "=== map all POC roles to PROVIDER ==="
for r in $RESOURCES; do
  R=$(echo "$r" | tr '[:lower:]' '[:upper:]')
  for m in $METHODS; do
    ROLE="${m}_${R}"
    /opt/keycloak/bin/kcadm.sh add-roles -r "$REALM" --gid "$GROUP_ID" --rolename "$ROLE" 2>/dev/null || true
  done
done
/opt/keycloak/bin/kcadm.sh add-roles -r "$REALM" --gid "$GROUP_ID" --rolename ALL_LOCATIONS 2>/dev/null || true
/opt/keycloak/bin/kcadm.sh add-roles -r "$REALM" --gid "$GROUP_ID" --rolename ANDROID_CLIENT 2>/dev/null || true

echo "=== ensure demo user ==="
USER_ID=$(/opt/keycloak/bin/kcadm.sh get users -r "$REALM" -q username=demo --fields id \
  | sed -n 's/.*"id" : "\([^"]*\)".*/\1/p' | head -1)
if [ -z "$USER_ID" ]; then
  /opt/keycloak/bin/kcadm.sh create users -r "$REALM" \
    -s username=demo -s enabled=true -s email=demo@opensrp.local \
    -s firstName=Demo -s lastName=User -s emailVerified=true
  USER_ID=$(/opt/keycloak/bin/kcadm.sh get users -r "$REALM" -q username=demo --fields id \
    | sed -n 's/.*"id" : "\([^"]*\)".*/\1/p' | head -1)
  /opt/keycloak/bin/kcadm.sh set-password -r "$REALM" --userid "$USER_ID" --new-password demo
fi
echo "  demo user id=$USER_ID"

# app id claim for gateway / app
/opt/keycloak/bin/kcadm.sh update "users/$USER_ID" -r "$REALM" \
  -s 'attributes.fhir_core_app_id=["cdss/debug"]' 2>/dev/null || true

# join PROVIDER group
/opt/keycloak/bin/kcadm.sh update "users/$USER_ID/groups/$GROUP_ID" -r "$REALM" -n 2>/dev/null \
  || /opt/keycloak/bin/kcadm.sh create "users/$USER_ID/groups/$GROUP_ID" -r "$REALM" -n 2>/dev/null \
  || true

# Also assign roles directly on user (belt and suspenders) — full RESOURCES set
for r in $RESOURCES; do
  R=$(echo "$r" | tr '[:lower:]' '[:upper:]')
  for m in GET POST PUT DELETE; do
    /opt/keycloak/bin/kcadm.sh add-roles -r "$REALM" --uid "$USER_ID" --rolename "${m}_${R}" 2>/dev/null || true
  done
done
/opt/keycloak/bin/kcadm.sh add-roles -r "$REALM" --uid "$USER_ID" --rolename ALL_LOCATIONS 2>/dev/null || true
/opt/keycloak/bin/kcadm.sh add-roles -r "$REALM" --uid "$USER_ID" --rolename ANDROID_CLIENT 2>/dev/null || true

echo "=== ensure fhir_core_app_id client mapper ==="
CID=$(/opt/keycloak/bin/kcadm.sh get clients -r "$REALM" -q clientId="$CLIENT_ID" --fields id \
  | sed -n 's/.*"id" : "\([^"]*\)".*/\1/p' | head -1)
if [ -n "$CID" ]; then
  EXISTS=$(/opt/keycloak/bin/kcadm.sh get "clients/$CID/protocol-mappers/models" -r "$REALM" \
    | grep -c 'fhir_core_app_id' || true)
  if [ "$EXISTS" = "0" ]; then
    /opt/keycloak/bin/kcadm.sh create "clients/$CID/protocol-mappers/models" -r "$REALM" \
      -s name=fhir_core_app_id \
      -s protocol=openid-connect \
      -s protocolMapper=oidc-usermodel-attribute-mapper \
      -s 'config."user.attribute"=fhir_core_app_id' \
      -s 'config."claim.name"=fhir_core_app_id' \
      -s 'config."access.token.claim"=true' \
      -s 'config."id.token.claim"=true' \
      -s 'config."userinfo.token.claim"=true' \
      -s 'config."jsonType.label"=String'
    echo "  mapper created"
  else
    echo "  mapper exists"
  fi
fi

echo "=== done ==="
echo "Re-login as demo/demo; token should include realm_access.roles and fhir_core_app_id."
