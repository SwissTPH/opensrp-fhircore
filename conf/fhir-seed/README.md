# CDSS bare FHIR seed (Composition + UUID Binary configs)

## Application id

| Context | Value |
|---------|--------|
| Server Composition `identifier.value` | **`cdss`** |
| Android local debug | **`cdss/debug`** |
| Gateway `App-Id` header (client strips `/debug`) | **`cdss`** |

Assets: `android/quest/src/main/assets/configs/cdss/`

## Why UUIDs for Binary / Composition ids

Other OpenSRP packages use ids like `Binary/eef55e…` so resources do not collide across apps on a shared FHIR server. This seed uses **stable UUID v5** ids (see `resource-ids.json`), not `cdss-application-config` style names.

## Package contents (bare shell)

| Resource | Role |
|----------|------|
| Composition (`identifier=cdss`) | Gateway ACL + config manifest |
| Binary (application, navigation, sync) | App shell |
| Binary (clientRegister, clientProfile) | All-clients register + profile |

Includes `Questionnaire/cdss-client-registration` + `StructureMap/cdss-client-registration`
(Add client + SDC StructureMap extraction). No TRICC PlanDefinitions yet.

## Shell vs TRICC content packages

This seed is the **shell** Composition only (`identifier.system = https://smartregister.org/app-id`,
`value = cdss`). It must stay generic: Binary app configs + registration Questionnaire/StructureMap.

TRICC form exports each produce their **own package Composition** (different identifier system /
value). Those are **not** the app-id Composition. OpenSRP currently loads **one** Composition for
the app id (`entryFirstRep`); it does **not** multi-search package Compositions.

Preferred multi-form delivery: tag clinical resources with the app id and pull them via **sync**
SearchParameters (see `android/feature/register-tricc.md` Part V-bis). Do **not** set package
Composition `identifier.value = cdss` (collides with the shell).

### Sync config (tag-based content)

`android/quest/src/main/assets/configs/cdss/sync_config.json` pulls:

| Kind | Query |
|------|--------|
| Content (Q, SM, PD, Library, AD, ValueSet, CodeSystem) | `_tag=https://smartregister.org/app-id\|cdss` |
| Clinical (Patient, QR, Task, RelatedPerson, …) | org / strategy filters via gateway |
| Registration Q/SM on shell Composition | still listed for first-time `fetchNonWorkflowConfigResources` |

Rebuild + re-upload the sync Binary after editing:

```bash
./conf/fhir-seed/build-binaries.sh
# PUT conf/fhir-seed/generated/Binary-cd4dfbc2-….json  (id from resource-ids.json)
```

### Gateway: tag-aware sync-filter skip list (**required**)

Default gateway ignore list only mentions content types with `_id`. For `_tag` content
sync, Android client requests must **skip** org/location filter injection, or HAPI ANDs
app-id + org tags and returns nothing.

POC override lives under **`conf/gateway/`** (mounted in `compose.fhir.yml`):

- `hapi_sync_filter_ignored_queries.json` — adds `_tag: ANY_VALUE` for content types + ValueSet
- See `conf/gateway/README.md`

Remote POC hosts must deploy that file (or equivalent) on the gateway image/host.

### Keycloak roles for sync resource types

Gateway `PermissionAccessChecker` requires realm roles `GET_{RESOURCETYPE}` (uppercase).
If sync_config adds a type (e.g. `ActivityDefinition`, `MedicationRequest`) without a matching
role, the Android log shows:

```text
SERVER ERROR - HTTP 403 | User is not authorized to GET …/ActivityDefinition?_tag=…
```

POC fix: `keycloak/setup-poc-roles.sh` (includes ActivityDefinition + MedicationRequest) or
realm import `keycloak/import/opensrp-realm.json`. After adding roles, **re-login** so the JWT
picks them up.

## Rebuild seed from assets

```bash
./conf/fhir-seed/build-binaries.sh
```

## Upload

### Bootstrap via HAPI direct (recommended if gateway blocks empty store)

```bash
HAPI=http://localhost:8082/fhir   # or internal host

./conf/fhir-seed/build-binaries.sh

for f in conf/fhir-seed/generated/Binary-*.json; do
  id=$(jq -r .id "$f")
  curl -sS -X PUT -H 'Content-Type: application/fhir+json' \
    --data-binary @"$f" "$HAPI/Binary/$id"
  echo " Binary/$id"
done

comp_id=$(jq -r .id conf/fhir-seed/Composition-cdss.json)
curl -sS -X PUT -H 'Content-Type: application/fhir+json' \
  --data-binary @conf/fhir-seed/Composition-cdss.json \
  "$HAPI/Composition/$comp_id"
echo " Composition/$comp_id"
```

### Via gateway (after Composition exists)

Use Bearer token + `App-Id: cdss`.

## Client registration Questionnaire + StructureMap

Source of truth:

- `android/quest/src/main/assets/configs/cdss/resources/questionnaire/cdss-client-registration.json`
- `android/quest/src/main/assets/configs/cdss/resources/structuremap/cdss-client-registration.json`

The questionnaire declares:

`sdc-questionnaire-targetStructureMap` → `https://fhir.opensrp.io/cdss/StructureMap/cdss-client-registration`

On save, the app loads `StructureMap/cdss-client-registration` from the local FHIR store
(via `ResourceMapper.extract`). That resource must be seeded (and listed on Composition for
remote app id `cdss`).

```bash
# Push Questionnaire + StructureMap (idempotent PUT)
./conf/fhir-seed/seed-questionnaire.sh

# Recommended: also refresh Composition (Questionnaire + StructureMap sections)
UPDATE_COMPOSITION=1 ./conf/fhir-seed/seed-questionnaire.sh

# Or with explicit endpoints:
FHIR=https://194.182.171.88/fhir KEYCLOAK=https://194.182.171.88 \
  UPDATE_COMPOSITION=1 ./conf/fhir-seed/seed-questionnaire.sh

# Questionnaire only:
SKIP_STRUCTUREMAP=1 ./conf/fhir-seed/seed-questionnaire.sh
```

## Android

1. Rebuild app.
2. Clear app data if a stale id (`quest`, `app/debug`) was saved.
3. Enter **`cdss/debug`** (loads assets) **or** **`cdss`** (loads Composition from server once seeded).

## Not recommended

Special-casing an app id to skip Composition on the gateway. Always seed `Composition.identifier=cdss`.

## Demo user FHIR assignment (post-login PractitionerDetail)

After Keycloak login the app calls:

```text
GET /fhir/PractitionerDetail?keycloak-uuid={Keycloak user sub}
```

The gateway plugin looks up a **Practitioner** whose `identifier.value` equals that UUID
(typically `use: secondary`). It then aggregates CareTeam, Organization, PractitionerRole,
OrganizationAffiliation → Location.

| Layer | What it does |
|-------|----------------|
| **Keycloak** | Auth only (`demo` / `demo`). Does **not** create FHIR Practitioners. |
| **FHIR Info Gateway** | Proxy + plugins (`PractitionerDetail`, `LocationHierarchy`). **No user-management console.** |
| **fhir-web** (not in this POC) | Admin UI that creates Keycloak user + Practitioner + PractitionerRole together. |
| **This seed** | POC stand-in for fhir-web user provisioning. |

### Upload demo assignment

```bash
# Needs demo JWT write roles + GET_PRACTITIONERDETAIL (see keycloak/setup-poc-roles.sh)
FHIR=https://194.182.171.88/fhir KEYCLOAK=https://194.182.171.88 \
  ./conf/fhir-seed/seed-demo-user.sh
```

Resources live under `conf/fhir-seed/demo-user/`. The script rewrites the Practitioner secondary
identifier from the live JWT `sub` when possible.

If you recreate the Keycloak `demo` user, re-run the seed so the secondary identifier matches.
