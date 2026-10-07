#!/usr/bin/env bash
# Build FHIR Binary resources (UUID ids) + Composition for app id "cdss".
# Sources: android/quest/src/main/assets/configs/cdss/
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
ASSETS="$ROOT/android/quest/src/main/assets/configs/cdss"
SEED="$(cd "$(dirname "$0")" && pwd)"
OUT="$SEED/generated"
mkdir -p "$OUT"

python3 - <<'PY'
import base64, json, pathlib, uuid

root = pathlib.Path("/mnt/data/Development/openSRP-fhircore")
assets = root / "android/quest/src/main/assets/configs/cdss"
seed = root / "conf/fhir-seed"
out = seed / "generated"
out.mkdir(parents=True, exist_ok=True)

ns = uuid.UUID("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

def uid(name: str) -> str:
    return str(uuid.uuid5(ns, f"opensrp.cdss.{name}"))

ids = {
    "composition": uid("composition"),
    "application": uid("binary.application"),
    "navigation": uid("binary.navigation"),
    "sync": uid("binary.sync"),
    "clientRegister": uid("binary.clientRegister"),
    "clientProfile": uid("binary.clientProfile"),
    "taskRegister": uid("binary.taskRegister"),
    "householdRegister": uid("binary.householdRegister"),
    "householdProfile": uid("binary.householdProfile"),
}

# Wipe previous binaries
for old in out.glob("Binary-*.json"):
    old.unlink()

sources = [
    ("application", assets / "application_config.json"),
    ("navigation", assets / "navigation_config.json"),
    ("sync", assets / "sync_config.json"),
    ("clientRegister", assets / "registers" / "client_register_config.json"),
    ("clientProfile", assets / "profiles" / "client_profile_config.json"),
    ("taskRegister", assets / "registers" / "task_register_config.json"),
    ("householdRegister", assets / "registers" / "household_register_config.json"),
    ("householdProfile", assets / "profiles" / "household_profile_config.json"),
]

for key, path in sources:
    if not path.is_file():
        raise SystemExit(f"missing asset: {path}")
    data_b64 = base64.b64encode(path.read_bytes()).decode("ascii")
    bid = ids[key]
    binary = {
        "resourceType": "Binary",
        "id": bid,
        "contentType": "application/json",
        "data": data_b64,
    }
    (out / f"Binary-{bid}.json").write_text(json.dumps(binary, indent=2) + "\n")
    print(f"Binary/{bid}  ← {path.relative_to(root)}")

comp = {
    "resourceType": "Composition",
    "id": ids["composition"],
    "meta": {
        "tag": [
            {
                "system": "https://smartregister.org/app-id",
                "code": "cdss",
                "display": "CDSS bare application package",
            }
        ]
    },
    "identifier": {
        "use": "official",
        "system": "https://smartregister.org/app-id",
        "value": "cdss",
    },
    "status": "final",
    "type": {
        "coding": [
            {
                "system": "http://snomed.info/sct",
                "code": "1156600005",
                "display": "Device setting parameter",
            }
        ]
    },
    "date": "2026-08-05",
    "author": [{"display": "CDSS bare seed"}],
    "title": "CDSS bare configuration (client register)",
    "section": [
        {
            "title": "Application configuration",
            "mode": "working",
            "focus": {
                "reference": f"Binary/{ids['application']}",
                "identifier": {"value": "application"},
            },
        },
        {
            "title": "Sync configuration",
            "mode": "working",
            "focus": {
                "reference": f"Binary/{ids['sync']}",
                "identifier": {"value": "sync"},
            },
        },
        {
            "title": "Navigation configuration",
            "mode": "working",
            "focus": {
                "reference": f"Binary/{ids['navigation']}",
                "identifier": {"value": "navigation"},
            },
        },
        {
            "title": "Register configurations",
            "mode": "working",
            "section": [
                {
                    "title": "Client register",
                    "mode": "working",
                    "focus": {
                        "reference": f"Binary/{ids['clientRegister']}",
                        "identifier": {"value": "clientRegister"},
                    },
                },
                {
                    "title": "Task register",
                    "mode": "working",
                    "focus": {
                        "reference": f"Binary/{ids['taskRegister']}",
                        "identifier": {"value": "taskRegister"},
                    },
                },
                {
                    "title": "Household register",
                    "mode": "working",
                    "focus": {
                        "reference": f"Binary/{ids['householdRegister']}",
                        "identifier": {"value": "householdRegister"},
                    },
                },
            ],
        },
        {
            "title": "Profile configurations",
            "mode": "working",
            "section": [
                {
                    "title": "Client profile",
                    "mode": "working",
                    "focus": {
                        "reference": f"Binary/{ids['clientProfile']}",
                        "identifier": {"value": "clientProfile"},
                    },
                },
                {
                    "title": "Household profile",
                    "mode": "working",
                    "focus": {
                        "reference": f"Binary/{ids['householdProfile']}",
                        "identifier": {"value": "householdProfile"},
                    },
                },
            ],
        },
        {
            "title": "Questionnaires",
            "mode": "working",
            "section": [
                {
                    "title": "Client registration",
                    "mode": "working",
                    "focus": {
                        "reference": "Questionnaire/cdss-client-registration",
                        "identifier": {"value": "cdss-client-registration"},
                    },
                }
            ],
        },
        {
            "title": "StructureMaps",
            "mode": "working",
            "section": [
                {
                    "title": "Client registration",
                    "mode": "working",
                    "focus": {
                        "reference": "StructureMap/cdss-client-registration",
                        "identifier": {"value": "cdss-client-registration"},
                    },
                }
            ],
        },
    ],
}

(seed / "Composition-cdss.json").write_text(json.dumps(comp, indent=2) + "\n")
# Keep assets composition aligned
(assets / "composition_config.json").write_text(json.dumps(comp, indent=2) + "\n")
(seed / "resource-ids.json").write_text(json.dumps(ids, indent=2) + "\n")
print(f"Composition/{ids['composition']}  identifier=cdss")
print("Done.")
PY
