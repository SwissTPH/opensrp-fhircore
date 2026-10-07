# FHIR Gateway allowlists (CDSS / TRICC)

OpenSRP gateway (`onaio/fhir-gateway-plugin`) applies **sync strategy filters**
(org / location / care-team `_tag`) to Android client GETs unless the request
matches `SYNC_FILTER_IGNORE_RESOURCES_FILE`.

## Why this directory exists

Upstream default `resources/hapi_sync_filter_ignored_queries.json` only documents
content resources with **`_id`** query params (Composition pin path).

For multi-form **Option A** (tag + sync), the app issues:

```text
StructureMap?_tag=https://smartregister.org/app-id|cdss&_count=…
PlanDefinition?_tag=https://smartregister.org/app-id|cdss&_count=…
Questionnaire?_tag=…
Library?_tag=…
ValueSet?_tag=…
```

If those requests are **not** skipped, the gateway **also injects** the
practitioner’s org/location `_tag`. HAPI then ANDs tags and content packages
(only tagged with app-id) disappear.

`hapi_sync_filter_ignored_queries.json` here:

| Addition | Why |
|----------|-----|
| Explicit `_tag: ANY_VALUE` for Q / SM / PD / Library / AD / CodeSystem | Tag-based content sync without org filter |
| `ValueSet` `_id` + `_tag` | Upstream omitted ValueSet; TRICC emits ValueSets |

## Compose wiring

`compose.fhir.yml` mounts this folder and points env vars at absolute paths:

```yaml
SYNC_FILTER_IGNORE_RESOURCES_FILE: /data/gateway/hapi_sync_filter_ignored_queries.json
ALLOWED_QUERIES_FILE: /data/gateway/hapi_page_url_allowed_queries.json
```

(Gateway loads ignore list via `FileReader`, so absolute host-mounted paths work.)

Restart gateway after edits:

```bash
docker compose -f compose.yml -f compose.fhir.yml up -d gateway
```

## Verify

```bash
# should return tagged StructureMaps (not empty due to org AND)
curl -sk -H "Authorization: Bearer $TOKEN" -H "App-Id: cdss" \
  "https://host/fhir/StructureMap?_tag=https://smartregister.org/app-id%7Ccdss&_count=50"
```

## App-id tag contract

Content packages must stamp:

```json
"meta": {
  "tag": [{
    "system": "https://smartregister.org/app-id",
    "code": "cdss",
    "display": "CDSS application"
  }]
}
```

Shell Composition `identifier.value` remains `cdss` (app bootstrap only).
