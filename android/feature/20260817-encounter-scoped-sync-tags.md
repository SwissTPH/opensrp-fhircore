# Start-Care Session Encounter Generation + Encounter-Scoped Sync Tags

| Field | Value |
|-------|-------|
| **Status** | Implemented |
| **Repos** | openSRP FHIRCore Android (`android/`) |
| **Related** | `feature/20260812-available-care-picker-and-ordering.md` (the "start care" session this hooks into), `feature/client-register-applicable-care.md`, `feature/register-tricc.md` |

Valid status values: `Draft` → `Approved` → `Implemented` → `Superseded`.

---

## Part I — Business spec

### 1. Problem

Two related gaps, one scoped to the "start care" (`APPLY_NAMED_EVENT`) session
(`quest/.../util/extensions/ConfigExtensions.kt:304-498`), one app-wide:

1. **Encounter creation is a StructureMap convention, not a guarantee.** No app code instantiates
   `Encounter()` — extraction is entirely delegated to `ResourceMapper.extract()`
   (`QuestionnaireViewModel.performExtraction()`). A session's questionnaires may or may not each
   produce their own Encounter, and nothing ties several questionnaires in the *same* start-care
   session to the *same* Encounter unless every map author remembers to thread the `encounterid` CQL
   parameter through by hand.
2. **Sync-strategy tags are stamped on every extracted resource, not just the Encounter — for every
   questionnaire submission in the app, not only start-care sessions.**
   `DefaultRepository.preProcessResources()` (`engine/.../data/local/DefaultRepository.kt:193`) adds
   the five sync tags (`organisation-tag-id`, `practitioner-tag-id`, `care-team-tag-id`,
   `location-tag-id`, `app-version`) to *every* bundle entry, called unconditionally per entry from
   `QuestionnaireViewModel.saveExtractedResources()` (`QuestionnaireViewModel.kt:553`). This happens
   even when the same bundle already contains an Encounter (many StructureMaps produce one, e.g.
   `family-registration.map`) — a single batch producing a dozen Observations duplicates the same
   five tags a dozen times, taking up storage/index space for metadata that's already fully present
   once, on the Encounter, with `Observation.encounter` (etc.) able to carry the provenance instead.

Gap 1 is start-care-specific (an opt-in fix, §2); gap 2 is a general extraction/save defect and its
fix applies to every questionnaire submission, whether or not it goes through a start-care session.

### 2. Goal

- Let a start-care session **generate its own Encounter** the first time it's needed — not depend on
  the questionnaire's own StructureMap happening to produce one — controlled by a `generateEncounter`
  opt-in on the triggering action config, so nothing changes for configs that don't ask for it.
- Reuse the **existing** "current-visit Encounter id" concept already on `AvailableCareSession`
  (`ConfigExtensions.kt:422`, `var encounterId: String?`) rather than inventing a second, parallel
  tracking mechanism — the session already learns an Encounter id from the first submission that
  produces one (`ConfigExtensions.kt:492-494`); this feature only changes what happens when no
  submission would otherwise have produced one.
- Once an Encounter — generated or map-produced — is known for a submission, **attach every other
  extracted resource in that bundle to it** (`Observation.encounter`, `Condition.encounter`,
  `MedicationRequest.encounter`, etc.), and stamp the sync-strategy tags on the Encounter only.
- **This tag-scoping rule is unconditional, not opt-in, and applies to every questionnaire
  submission in the app** — not just `APPLY_NAMED_EVENT` sessions. Any bundle that already contains
  a map-produced Encounter (many do today, e.g. `family-registration.map`) gets the same treatment:
  the Encounter is tagged, its siblings are attached to it and left untagged. This is safe because it
  is lossless — an untagged Observation still carries the org/practitioner/care-team/location context
  via its `.encounter` reference — and it removes duplicate tag storage that was never buying
  anything. Only when a submission has **no** Encounter at all (nothing in the bundle, and no
  `generateEncounter` opt-in to create one) does today's behavior apply unchanged: tag every
  resource, exactly as now.
- **Encounter *generation* remains opt-in** (`generateEncounter`) — inventing an Encounter where none
  would otherwise exist is a bigger behavior change than re-scoping tags on one that's already there,
  so it stays gated to sessions that explicitly ask for it. Tag-scoping and attachment, once an
  Encounter is resolved by any means, are not gated.

### 3. Decisions

| Topic | Decision |
|-------|----------|
| Opt-in surface | New `generateEncounter` param (`"true"`/`"false"`) on the `APPLY_NAMED_EVENT` action config, default `false` (absent) — a config must explicitly ask for this behavior. |
| Where the flag lives for the session's lifetime | `AvailableCareSession.generateEncounter: Boolean`, resolved once in `handleApplyNamedEvent` when the session is created — same lifecycle as the existing `namedEvent`/`subjectId` resolution. |
| Where the id lives | The **existing** `AvailableCareSession.encounterId` field — no second field, no duplicate learn-from-submission path. |
| When generation happens | Lazily, on the **first extraction** in the session where `encounterId == null && generateEncounter == true` and the questionnaire's own bundle didn't produce an Encounter — not upfront when the picker opens, since the subject/answers needed to build a meaningful Encounter only exist once a questionnaire is actually being extracted. |
| How the session learns the generated id | No new mechanism — the generation step calls `questionnaireResponse.setEncounter(...)`, the exact line that already exists for map-produced Encounters (`QuestionnaireViewModel.kt:546-548`), so the session's current `submission.questionnaireResponse.encounter` read (`ConfigExtensions.kt:492-494`) picks it up unmodified. |
| Attaching other resources | Every other bundle entry whose FHIR type carries an `.encounter` reference (Observation, Condition, MedicationRequest, Procedure, ServiceRequest, DiagnosticReport, MedicationAdministration, …) gets it set, unless the map already set one — new dispatch extension mirroring the existing `appendOrganizationInfo`/`appendPractitionerInfo` `when (this)` pattern (`engine/.../util/extension/ResourceExtension.kt:271-321`). |
| Tag scoping | **Unconditional** — applies in `saveExtractedResources` for *any* submission (start-care or not) once an Encounter is resolved (generated, already in the bundle, or carried over from earlier in the session): tag the Encounter, skip tagging everything else. Not gated by `generateEncounter` — that flag only controls whether a *missing* Encounter gets created. When no Encounter is resolved at all: unchanged, tag every resource, exactly as today. |
| Why unconditional is safe | Tag removal on non-Encounter resources is lossless as long as the resource references back to the tagged Encounter (`appendEncounterReference`, always run alongside the tag-skip decision) — a client reading `Observation.encounter` can resolve the same org/practitioner/care-team/location context. Encounter *generation* doesn't get the same free pass because inventing a resource that wouldn't otherwise exist is a bigger change than re-scoping tags on one already present. |
| Generated Encounter's own metadata | Goes through the same `appendOrganizationInfo`/`appendPractitionerInfo` calls any map-produced Encounter already gets (`Encounter ->` branches already exist in both functions) — no special-casing needed there. |

---

## Part II — Technical spec

### 4. Where it lives (as implemented)

| Area | Location |
|------|----------|
| Opt-in flag on the session | `quest/.../util/extensions/ConfigExtensions.kt` — `handleApplyNamedEvent()` resolves `generateEncounter` from `interpolatedParams` once (`it.key == GENERATE_ENCOUNTER_PARAM_KEY`, `"true"`/anything else); `AvailableCareSession` carries `val generateEncounter: Boolean` next to the existing `encounterId`. |
| Threading the flag to extraction | `launchInterventionOption()` — `actionParams` is now built with `buildList {}`: the existing `encounter` param is added when `encounterId` is known, and a `GENERATE_ENCOUNTER_PARAM_KEY` (`PARAMDATA`) param is added whenever `generateEncounter` is true. |
| Encounter generation + attachment | `QuestionnaireViewModel.saveExtractedResources()` — gained an `actionParameters: List<ActionParameter> = emptyList()` parameter, threaded in from `performSave` (which already received it from `handleQuestionnaireSubmission`). |
| New per-type `.encounter` dispatch | `engine/.../util/extension/ResourceExtension.kt` — `Resource.appendEncounterReference(encounterReference: Reference)`, dispatching on `Observation`, `Condition`, `Procedure`, `MedicationRequest`, `ServiceRequest`, `DiagnosticReport` (`.encounter`) and `MedicationAdministration` (`.context`), reusing the existing private `updateReference()` helper so an already-set reference is never clobbered. |
| Tag scoping at save time | `saveExtractedResources`'s per-entry save call now computes `addMandatoryTags = resolvedEncounterReference == null || entryResource.resourceType == ResourceType.Encounter` before calling `defaultRepository.addOrUpdate(addMandatoryTags, resource = entryResource)`. |
| Generated Encounter's fields | `Encounter().apply { id = UUID.randomUUID().toString(); status = Encounter.EncounterStatus.INPROGRESS; period = Period().apply { start = extractionDate }; subject = <resolved subject> }` — subject comes from `questionnaireResponse.subject` if already set, else the first bundle resource matching the questionnaire's subject type. |

### 4.1 GENERATE_ENCOUNTER_PARAM_KEY

`const val GENERATE_ENCOUNTER_PARAM_KEY = "generateEncounter"`, defined once in
`ConfigExtensions.kt` and imported by `QuestionnaireViewModel.kt` — the single source of truth for
the param key on both the producing (session) and consuming (extraction) side.

### 5. End-to-end flow

```text
Register/profile button (ON_CLICK, workflow=APPLY_NAMED_EVENT, params: generateEncounter=true)
        │
        ▼
handleApplyNamedEvent — resolves generateEncounter once; AvailableCareSession(generateEncounter, encounterId=null)
        │
        ▼
advanceAvailableCareSession → launchInterventionOption(option, session.encounterId, session.generateEncounter)
  - actionParams always carries generateEncounter; carries `encounter` only once encounterId is known
        │
        ▼
QuestionnaireViewModel.saveExtractedResources(bundle, ..., actionParameters)
  resolvedEncounterRef =
    bundle has an Encounter entry?              → use it            (ANY questionnaire, not just start-care — NEW behavior)
    : actionParameters has `encounter` param?   → reuse it, no generation             (session already has one)
    : generateEncounter == true?                → BUILD + SAVE a new Encounter now,
                                                   questionnaireResponse.setEncounter(it)   (NEW, start-care only)
    : else                                      → null                                (unchanged path)

  if resolvedEncounterRef != null:
      other bundle entries → appendEncounterReference(resolvedEncounterRef)     (NEW)
      Encounter entry      → addOrUpdate(addMandatoryTags = true)               (tags land here)
      everything else      → addOrUpdate(addMandatoryTags = false)              (NEW — unconditional, no opt-in)
  else:
      every entry           → addOrUpdate(addMandatoryTags = true)              (today's behavior, unchanged)
        │
        ▼
EventBus emits OnSubmitQuestionnaire → advanceAvailableCareSession's existing read of
submission.questionnaireResponse.encounter (ConfigExtensions.kt:492-494) learns session.encounterId
— UNCHANGED, now also fires for a generated Encounter, not only a map-produced one.
```

### 6. Non-goals / explicitly out of scope

- `appendRelatedEntityLocation()`'s own `Meta.tag` stamping (`ResourceExtension.kt:323`) is untouched.
- `DefaultRepository.preProcessResources()` itself is untouched — it remains the mechanism that
  stamps tags; only the *call site* in `saveExtractedResources` changes, choosing per-entry whether
  to pass `addMandatoryTags = true` or `false` based on whether an Encounter was resolved for that
  submission. Every other caller of `create`/`createRemote`/`addOrUpdate` elsewhere in the codebase
  (P2P sync, non-questionnaire resource writes) is unaffected.
- No change to how tags are *defined* (`QuestConfigService.defineResourceTags()`) or their systems —
  only *which resource* they land on changes.
- Tie-break/ordering/session-advance logic from `feature/20260812-available-care-picker-and-ordering.md`
  is unchanged; this feature only adds the encounter-generation and tag-scoping steps inside the
  existing submit/save path.
- **Known behavior change, called out rather than silently shipped:** any *existing* questionnaire
  whose StructureMap already produces an Encounter alongside other resources will, after this change,
  stop tagging those other resources — with no config flag to opt out. This is deliberate (§2) but
  worth a changelog mention since it alters on-disk `Meta.tag` content for flows that predate this
  feature and never asked for it.

### 7. Tests

`engine/.../util/extension/ResourceExtensionTest.kt` (new cases):
- `appendEncounterReference` sets `.encounter` on Observation, Condition, MedicationRequest; sets
  `.context` on MedicationAdministration; does not overwrite an already-set reference; is a no-op
  (no throw) for a Resource type with neither field (Patient).

`quest/.../ui/questionnaire/QuestionnaireViewModelTest.kt` (new cases), all calling
`saveExtractedResources` directly against the real `DefaultRepository` spy:
- `testSaveExtractedResourcesTagsOnlyEncounterWhenBundleContainsOne` — bundle has an Encounter +
  Observation, no action params at all (plain, non-start-care submission): confirms the Encounter is
  saved with `addMandatoryTags = true`, the Observation with `false`, and the Observation's
  `.encounter` reference points at it. This is the behavior-change case (§2), asserted as the new
  default, not guarded as a regression.
- `testSaveExtractedResourcesTagsEveryResourceWhenNoEncounterIsResolved` — no Encounter anywhere, no
  params: regression guard confirming the one case that stays byte-for-byte unchanged.
- `testSaveExtractedResourcesGeneratesEncounterWhenOptedIn` — no Encounter, `generateEncounter=true`
  action param: confirms a new Encounter is generated, tagged, set on the `QuestionnaireResponse`,
  and the Observation is attached to it and left untagged.
- `testSaveExtractedResourcesAttachesToCarriedEncounterWithoutGenerating` — no Encounter in bundle,
  an `encounter` param already carries an id: confirms no new Encounter is generated (`QR.encounter`
  stays unset this round) and the Observation attaches to the carried id, untagged.

All four new `QuestionnaireViewModelTest` cases pass; the same 4 pre-existing failures in that file
(`testHandleQuestionnaireSubmission`, `testHandleQuestionnaireSubmissionHasValidationErrors...`,
`testPerformExtractionWithStructureMap`, `testValidateQuestionnaireResponse`) were confirmed present
on this branch *before* this change too (verified by stashing just the three changed source files
and re-running) — unrelated pre-existing breakage on `feature/all-client`, not a regression from this
feature.

**Gap, flagged rather than silently left untested:** no `ConfigExtensionsKtTest` coverage was added
for `generateEncounter` resolution/threading through `handleApplyNamedEvent` →
`launchInterventionOption` — that file's existing tests don't exercise the `APPLY_NAMED_EVENT` path
(same pre-existing gap noted in `feature/20260812-available-care-picker-and-ordering.md` §6/§7).

### 8. Resolved decisions (were open items pre-implementation)

- Generated `Encounter` fields: `status = Encounter.EncounterStatus.INPROGRESS`,
  `period.start = extractionDate` (the same `Date()` already computed at the top of
  `saveExtractedResources`), `subject` from `questionnaireResponse.subject` if already set, else the
  first bundle resource matching the questionnaire's subject type. No `class` is set — left to the
  same "automatically added in code" convention StructureMaps already comment about for
  `serviceProvider`/`participant`.
- `appendEncounterReference` covers Observation, Condition, Procedure, MedicationRequest,
  ServiceRequest, DiagnosticReport (`.encounter`) and MedicationAdministration (`.context`) — not
  cross-checked against tricc's actual StructureMap output; extending the `when (this)` list is a
  same-file, low-risk follow-up if a needed type is missing.
- `generateEncounter` is a single flag on the triggering action config (no per-named-event
  differentiation) — sufficient for the one deployment currently using `APPLY_NAMED_EVENT`.

### 9. Open items

- No end-to-end/instrumentation verification yet (same caveat as
  `feature/20260812-available-care-picker-and-ordering.md` §7) — unit-level coverage only.
- Multiple-Encounters-in-one-bundle edge case (all tagged, resources attach to the first found) is
  implemented but not covered by a dedicated test.
