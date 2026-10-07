library "fever-triage-Helper" version '1.0.0'

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1' called FHIRHelpers

// Current-visit Encounter id, passed in by the client at $populate time (null on a
// visit's first process — nothing recorded yet this encounter, which is expected).
// See feature/20260812-intervention-order-and-dedup.md.
parameter encounterid String default null

context Patient

// ── Observation helpers (repeat-aware, current-encounter-scoped) ─────────────

// ── Repeat / current-encounter helpers ────────────────────────────────────────
// Extension URL: https://fhir.tricc.io/StructureDefinition/observation-repeat-index

define function ObservationHasCode(O Observation, code String):
  exists (O.code.coding C where C.code = code)

define function ObservationRepeatIndex(O Observation):
  singleton from (
    O.extension E
      where E.url = 'https://fhir.tricc.io/StructureDefinition/observation-repeat-index'
      return (E.value as FHIR.integer).value
  )

define function GetObservations(code String):
  if encounterid is null then {} as List<Observation>
  else
    [Observation] O
      where O.status in {'final', 'amended', 'corrected'}
        and ObservationHasCode(O, code)
        and O.encounter.reference = 'Encounter/' + encounterid

define function GetObservation(code String):
  Last(
    (GetObservations(code)) O
      where ObservationRepeatIndex(O) is null or ObservationRepeatIndex(O) = 1
      return O
      sort by (effective as FHIR.dateTime)
  )

define function GetRepeated(code String, repeatIndex Integer):
  Last(
    (GetObservations(code)) O
      where ObservationRepeatIndex(O) = repeatIndex
      return O
      sort by (effective as FHIR.dateTime)
  )

define function GetObservationValue(code String):
  GetObservation(code).value

define function GetRepeatedValue(code String, repeatIndex Integer):
  GetRepeated(code, repeatIndex).value

define function GetNumberOfRepeat(code String):
  Count(
    distinct(
      (GetObservations(code)) O
        return ObservationRepeatIndex(O)
    )
  )

define function GetHistoryObservations(code String, repeatIndex Integer):
  [Observation] O
    where O.status in {'final', 'amended', 'corrected'}
      and ObservationHasCode(O, code)
      and (
        repeatIndex is null
        or ObservationRepeatIndex(O) = repeatIndex
        or (repeatIndex = 1 and ObservationRepeatIndex(O) is null)
      )

define function GetHistoryObservation(
  code String,
  period String,
  reverseOrderPosition Integer,
  repeatIndex Integer
):
  First(
    Skip(
      (
        (GetHistoryObservations(code, repeatIndex)) O
          return O
          sort by (effective as FHIR.dateTime) desc
      ),
      Coalesce(reverseOrderPosition, 1) - 1
    )
  )

define function GetHistoryObservationValue(
  code String,
  period String,
  reverseOrderPosition Integer,
  repeatIndex Integer
):
  GetHistoryObservation(code, period, reverseOrderPosition, repeatIndex).value

// ── Condition family (same current-encounter / history split; no repeat index —
// Condition entries aren't repeated within one encounter the way vitals are) ──

define function ConditionHasCode(C Condition, code String):
  exists (C.code.coding CC where CC.code = code)

define function ConditionVerificationCode(C Condition):
  First(C.verificationStatus.coding CC return CC.code.value)

define function ConditionClinicalCode(C Condition):
  First(C.clinicalStatus.coding CC return CC.code.value)

define function GetConditions(code String):
  if encounterid is null then {} as List<Condition>
  else
    [Condition] C
      where ConditionHasCode(C, code)
        and C.encounter.reference = 'Encounter/' + encounterid

define function GetActiveConditions(code String):
  (GetConditions(code)) C
    where ConditionVerificationCode(C) != 'refuted'
      and ConditionVerificationCode(C) != 'entered-in-error'

define function GetCondition(code String):
  Last(
    (GetActiveConditions(code)) C
      return C
      sort by (recordedDate as FHIR.dateTime)
  )

define function GetConditionValue(code String):
  exists (GetActiveConditions(code))

define function HasProvisionalCondition(code String):
  exists ((GetConditions(code)) C where ConditionVerificationCode(C) = 'provisional')

define function HasConfirmedCondition(code String):
  exists ((GetConditions(code)) C where ConditionVerificationCode(C) = 'confirmed')

define function HasRefutedCondition(code String):
  exists ((GetConditions(code)) C where ConditionVerificationCode(C) = 'refuted')

define function GetHistoryConditions(code String):
  [Condition] C
    where ConditionHasCode(C, code)
      and ConditionVerificationCode(C) != 'refuted'
      and ConditionVerificationCode(C) != 'entered-in-error'

define function GetHistoryCondition(code String):
  Last(
    (GetHistoryConditions(code)) C
      return C
      sort by (recordedDate as FHIR.dateTime)
  )

define function GetHistoryConditionValue(code String):
  exists (GetHistoryConditions(code))


// ── Populate context helpers ─────────────────────────────────────────────────

// ── Populate context helpers ─────────────────────────────────────────────────

define function GetPatientValue(code String):
  null

define function GetFacilityValue(code String):
  null

define function GetLocationValue(code String):
  null

define function GetPractitionerValue(code String):
  null

// Current-encounter accessors. GetObservations/GetConditions already filter on the
// `encounterid` parameter, so these are GetObservationValue / GetConditionValue
// scoped to this encounter; the names stay resource-specific so a populate node with
// a Condition concept_type does not read an Observation value
// (fix/20260821-merge-input-into-populate.md).

define function GetEncounterObservationValue(code String, repeatIndex Integer, period String):
  if repeatIndex is null or repeatIndex = 1 then GetObservationValue(code)
  else GetRepeatedValue(code, repeatIndex)

define function GetEncounterConditionValue(code String):
  GetConditionValue(code)

// Deprecated alias: resource-agnostic name kept for previously generated libraries.
define function GetEncounterValue(code String, repeatIndex Integer, period String):
  GetEncounterObservationValue(code, repeatIndex, period)


// ── Condition helpers ─────────────────────────────────────────────────────────

// Any-time "does this patient have an active Condition with this code". `~ "active"`
// would need a declared code/codesystem, and the retrieve cannot take a String
// argument as its code selector (fix/20260930-helper-cql-compile.md).
define function HasCondition(code String):
  exists (
    [Condition] C
      where ConditionHasCode(C, code)
        and ConditionClinicalCode(C) = 'active'
  )

// ── Age helpers ───────────────────────────────────────────────────────────────

define AgeInDays:
  duration in days between Patient.birthDate and Today()

define AgeInMonths:
  duration in months between Patient.birthDate and Today()

define AgeInYears:
  AgeInYears()
