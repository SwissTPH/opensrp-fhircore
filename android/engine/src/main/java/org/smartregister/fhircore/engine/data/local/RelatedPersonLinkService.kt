/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.engine.data.local

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.get
import com.google.android.fhir.search.Search
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.RelatedPerson
import org.hl7.fhir.r4.model.ResourceType
import org.smartregister.fhircore.engine.util.extension.RelatedPersonAgeFilter
import org.smartregister.fhircore.engine.util.extension.RelatedPersonKinship
import org.smartregister.fhircore.engine.util.extension.RelatedPersonRole
import org.smartregister.fhircore.engine.util.extension.batchedSearch
import org.smartregister.fhircore.engine.util.extension.buildRelatedPersonLink
import org.smartregister.fhircore.engine.util.extension.childPatientId
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.inferGuardianRelationship
import org.smartregister.fhircore.engine.util.extension.isDependentOfGuardian
import org.smartregister.fhircore.engine.util.extension.isPrimaryCaregiver
import org.smartregister.fhircore.engine.util.extension.matchesAgeFilter
import org.smartregister.fhircore.engine.util.extension.matchesNameQuery
import org.smartregister.fhircore.engine.util.extension.setPrimaryCaregiver
import org.smartregister.fhircore.engine.util.extension.toCoding
import timber.log.Timber

/**
 * Searches local Patients and creates the TRICC RelatedPerson link (`RelatedPerson.patient` =
 * child, `identifier` = guardian Patient URL). See `feature/register-tricc.md` and
 * `feature/20260813-related-person-picker.md`.
 */
@Singleton
class RelatedPersonLinkService
@Inject
constructor(
  private val fhirEngine: FhirEngine,
  private val defaultRepository: DefaultRepository,
) {

  data class LinkResult(
    val relatedPerson: RelatedPerson,
    val created: Boolean,
  )

  suspend fun searchPatients(
    query: String,
    ageFilter: RelatedPersonAgeFilter,
    excludePatientId: String,
    now: LocalDate = LocalDate.now(),
    limit: Int = DEFAULT_SEARCH_LIMIT,
  ): List<Patient> {
    val excluded = excludePatientId.extractLogicalIdUuid()
    val patients =
      runCatching { loadPatients() }
        .onFailure { Timber.e(it, "Failed to search Patients for related-person picker") }
        .getOrDefault(emptyList())
    return patients
      .asSequence()
      .filter { it.logicalId != excluded }
      .filter { it.active != false }
      .filter { it.matchesNameQuery(query) }
      .filter { it.matchesAgeFilter(ageFilter, now) }
      .take(limit)
      .toList()
  }

  /**
   * Links [currentPatientId] to [otherPatientId]. [roleOfOther] is who [otherPatientId] is relative
   * to the open client: [RelatedPersonRole.CHILD] means the other person is the child. [kinship] is
   * the adult's role toward the child (`MTH` / `FTH` / `GUARD`). When [isPrimaryCaregiver] is true,
   * this join is the child's only primary-caregiver RelatedPerson.
   */
  suspend fun link(
    currentPatientId: String,
    otherPatientId: String,
    roleOfOther: RelatedPersonRole,
    kinship: RelatedPersonKinship? = null,
    isPrimaryCaregiver: Boolean = false,
  ): Result<LinkResult> {
    val currentId = currentPatientId.extractLogicalIdUuid()
    val otherId = otherPatientId.extractLogicalIdUuid()
    if (currentId.isBlank() || otherId.isBlank()) {
      return Result.failure(IllegalArgumentException("Both clients are required to create a link"))
    }
    if (currentId == otherId) {
      return Result.failure(IllegalArgumentException("Cannot link a client to themselves"))
    }

    val current =
      runCatching { fhirEngine.get<Patient>(currentId) }
        .getOrElse {
          return Result.failure(it)
        }
    val other =
      runCatching { fhirEngine.get<Patient>(otherId) }
        .getOrElse {
          return Result.failure(it)
        }

    val (child, guardian) =
      when (roleOfOther) {
        RelatedPersonRole.CHILD -> other to current
        RelatedPersonRole.GUARDIAN -> current to other
      }

    val existing = findExistingLink(child.logicalId, guardian.logicalId)
    val relationship = kinship?.toCoding() ?: inferGuardianRelationship(guardian)
    if (existing != null) {
      return runCatching {
          if (isPrimaryCaregiver != existing.isPrimaryCaregiver()) {
            existing.setPrimaryCaregiver(isPrimaryCaregiver)
            defaultRepository.addOrUpdate(true, existing)
          }
          if (isPrimaryCaregiver) {
            clearPrimaryCaregiverOnSiblings(
              child.logicalId,
              exceptRelatedPersonId = existing.logicalId,
            )
          }
          LinkResult(existing, created = false)
        }
        .onFailure { Timber.e(it, "Failed to update existing RelatedPerson link") }
    }

    val relatedPerson =
      buildRelatedPersonLink(
        child = child,
        guardian = guardian,
        relationship = relationship,
        isPrimaryCaregiver = isPrimaryCaregiver,
      )
    return runCatching {
        defaultRepository.create(true, relatedPerson)
        if (isPrimaryCaregiver) {
          clearPrimaryCaregiverOnSiblings(
            child.logicalId,
            exceptRelatedPersonId = relatedPerson.logicalId,
          )
        }
        LinkResult(relatedPerson, created = true)
      }
      .onFailure { Timber.e(it, "Failed to persist RelatedPerson link") }
  }

  suspend fun hasPrimaryCaregiver(childPatientId: String): Boolean {
    val childId = childPatientId.extractLogicalIdUuid()
    return loadRelatedPersonsOrEmpty().any { rp ->
      rp.childPatientId() == childId && rp.isPrimaryCaregiver()
    }
  }

  private suspend fun clearPrimaryCaregiverOnSiblings(
    childPatientId: String,
    exceptRelatedPersonId: String,
  ) {
    val childId = childPatientId.extractLogicalIdUuid()
    loadRelatedPersonsOrEmpty()
      .filter { rp ->
        rp.logicalId != exceptRelatedPersonId &&
          rp.childPatientId() == childId &&
          rp.isPrimaryCaregiver()
      }
      .forEach { sibling ->
        sibling.setPrimaryCaregiver(false)
        defaultRepository.addOrUpdate(true, sibling)
      }
  }

  suspend fun findExistingLink(childPatientId: String, guardianPatientId: String): RelatedPerson? {
    val childId = childPatientId.extractLogicalIdUuid()
    val guardianId = guardianPatientId.extractLogicalIdUuid()
    return loadRelatedPersonsOrEmpty().firstOrNull { rp ->
      rp.patient?.reference?.extractLogicalIdUuid() == childId &&
        rp.isDependentOfGuardian(guardianId)
    }
  }

  private suspend fun loadRelatedPersonsOrEmpty(): List<RelatedPerson> =
    runCatching { loadRelatedPersons() }
      .onFailure { Timber.e(it, "Failed to load RelatedPersons") }
      .getOrDefault(emptyList())

  private suspend fun loadPatients(): List<Patient> =
    fhirEngine.batchedSearch<Patient>(Search(ResourceType.Patient)).map { it.resource }

  private suspend fun loadRelatedPersons(): List<RelatedPerson> =
    fhirEngine.batchedSearch<RelatedPerson>(Search(ResourceType.RelatedPerson)).map { it.resource }

  companion object {
    const val DEFAULT_SEARCH_LIMIT = 50
    const val DEFAULT_REGISTRATION_QUESTIONNAIRE_ID = "cdss-client-registration"
  }
}
