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

package org.smartregister.fhircore.engine.task

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.SearchResult
import com.google.android.fhir.get
import com.google.android.fhir.search.Search
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.CanonicalType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.PlanDefinition
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.TriggerDefinition
import org.hl7.fhir.r4.utils.FHIRPathEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Covers the 2026-08-12 addition to [NamedEventInterventionService]: reading the `tricc-process`/
 * `tricc-process-order` extensions tricc stamps on each Intervention PD's per-process action
 * (`feature/20260812-intervention-order-and-dedup.md`), sorting by that order, and grouping options
 * by originating PlanDefinition for the "select available care" picker
 * ([NamedEventInterventionService.listAvailableCarePlans]).
 */
class NamedEventInterventionServiceTest {

  private val fhirEngine: FhirEngine = mockk()
  private val fhirPathEngine: FHIRPathEngine = mockk(relaxed = true)
  private val workflowCarePlanGenerator: WorkflowCarePlanGenerator = mockk(relaxed = true)
  private lateinit var service: NamedEventInterventionService

  private val patient = Patient().apply { id = "patient-1" }

  @Before
  fun setUp() {
    service = NamedEventInterventionService(fhirEngine, fhirPathEngine, workflowCarePlanGenerator)
    coEvery { fhirEngine.get<Patient>(any()) } returns patient
  }

  private fun processAction(
    process: String,
    order: Int?,
    questionnaireId: String,
  ): PlanDefinition.PlanDefinitionActionComponent =
    PlanDefinition.PlanDefinitionActionComponent().apply {
      title = "Launch $process"
      addTrigger(
        TriggerDefinition().apply {
          type = TriggerDefinition.TriggerType.NAMEDEVENT
          name = process
        },
      )
      addExtension(
        "https://fhir.tricc.io/StructureDefinition/tricc-process",
        StringType(process),
      )
      if (order != null) {
        addExtension(
          "https://fhir.tricc.io/StructureDefinition/tricc-process-order",
          IntegerType(order),
        )
      }
      setDefinition(CanonicalType("https://fhir.tricc.io/Questionnaire/$questionnaireId"))
    }

  private fun wrapperPlanDefinition(
    id: String,
    vararg children: PlanDefinition.PlanDefinitionActionComponent,
  ): PlanDefinition =
    PlanDefinition().apply {
      this.id = id
      title = "$id - Intervention"
      addAction(
        PlanDefinition.PlanDefinitionActionComponent().apply {
          this.id = "available-care"
          addTrigger(
            TriggerDefinition().apply {
              type = TriggerDefinition.TriggerType.NAMEDEVENT
              name = "available-care"
            },
          )
          children.forEach { addAction(it) }
        },
      )
    }

  private fun stubPlanDefinitions(vararg planDefinitions: PlanDefinition) {
    // NamedEventInterventionService.loadPlanDefinitions() calls the batchedSearch() extension,
    // which pages through FhirEngine.search() itself — stub that, not batchedSearch directly
    // (matches the existing mocking convention, e.g. ConfigurationRegistryTest).
    coEvery { fhirEngine.search<PlanDefinition>(any<Search>()) } returns
      planDefinitions.map { pd -> SearchResult(resource = pd, included = null, revIncluded = null) }
  }

  @Test
  fun `listInterventions sorts by cpg-common-process order across a single PlanDefinition`() =
    runBlocking {
      stubPlanDefinitions(
        wrapperPlanDefinition(
          "pd-1",
          processAction(process = "triage", order = 10, questionnaireId = "q-triage"),
          processAction(process = "registration", order = 30, questionnaireId = "q-registration"),
        ),
      )

      val options = service.listInterventions("available-care", "Patient/patient-1")

      assertEquals(listOf("q-triage", "q-registration"), options.map { it.questionnaireId })
      assertEquals(listOf(10, 30), options.map { it.order })
      assertEquals(listOf("triage", "registration"), options.map { it.process })
    }

  @Test
  fun `listInterventions sorts across different PlanDefinitions by the same canonical order`() =
    runBlocking {
      stubPlanDefinitions(
        wrapperPlanDefinition(
          "pd-a",
          processAction(
            process = "dispense-medications",
            order = 100,
            questionnaireId = "q-dispense",
          ),
        ),
        wrapperPlanDefinition(
          "pd-b",
          processAction(process = "triage", order = 10, questionnaireId = "q-triage"),
        ),
      )

      val options = service.listInterventions("available-care", "Patient/patient-1")

      // Lowest order wins regardless of which PlanDefinition (pd-a vs pd-b) it came from, and
      // regardless of PlanDefinition/action declaration order.
      assertEquals(listOf("q-triage", "q-dispense"), options.map { it.questionnaireId })
    }

  @Test
  fun `option without the order extension sorts last`() = runBlocking {
    stubPlanDefinitions(
      wrapperPlanDefinition(
        "pd-1",
        processAction(process = "custom-process", order = null, questionnaireId = "q-custom"),
        processAction(process = "triage", order = 10, questionnaireId = "q-triage"),
      ),
    )

    val options = service.listInterventions("available-care", "Patient/patient-1")

    assertEquals(listOf("q-triage", "q-custom"), options.map { it.questionnaireId })
    assertNull(
      options
        .last { it.questionnaireId == "q-custom" }
        .order
        .let { if (it == Int.MAX_VALUE) null else it },
    )
  }

  @Test
  fun `listAvailableCarePlans groups options by originating PlanDefinition`() = runBlocking {
    stubPlanDefinitions(
      wrapperPlanDefinition(
        "pd-a",
        processAction(process = "registration", order = 30, questionnaireId = "q-registration"),
      ),
      wrapperPlanDefinition(
        "pd-b",
        processAction(process = "triage", order = 10, questionnaireId = "q-triage"),
        processAction(
          process = "dispense-medications",
          order = 100,
          questionnaireId = "q-dispense",
        ),
      ),
    )

    val plans = service.listAvailableCarePlans("available-care", "Patient/patient-1")

    assertEquals(setOf("pd-a", "pd-b"), plans.map { it.planDefinitionId }.toSet())
    val pdB = plans.first { it.planDefinitionId == "pd-b" }
    assertEquals(listOf("q-triage", "q-dispense"), pdB.options.map { it.questionnaireId })
  }

  @Test
  fun `listAvailableCarePlans omits a PlanDefinition with no launchable option`() = runBlocking {
    val noQuestionnaireAction =
      PlanDefinition.PlanDefinitionActionComponent().apply {
        title = "Contained task only"
        addTrigger(
          TriggerDefinition().apply {
            type = TriggerDefinition.TriggerType.NAMEDEVENT
            name = "registration"
          },
        )
        // No definitionCanonical at all -> never resolves to a Questionnaire id.
      }
    stubPlanDefinitions(wrapperPlanDefinition("pd-empty", noQuestionnaireAction))

    val plans = service.listAvailableCarePlans("available-care", "Patient/patient-1")

    assertEquals(emptyList<Any>(), plans)
  }

  @Test
  fun `listAvailableCarePlans title falls back to name then a default`() = runBlocking {
    val pdWithName =
      PlanDefinition().apply {
        id = "pd-name-only"
        name = "pd-name-only-name"
        addAction(
          PlanDefinition.PlanDefinitionActionComponent().apply {
            id = "available-care"
            addTrigger(
              TriggerDefinition().apply {
                type = TriggerDefinition.TriggerType.NAMEDEVENT
                name = "available-care"
              },
            )
            addAction(processAction(process = "triage", order = 10, questionnaireId = "q-triage"))
          },
        )
      }
    stubPlanDefinitions(pdWithName)

    val plans = service.listAvailableCarePlans("available-care", "Patient/patient-1")

    assertEquals("pd-name-only-name", plans.single().title)
  }
}
