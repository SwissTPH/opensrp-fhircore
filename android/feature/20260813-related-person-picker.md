# Client Profile — Basic Data + Add Related Person (Child / Guardian)

| Field | Value |
|-------|-------|
| **Status** | Implemented |
| **Repos** | openSRP FHIRCore Android (`android/`) |
| **Related** | `feature/register-tricc.md` §5 / §5.4 (RelatedPerson shape and add invariant) |

Valid status values: `Draft` → `Approved` → `Implemented` → `Superseded`.

---

## Part I — Business spec

### 1. Problem

Opening a client showed little more than a name. Related persons were hidden until at least one
existed, and "Add related person" launched a questionnaire (`add-related-person`) that is not
shipped. Linking a parent/guardian or child therefore could not be completed from the profile.

### 2. Goal

- On opening a client, show **sex, date of birth, and age**.
- Always show **related persons** on the same profile (children / dependents and parents /
  guardians), with empty states when none are linked.
- **Add related person** (Kotlin dialogs, then the **standard** client registration form — no
  `add-related-person` questionnaire):
  1. Ask **child | mother | father | guardian**. If child, also ask the current adult's
     kinship (mother / father / guardian).
  2. Ask whether this join is the child's **main caregiver / primary contact** (default yes
     when the child has none).
  3. Then **search** an existing client or **create** one (same `cdss-client-registration`).
  4. Persist one `RelatedPerson` (`patient` = child, PI identifier = guardian, kinship
     coding, optional primary-caregiver extension).

### 3. Decisions

| Topic | Decision |
|-------|----------|
| Layout | Config-driven profile: personal-data row on the client card; related-person cards always visible below (the "half" of the screen), not a new tab ViewType. |
| Add UX | Sequential native dialogs (who, caregiver, search/create) plus a full-screen search dialog. |
| Search | Local Patient search; name contains; age filter changeable; unknown DOB is included in both bands. Current client is excluded. |
| Create | Same questionnaire as Add client (`cdss-client-registration`). Form is Patient-only — no inline caregiver items. First extracted `Patient` id is the new client. |
| RelatedPerson shape | `patient` = **child**, `identifier` PI/secondary = guardian `Patient/{id}`, single kinship `MTH` / `FTH` / `GUARD`. Main caregiver is extension `https://fhir.opensrp.io/cdss/StructureDefinition/primary-caregiver` on that same row — never a second RelatedPerson. |
| Duplicates | If the same child↔guardian pair already exists, do not create another row; may update the caregiver flag. |

---

## Part II — Technical spec

### 4. Where it lives

| Area | Location |
|------|----------|
| Workflow | `ApplicationWorkflow.ADD_RELATED_PERSON` |
| Link + search | `engine/.../data/local/RelatedPersonLinkService.kt` |
| RelatedPerson builder / age helpers | `engine/.../util/extension/RelatedPersonAsPatient.kt` |
| Guardian Patient nest | `RegisterRepository.enrichGuardianPatientsFromRelatedPersons` |
| Flow orchestration | `quest/.../ui/relatedperson/RelatedPersonAddCoordinator.kt` |
| Search UI | `RelatedPersonSearchDialogFragment` + `RelatedPersonSearchScreen` |
| Click handler | `ConfigExtensions` → `ADD_RELATED_PERSON` |
| Profile refresh after search-only link | `ProfileFragment` also handles `AppEvent.RefreshData` |
| Config | `configs/{app,cdss}/profiles/client_profile_config.json` |

### 5. End-to-end flow

```text
Profile FAB (+)
        │  workflow ADD_RELATED_PERSON
        │  params: subjectId=@{patientLogicalId},
        │          registrationQuestionnaireId=cdss-client-registration
        ▼
RelatedPersonAddCoordinator
  1. Dialog: Child | Mother | Father | Guardian
     (if Child → also "Your relationship to this child")
  2. Dialog: main caregiver / primary contact? (default yes if none yet)
  3. Dialog: Search existing client | Create patient
        │
        ├─ Search → RelatedPersonSearchDialogFragment
        │     age filter default <18 (child) or ≥18 (adult)
        │     tap a row → otherPatientId
        │
        └─ Create → launch cdss-client-registration (Patient only)
              wait EventBus OnSubmitQuestionnaire
              otherPatientId = first extracted Patient id
        ▼
RelatedPersonLinkService.link(current, other, role, kinship, isPrimaryCaregiver)
  one RelatedPerson per pair; caregiver flag at most one per child
        ▼
Toast + AppEvent.RefreshData → profile reloads
```

### 6. Tests

`RelatedPersonLinkServiceTest` — age/name filters, RelatedPerson shape, kinship, primary
caregiver uniqueness, duplicate short-circuit, self-link rejection.

`RelatedPersonAsPatientTest` — default age filter, kinship coding, primary-caregiver
extension, builder copies guardian demographics.

`RulesFactoryTest.extractLogicalId` / `isPrimaryCaregiver` — used by profile badge rules.

### 7. Open items

- Not verified on-device (same caveat as other TRICC profile work).
- Abandoned registration questionnaire does not emit an event, so the create path stops
  without a RelatedPerson — same accepted limitation as the available-care session.
- Optional: after Add client for an under-18, prompt “Add a parent/guardian?” (registration
  no longer collects a caregiver inline).
