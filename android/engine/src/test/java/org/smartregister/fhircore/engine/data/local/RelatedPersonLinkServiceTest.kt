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
import com.google.android.fhir.SearchResult
import com.google.android.fhir.get
import com.google.android.fhir.search.Search
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.HumanName
import org.hl7.fhir.r4.model.Identifier
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.RelatedPerson
import org.hl7.fhir.r4.model.Resource
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.engine.util.extension.RELATED_PERSON_PATIENT_IDENTIFIER_SYSTEM
import org.smartregister.fhircore.engine.util.extension.RELATIONSHIP_FATHER
import org.smartregister.fhircore.engine.util.extension.RELATIONSHIP_GUARDIAN
import org.smartregister.fhircore.engine.util.extension.RELATIONSHIP_MOTHER
import org.smartregister.fhircore.engine.util.extension.RelatedPersonAgeFilter
import org.smartregister.fhircore.engine.util.extension.RelatedPersonKinship
import org.smartregister.fhircore.engine.util.extension.RelatedPersonRole
import org.smartregister.fhircore.engine.util.extension.buildRelatedPersonLink
import org.smartregister.fhircore.engine.util.extension.childPatientId
import org.smartregister.fhircore.engine.util.extension.defaultAgeFilter
import org.smartregister.fhircore.engine.util.extension.guardianPatientReference
import org.smartregister.fhircore.engine.util.extension.inferGuardianRelationship
import org.smartregister.fhircore.engine.util.extension.isPrimaryCaregiver
import org.smartregister.fhircore.engine.util.extension.matchesAgeFilter
import org.smartregister.fhircore.engine.util.extension.matchesNameQuery
import org.smartregister.fhircore.engine.util.extension.patientUrlIdentifier
import org.smartregister.fhircore.engine.util.extension.setPrimaryCaregiver

class RelatedPersonLinkServiceTest {

  private val fhirEngine: FhirEngine = mockk()
  private val defaultRepository: DefaultRepository = mockk(relaxed = true)
  private lateinit var service: RelatedPersonLinkService

  private val now: LocalDate = LocalDate.of(2026, 8, 13)

  @Before
  fun setUp() {
    service = RelatedPersonLinkService(fhirEngine, defaultRepository)
  }

  @Test
  fun defaultAgeFilter_isUnder18ForChildAndAdultForGuardian() {
    Assert.assertEquals(RelatedPersonAgeFilter.UNDER_18, RelatedPersonRole.CHILD.defaultAgeFilter())
    Assert.assertEquals(
      RelatedPersonAgeFilter.AGE_18_OR_OVER,
      RelatedPersonRole.GUARDIAN.defaultAgeFilter(),
    )
  }

  @Test
  fun inferGuardianRelationship_usesGender() {
    Assert.assertEquals(
      RELATIONSHIP_MOTHER,
      inferGuardianRelationship(patient("g", gender = Enumerations.AdministrativeGender.FEMALE))
        .code,
    )
    Assert.assertEquals(
      RELATIONSHIP_FATHER,
      inferGuardianRelationship(patient("g", gender = Enumerations.AdministrativeGender.MALE)).code,
    )
    Assert.assertEquals(
      RELATIONSHIP_GUARDIAN,
      inferGuardianRelationship(patient("g", gender = Enumerations.AdministrativeGender.UNKNOWN))
        .code,
    )
  }

  @Test
  fun buildRelatedPersonLink_patientIsAlwaysChild_identifierIsGuardian() {
    val child = patient("child-1", given = "Jean", family = "Doe", yearsAgo = 4)
    val mother =
      patient(
        "mother-1",
        given = "Marie",
        family = "Doe",
        yearsAgo = 30,
        gender = Enumerations.AdministrativeGender.FEMALE,
      )

    val rp = buildRelatedPersonLink(child, mother)

    Assert.assertEquals("child-1", rp.childPatientId())
    Assert.assertEquals("Patient/mother-1", rp.guardianPatientReference())
    Assert.assertEquals(Identifier.IdentifierUse.SECONDARY, rp.identifierFirstRep.use)
    Assert.assertEquals(RELATED_PERSON_PATIENT_IDENTIFIER_SYSTEM, rp.identifierFirstRep.system)
    Assert.assertEquals(RELATIONSHIP_MOTHER, rp.relationshipFirstRep.codingFirstRep.code)
    Assert.assertEquals("Marie", rp.nameFirstRep.given.first().value)
    Assert.assertEquals("Doe", rp.nameFirstRep.family)
    Assert.assertEquals(Enumerations.AdministrativeGender.FEMALE, rp.gender)
  }

  @Test
  fun matchesAgeFilter_under18AndAdult() {
    val child = patient("c", yearsAgo = 7)
    val adult = patient("a", yearsAgo = 22)
    val unknown = Patient().apply { id = "u" }

    Assert.assertTrue(child.matchesAgeFilter(RelatedPersonAgeFilter.UNDER_18, now))
    Assert.assertFalse(child.matchesAgeFilter(RelatedPersonAgeFilter.AGE_18_OR_OVER, now))
    Assert.assertFalse(adult.matchesAgeFilter(RelatedPersonAgeFilter.UNDER_18, now))
    Assert.assertTrue(adult.matchesAgeFilter(RelatedPersonAgeFilter.AGE_18_OR_OVER, now))
    Assert.assertTrue(unknown.matchesAgeFilter(RelatedPersonAgeFilter.UNDER_18, now))
    Assert.assertTrue(unknown.matchesAgeFilter(RelatedPersonAgeFilter.AGE_18_OR_OVER, now))
  }

  @Test
  fun matchesNameQuery_givenFamilyAndEmpty() {
    val patient = patient("p", given = "Jean", family = "Uwimana")
    Assert.assertTrue(patient.matchesNameQuery(""))
    Assert.assertTrue(patient.matchesNameQuery("jean"))
    Assert.assertTrue(patient.matchesNameQuery("UWI"))
    Assert.assertFalse(patient.matchesNameQuery("marie"))
  }

  @Test
  fun searchPatients_filtersByNameAgeAndExclude() = runBlocking {
    val child = patient("child-1", given = "Jean", family = "Doe", yearsAgo = 5)
    val otherChild = patient("child-2", given = "Paul", family = "Doe", yearsAgo = 8)
    val adult = patient("adult-1", given = "Marie", family = "Doe", yearsAgo = 32)
    val current = patient("current", given = "Jeanette", family = "Doe", yearsAgo = 4)

    coEvery { fhirEngine.search<Patient>(any<Search>()) } returns
      listOf(child, otherChild, adult, current).map { it.asSearchResult() }

    val results =
      service.searchPatients(
        query = "doe",
        ageFilter = RelatedPersonAgeFilter.UNDER_18,
        excludePatientId = "current",
        now = now,
      )

    Assert.assertEquals(listOf("child-1", "child-2"), results.map { it.idElement.idPart })
  }

  @Test
  fun link_addingChild_setsPatientToOtherAndIdentifierToCurrent() = runBlocking {
    val adult =
      patient(
        "adult-1",
        given = "Marie",
        yearsAgo = 30,
        gender = Enumerations.AdministrativeGender.FEMALE,
      )
    val child = patient("child-1", given = "Jean", yearsAgo = 4)
    coEvery { fhirEngine.get<Patient>("adult-1") } returns adult
    coEvery { fhirEngine.get<Patient>("child-1") } returns child
    coEvery { fhirEngine.search<RelatedPerson>(any<Search>()) } returns emptyList()
    coEvery { defaultRepository.create(any(), *anyVararg()) } returns listOf("rp-1")

    val result =
      service.link(
        currentPatientId = "adult-1",
        otherPatientId = "child-1",
        roleOfOther = RelatedPersonRole.CHILD,
        kinship = RelatedPersonKinship.MOTHER,
        isPrimaryCaregiver = true,
      )

    Assert.assertTrue(result.isSuccess)
    Assert.assertTrue(result.getOrThrow().created)
    val created = slot<Resource>()
    coVerify { defaultRepository.create(true, capture(created)) }
    val rp = created.captured as RelatedPerson
    Assert.assertEquals("child-1", rp.childPatientId())
    Assert.assertEquals("Patient/adult-1", rp.guardianPatientReference())
    Assert.assertEquals(RELATIONSHIP_MOTHER, rp.relationshipFirstRep.codingFirstRep.code)
    Assert.assertTrue(rp.isPrimaryCaregiver())
  }

  @Test
  fun link_addingGuardian_setsPatientToCurrentAndIdentifierToOther() = runBlocking {
    val child = patient("child-1", given = "Jean", yearsAgo = 4)
    val adult =
      patient(
        "adult-1",
        given = "Marie",
        yearsAgo = 30,
        gender = Enumerations.AdministrativeGender.FEMALE,
      )
    coEvery { fhirEngine.get<Patient>("child-1") } returns child
    coEvery { fhirEngine.get<Patient>("adult-1") } returns adult
    coEvery { fhirEngine.search<RelatedPerson>(any<Search>()) } returns emptyList()
    coEvery { defaultRepository.create(any(), *anyVararg()) } returns listOf("rp-1")

    val result =
      service.link(
        currentPatientId = "child-1",
        otherPatientId = "adult-1",
        roleOfOther = RelatedPersonRole.GUARDIAN,
        kinship = RelatedPersonKinship.FATHER,
        isPrimaryCaregiver = false,
      )

    Assert.assertTrue(result.isSuccess)
    val created = slot<Resource>()
    coVerify { defaultRepository.create(true, capture(created)) }
    val rp = created.captured as RelatedPerson
    Assert.assertEquals("child-1", rp.childPatientId())
    Assert.assertEquals("Patient/adult-1", rp.guardianPatientReference())
    Assert.assertEquals(RELATIONSHIP_FATHER, rp.relationshipFirstRep.codingFirstRep.code)
    Assert.assertFalse(rp.isPrimaryCaregiver())
  }

  @Test
  fun link_doesNotCreateDuplicate() = runBlocking {
    val child = patient("child-1")
    val adult = patient("adult-1")
    val existing =
      RelatedPerson().apply {
        id = "rp-existing"
        patient = Reference("Patient/child-1")
        addIdentifier(patientUrlIdentifier("Patient/adult-1"))
      }
    coEvery { fhirEngine.get<Patient>("child-1") } returns child
    coEvery { fhirEngine.get<Patient>("adult-1") } returns adult
    coEvery { fhirEngine.search<RelatedPerson>(any<Search>()) } returns
      listOf(existing.asSearchResult())

    val result = service.link("child-1", "adult-1", RelatedPersonRole.GUARDIAN)

    Assert.assertTrue(result.isSuccess)
    Assert.assertFalse(result.getOrThrow().created)
    Assert.assertEquals("rp-existing", result.getOrThrow().relatedPerson.idElement.idPart)
    coVerify(exactly = 0) { defaultRepository.create(any(), *anyVararg()) }
  }

  @Test
  fun link_newPrimaryCaregiver_clearsSiblingFlag() = runBlocking {
    val child = patient("child-1")
    val father = patient("father-1", gender = Enumerations.AdministrativeGender.MALE)
    val existingMother =
      RelatedPerson().apply {
        id = "rp-mother"
        patient = Reference("Patient/child-1")
        addIdentifier(patientUrlIdentifier("Patient/mother-1"))
        setPrimaryCaregiver(true)
      }
    coEvery { fhirEngine.get<Patient>("child-1") } returns child
    coEvery { fhirEngine.get<Patient>("father-1") } returns father
    coEvery { fhirEngine.search<RelatedPerson>(any<Search>()) } returns
      listOf(existingMother.asSearchResult())
    coEvery { defaultRepository.create(any(), *anyVararg()) } returns listOf("rp-father")
    coEvery { defaultRepository.addOrUpdate(any(), any<Resource>()) } returns Unit

    val result =
      service.link(
        currentPatientId = "child-1",
        otherPatientId = "father-1",
        roleOfOther = RelatedPersonRole.GUARDIAN,
        kinship = RelatedPersonKinship.FATHER,
        isPrimaryCaregiver = true,
      )

    Assert.assertTrue(result.isSuccess)
    Assert.assertTrue(result.getOrThrow().created)
    coVerify {
      defaultRepository.addOrUpdate(
        true,
        match { resource ->
          resource is RelatedPerson &&
            resource.idElement.idPart == "rp-mother" &&
            !resource.isPrimaryCaregiver()
        },
      )
    }
  }

  @Test
  fun link_rejectsSelfLink() = runBlocking {
    val result = service.link("same", "Patient/same", RelatedPersonRole.CHILD)
    Assert.assertTrue(result.isFailure)
  }

  private fun patient(
    id: String,
    given: String = "Name",
    family: String = "Test",
    yearsAgo: Int? = null,
    gender: Enumerations.AdministrativeGender? = null,
  ): Patient =
    Patient().apply {
      this.id = id
      active = true
      addName(
        HumanName().apply {
          this.family = family
          addGiven(given)
        },
      )
      gender?.let { this.gender = it }
      yearsAgo?.let {
        birthDate =
          Date.from(now.minusYears(it.toLong()).atStartOfDay(ZoneId.systemDefault()).toInstant())
      }
    }

  private fun <T : Resource> T.asSearchResult(): SearchResult<T> =
    SearchResult(resource = this, included = null, revIncluded = null)
}
