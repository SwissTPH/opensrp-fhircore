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

package org.smartregister.fhircore.quest

import com.google.android.fhir.datacapture.mapping.ResourceMapper
import com.google.android.fhir.datacapture.mapping.StructureMapExtractionContext
import kotlinx.coroutines.test.runTest
import org.hl7.fhir.r4.context.IWorkerContext
import org.hl7.fhir.r4.context.SimpleWorkerContext
import org.hl7.fhir.r4.model.Group
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StructureMap
import org.hl7.fhir.r4.utils.StructureMapUtilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.smartregister.fhircore.engine.util.extension.decodeResourceFromString
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.helper.TransformSupportServices
import org.smartregister.fhircore.quest.robolectric.RobolectricTest

/**
 * Compiles the household registration FML and checks it yields the Group that householdRegister
 * actually queries for. Mirrors [CdssRegistrationDiagnosticTest], which does the same for client
 * registration: the compiled StructureMap JSON is written back beside the FML and into
 * conf/fhir-seed so the assets and the seed stay in step.
 */
class CdssHouseholdRegistrationTest : RobolectricTest() {

  private val worker =
    SimpleWorkerContext().apply {
      this.setExpansionProfile(Parameters())
      this.isCanRunWithoutTerminology = true
    }
  private val transformSupportServices = TransformSupportServices(worker)

  private fun basePath() =
    "${System.getProperty("user.dir")}/src/main/assets/configs/cdss/resources"

  private suspend fun extract(
    questionnaire: Questionnaire,
    structureMap: StructureMap,
    questionnaireResponse: QuestionnaireResponse,
  ) =
    ResourceMapper.extract(
      questionnaire = questionnaire,
      questionnaireResponse = questionnaireResponse,
      structureMapExtractionContext =
        StructureMapExtractionContext(
          transformSupportServices = transformSupportServices,
          structureMapProvider = { _: String?, _: IWorkerContext -> structureMap },
        ),
    )

  @Test
  @kotlinx.coroutines.ExperimentalCoroutinesApi
  fun compileMapAndExtractHousehold() =
    runTest(timeout = kotlin.time.Duration.parse("90s")) {
      val questionnaire =
        java.io
          .File("${basePath()}/questionnaire/cdss-household-registration.json")
          .readText()
          .decodeResourceFromString<Questionnaire>()
      val mapText =
        java.io.File("${basePath()}/structuremap/cdss-household-registration.map").readText()

      val compiled =
        StructureMapUtilities(worker, transformSupportServices)
          .parse(mapText, "CdssHouseholdRegistration")
          .apply {
            id = "cdss-household-registration"
            url = "https://fhir.opensrp.io/cdss/StructureMap/cdss-household-registration"
            name = "CdssHouseholdRegistration"
            title = "CDSS household registration"
            status = org.hl7.fhir.r4.model.Enumerations.PublicationStatus.ACTIVE
          }

      val compiledJson = compiled.encodeResourceToString()
      java.io
        .File("${basePath()}/structuremap/cdss-household-registration.json")
        .writeText(compiledJson + "\n")
      java.io
        .File(
          "${System.getProperty("user.dir")}/../../conf/fhir-seed/resources/StructureMap-cdss-household-registration.json",
        )
        .apply {
          parentFile?.mkdirs()
          if (parentFile?.exists() == true) writeText(compiledJson + "\n")
        }

      val response =
        """
        {
          "resourceType": "QuestionnaireResponse",
          "status": "completed",
          "item": [
            { "linkId": "hh-name", "answer": [ { "valueString": "Mwangi" } ] },
            { "linkId": "hh-number", "answer": [ { "valueString": "1042" } ] },
            { "linkId": "hh-village", "answer": [ { "valueString": "Kibera" } ] }
          ]
        }
        """
          .trimIndent()
          .decodeResourceFromString<QuestionnaireResponse>()

      val bundle = extract(questionnaire, compiled, response)
      val group = bundle.entry.map { it.resource }.filterIsInstance<Group>().firstOrNull()

      assertNotNull("extraction produced no Group", group)
      group!!
      assertEquals("Mwangi", group.name)
      assertTrue(group.active)
      assertTrue(group.actual)
      assertEquals(Group.GroupType.PERSON, group.type)

      // householdRegister selects on these two, so they are what make the row appear at all.
      assertEquals("https://www.snomed.org", group.code.codingFirstRep.system)
      assertEquals("35359004", group.code.codingFirstRep.code)

      // The register card reads identifier[0].value and characteristic[0].code.text.
      assertEquals("1042", group.identifierFirstRep.value)
      assertEquals("Kibera", group.characteristicFirstRep.code.text)
    }
}
