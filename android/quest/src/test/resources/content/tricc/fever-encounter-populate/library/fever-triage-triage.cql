library "fever-triage-triage" version '1.0.0'

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1' called FHIRHelpers
include "fever-triage-Helper" version '1.0.0' called Helper

context Patient



define Dedup_fever_Vv_1: Helper.GetObservationValue('fever')
