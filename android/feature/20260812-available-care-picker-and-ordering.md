# Available Care Picker — Checkbox Selection, cpg-common-process Ordering, Sequential Launch

| Field | Value |
|-------|-------|
| **Status** | Implemented |
| **Repos** | openSRP FHIRCore Android (`android/`); companion change in `tricc_oo` |
| **Related** | `feature/client-register-applicable-care.md` (supersedes its §3/§5 single-tap picker), `feature/register-tricc.md`, `tricc_oo` `feature/20260812-intervention-order-and-dedup.md` (generates the extensions/CQL parameter this feature consumes) |
| **Named event (default)** | `available-care` |

Valid status values: `Draft` → `Approved` → `Implemented` → `Superseded`.

---

## Part I — Business spec

### 1. Problem

The existing "Start care" picker (`feature/client-register-applicable-care.md`) lists every
applicable intervention as a single-tap `AlertDialog` row — one row per Questionnaire, across
however many PlanDefinitions matched. Two problems: (1) there's no way to select **more than one**
care package at once, and (2) once several are selected, there's no defined order to launch their
underlying questionnaires in — a patient's actual care pathway (registration before triage before
dispense-medications, etc.) has no inherent name-sort or PlanDefinition-declaration-order meaning.

### 2. Goal

- Show one **checkbox** per available PlanDefinition (not per questionnaire), titled by the
  PlanDefinition's own title, plus a **Start** button.
- Once started, launch the selected PDs' questionnaires **one at a time**, in cpg-common-process
  order (a fixed, canonical ordering tricc now stamps on every exported action — see the tricc-side
  spec) — never more than the single lowest-order due questionnaire at a time.
- After each questionnaire is submitted, re-check applicability across the *same* selected PDs —
  submitting one may have unlocked a different, lower-order action on another selected PD (once
  tricc starts emitting real applicability conditions; today it changes nothing since no action is
  gated yet, but the sequencing already accounts for it) — and continue with whichever is now due,
  until nothing is left.
- Avoid re-running `$apply` unnecessarily: the checkbox list's own already-computed applicability
  result is reused for the very first launch decision; every subsequent step re-runs it for real.

### 3. Decisions

| Topic | Decision |
|-------|----------|
| Picker UI | Native `AlertDialog.setMultiChoiceItems` (checkbox per PlanDefinition title) + a "Start" positive button — kept in the same native-dialog family as the picker it replaces, rather than introducing a new Compose screen/nav route, since this call site (`ConfigExtensions.handleApplyNamedEvent`) is a plain extension function, not a Composable. |
| Row granularity | One row per **PlanDefinition** (`NamedEventInterventionService.AvailableCarePlan`), not per questionnaire — a PD is listed only if it has at least one launchable (Questionnaire-resolvable) option. |
| Default selection | All rows start **unchecked**, and **Start is disabled until at least one is checked**. Passing applicability means a package *may* be delivered, not that it is intended for this visit; pre-selecting everything made "Start" the path of least resistance and risked launching questionnaires the user never meant to open. Superseded the original pre-checked default (2026-10-04). |
| Order source | The `tricc-process-order` extension (`valueInteger`) tricc stamps on each per-process action — fixed/canonical (10, 20, 30…), so it's comparable across the several PlanDefinitions the user may have checked, not just within one. |
| Tie-break | If more than one selected PD's due action shares the same lowest order, a short single-choice fallback dialog asks the user to pick one before launching. |
| Advancing after submission | Hooks into the existing `EventBus`/`AppEvent.OnSubmitQuestionnaire` mechanism (already used by `AppMainActivity.onSubmitQuestionnaire`) rather than a new per-launch callback — `QuestionnaireHandler.launchQuestionnaire`'s `startForResult` callback is centralized on the Activity, not scoped per call site. |
| "Don't re-show a just-submitted item" | Tracked client-side for the session (`submittedQuestionnaireIds`) — tricc does not yet emit any applicability `condition` (see `feature/careplan-intervention-plandefinition.md` §26), so without this, a re-`$apply` would show the same just-completed action forever. |
| Current-encounter dedup support | The current-visit Encounter id (learned from the first submission that produces one) is passed forward as a `QUESTIONNAIRE_RESPONSE_POPULATION_RESOURCE` action param on every subsequent launch in the same session, so tricc's `encounterid`-scoped CQL Helper functions can actually resolve (see the tricc-side spec). The first launch in a session has no Encounter yet — expected, matches tricc's own framing (nothing recorded yet this visit). |

---

## Part II — Technical spec

### 4. Where it lives

| Area | Location |
|------|----------|
| PD grouping + order-aware sorting | `engine/.../task/NamedEventInterventionService.kt` — `AvailableCarePlan`, `listAvailableCarePlans()`; `InterventionOption.order`/`.process`; `listInterventions()` now sorts by `order` too |
| Picker UI, session/consolidation logic, encounter propagation | `quest/.../util/extensions/ConfigExtensions.kt` — `handleApplyNamedEvent()`, `showAvailableCarePicker()`, `AvailableCareSession`, `resolveNextBatch()`, `advanceAvailableCareSession()`, `awaitTieBreakChoice()`, `launchInterventionOption()` |
| EventBus access from a non-injected extension function | `quest/.../di/NamedEventInterventionEntryPoint.kt` — added `eventBus(): EventBus` alongside the existing `namedEventInterventionService()` |

### 5. End-to-end flow

```text
Register/profile button (ON_CLICK, workflow=APPLY_NAMED_EVENT) — unchanged trigger
        │
        ▼
ConfigExtensions.handleApplyNamedEvent
  - resolves namedEvent/subjectId (unchanged)
  - service.listAvailableCarePlans(namedEvent, subjectId)
      → one AvailableCarePlan per matching PlanDefinition with ≥1 launchable option
        (internally: same collectFromPlanDefinition() per-PD applicability logic as before,
         now grouped instead of flattened+globally-deduped)
        │
        ▼
showAvailableCarePicker  — AlertDialog, checkbox per PD title (all unchecked by default) + Start
                           (Start disabled while nothing is checked)
        │  user taps Start with N plans checked
        ▼
AvailableCareSession(selectedPlanIds, cachedPlans = the N checked plans' already-computed options)
        │
        ▼
advanceAvailableCareSession (recursive, one step per launched questionnaire):
  1. resolveNextBatch:
       - first call: reuse session.cachedPlans (no re-$apply)
       - later calls: service.listAvailableCarePlans(...) again (fresh $apply)
       - flatten selected plans' options, drop already-submitted questionnaireIds, dedup by
         questionnaireId, take the subset at the lowest `order`
  2. batch empty → session done
  3. batch.size == 1 → that option; batch.size > 1 → awaitTieBreakChoice (single-choice dialog)
  4. launchInterventionOption(option, session.encounterId)
       → QuestionnaireConfig launch, now with a QUESTIONNAIRE_RESPONSE_POPULATION_RESOURCE
         actionParam for Encounter/{encounterId} when known
  5. suspend until eventBus.events.getFor(session.consumerId) emits an OnSubmitQuestionnaire
     for exactly this questionnaireId (no timeout — see note below)
  6. mark submitted; if session.encounterId was null, read it off
     questionnaireSubmission.questionnaireResponse.encounter
  7. go to 1
```

**Note on abandoned questionnaires:** if the user backs out of a launched Questionnaire without
submitting, `AppMainActivity.onSubmitQuestionnaire` never fires (it only triggers on
`RESULT_OK`), so step 5 never resolves and the session simply stops advancing — bounded by the
launching `LifecycleOwner`'s own coroutine scope (not a leak), but the user would need to reopen
the picker to resume. No existing mechanism in this codebase signals "user cancelled," so this is
an accepted, pre-existing-pattern limitation rather than a new regression.

### 6. Tests

`engine/src/test/.../task/NamedEventInterventionServiceTest.kt` (new) — order-based sorting within
a single PlanDefinition and across several, an option missing the order extension sorting last,
`listAvailableCarePlans` grouping by PlanDefinition and omitting PDs with no launchable option, and
title fallback (`title` → `name` → default). Existing `ConfigExtensionsKtTest` (41 tests) still
passes unmodified — it doesn't exercise `APPLY_NAMED_EVENT` — confirming no regression to the
other click-handling paths in the same file.

**Gap, flagged rather than silently left untested:** the picker/session orchestration in
`ConfigExtensions.kt` (consolidation, tie-break, re-`$apply`-after-submit, encounter propagation)
has no automated test yet. It compiles cleanly (`:quest:compileOpensrpDebugKotlin`) and was
manually traced through, but a full `RobolectricTest`/Hilt integration test (mirroring
`ConfigExtensionsKtTest`'s existing setup, with a fake `EventBus`/`NamedEventInterventionService`)
is recommended follow-up before this ships to a real device — see §7.

### 7. Open items

- Not yet verified on-device (same caveat as `feature/client-register-applicable-care.md` §10) —
  in particular the multi-step suspend loop awaiting real `EventBus` emissions across real
  `QuestionnaireActivity` launches, which a JVM unit test can't exercise end-to-end.
- No automated test for the `ConfigExtensions.kt` session logic yet (§6) — recommended before
  wider rollout.
- Tie-break UX (a second dialog when two selected PDs' due actions share the same order) is a
  reasonable default, not something the user explicitly specified — worth confirming with a real
  multi-process-tie scenario once tricc content with duplicate orders across PDs exists.
