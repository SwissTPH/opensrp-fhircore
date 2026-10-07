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

package org.smartregister.fhircore.quest.ui.questionnaire

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import ca.uhn.fhir.parser.IParser
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.knowledge.KnowledgeManager
import com.google.android.fhir.workflow.FhirOperator
import dagger.Lazy
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.mockk
import java.io.File
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.cqframework.cql.cql2elm.CqlTranslator
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.ModelManager
import org.cqframework.cql.cql2elm.quick.FhirLibrarySourceProvider
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Encounter
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.Library
import org.hl7.fhir.r4.model.Observation
import org.hl7.fhir.r4.model.Parameters
import org.hl7.fhir.r4.model.Patient
import org.hl7.fhir.r4.model.Quantity
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.Reference
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.hl7.fhir.r4.model.StructureMap
import org.hl7.fhir.r4.utils.StructureMapUtilities
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.data.local.ContentCache
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.ActionParameterType
import org.smartregister.fhircore.engine.rulesengine.ConfigRulesExecutor
import org.smartregister.fhircore.engine.rulesengine.RulesExecutor
import org.smartregister.fhircore.engine.task.FhirCarePlanGenerator
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.QuestionnaireMediaResolver
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.cqfLibraryUrls
import org.smartregister.fhircore.engine.util.extension.decodeResourceFromString
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.fhirpath.FhirPathDataExtractor
import org.smartregister.fhircore.engine.util.helper.TransformSupportServices
import org.smartregister.fhircore.engine.util.validation.ResourceValidationRequestHandler
import org.smartregister.fhircore.quest.app.fakes.Faker
import org.smartregister.fhircore.quest.robolectric.RobolectricTest
import org.smartregister.fhircore.quest.sdk.CqlBuilder

/**
 * End-to-end test for reading a value back within the same encounter, across two TRICC-generated
 * Questionnaires.
 * 1. The `triage` Questionnaire captures a temperature; its extraction StructureMap writes
 *    `Observation(code=fever, valueQuantity=38 C)` into the encounter.
 * 2. The `history-and-physical` Questionnaire opens later in the **same** encounter. Its
 *    `load_fever` item carries a CQL `initialExpression` (`Calc_load_fever` →
 *    `Helper.GetEncounterObservationValue('fever', …)`) and a `cqf-library` link.
 * 3. [QuestionnaireViewModel.populateQuestionnaire] evaluates that CQL against the real
 *    `FhirOperator` and seeds the answer, so the second form opens showing 38 C.
 *
 * Every artifact under `src/test/resources/content/tricc/fever-encounter-populate` is generated —
 * nothing here is hand-authored FHIR. Regenerate with:
 * ```
 * cd ../../tricc_oo && source .venv/bin/activate
 * python tests/build.py -i tests/data/yaml/fever_encounter_populate.yaml \
 *   -o out/ -I YamlStrategy -O FHIRStrategy
 * ```
 *
 * then copy `out/fever-triage/{questionnaire,library,structure-map}` over the fixtures and
 * `tests/data/yaml/fever_encounter_populate.yaml` over `source.yaml`. Because [compileCql]
 * translates every generated library and fails on any error, this test doubles as the cql-to-elm
 * gate for what TRICC emits — see `../../tricc_oo/fix/20260930-helper-cql-compile.md`.
 */
@HiltAndroidTest
@OptIn(ExperimentalCoroutinesApi::class)
class TriccEncounterPopulateE2ETest : RobolectricTest() {

  @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)

  @Inject lateinit var fhirEngine: FhirEngine

  @Inject lateinit var knowledgeManager: KnowledgeManager

  @Inject lateinit var fhirOperator: FhirOperator

  @Inject lateinit var rulesExecutor: RulesExecutor

  @Inject lateinit var fhirPathDataExtractor: FhirPathDataExtractor

  @Inject lateinit var sharedPreferencesHelper: SharedPreferencesHelper

  @Inject lateinit var configService: ConfigService

  @Inject lateinit var contentCache: ContentCache

  @Inject lateinit var parser: IParser

  @Inject lateinit var transformSupportServices: TransformSupportServices

  @Inject lateinit var fhirValidatorRequestHandlerProvider: Lazy<ResourceValidationRequestHandler>

  private val context: Application = ApplicationProvider.getApplicationContext()
  private val configurationRegistry = Faker.buildTestConfigurationRegistry()

  /**
   * Real dispatchers, deliberately not the injected ones: `FakeDispatcherProviderModule` maps every
   * dispatcher onto the test dispatcher, so anything the view model wraps in
   * `withContext(dispatcherProvider.default())` still runs on Robolectric's main thread — and
   * `FhirOperator` throws `BlockingMainThreadException` there. `populateQuestionnaire` swallows
   * that failure (`runCatching { … }.onFailure { Timber.e(…) }`), so with the fake provider the CQL
   * is silently never evaluated and the assertion below could never pass for the right reason.
   */
  private val realDispatcherProvider = object : DispatcherProvider {}

  private lateinit var questionnaireViewModel: QuestionnaireViewModel

  private val patient = Patient().apply { id = PATIENT_ID }
  private val encounter =
    Encounter().apply {
      id = ENCOUNTER_ID
      status = Encounter.EncounterStatus.INPROGRESS
      subject = Reference("Patient/$PATIENT_ID")
    }

  @Before
  fun setUp() {
    hiltRule.inject()

    val defaultRepository =
      DefaultRepository(
        fhirEngine = fhirEngine,
        dispatcherProvider = realDispatcherProvider,
        sharedPreferencesHelper = sharedPreferencesHelper,
        configurationRegistry = configurationRegistry,
        configService = configService,
        configRulesExecutor = mockk<ConfigRulesExecutor>(relaxed = true),
        fhirPathDataExtractor = fhirPathDataExtractor,
        parser = parser,
        context = context,
        contentCache = contentCache,
      )

    questionnaireViewModel =
      QuestionnaireViewModel(
        defaultRepository = defaultRepository,
        dispatcherProvider = realDispatcherProvider,
        fhirCarePlanGenerator = mockk<FhirCarePlanGenerator>(relaxed = true),
        rulesExecutor = rulesExecutor,
        transformSupportServices = transformSupportServices,
        sharedPreferencesHelper = sharedPreferencesHelper,
        fhirOperator = fhirOperator,
        fhirValidatorRequestHandlerProvider = fhirValidatorRequestHandlerProvider,
        fhirPathDataExtractor = fhirPathDataExtractor,
        configurationRegistry = configurationRegistry,
        questionnaireMediaResolver = mockk<QuestionnaireMediaResolver>(relaxed = true),
      )
  }

  /**
   * Step 1-2 of the flow: fill the triage form, extract with its StructureMap, assert the
   * Observation, and save it into the encounter. Returns the saved Observation.
   */
  private suspend fun recordTriageTemperature(temperature: Double): Observation {
    fhirEngine.create(patient, encounter)
    fhirEngine.create(compiledTriageStructureMap())

    val extracted =
      questionnaireViewModel.performExtraction(
        extractByStructureMap = true,
        questionnaire = questionnaire(TRIAGE_QUESTIONNAIRE),
        questionnaireResponse = triageResponse(temperature),
        context = context,
      )

    val observation =
      extracted.entry.map { it.resource }.filterIsInstance<Observation>().singleOrNull()
    Assert.assertNotNull("StructureMap extraction produced no Observation", observation)
    Assert.assertEquals(FEVER_CODE, observation!!.code.codingFirstRep.code)

    val extractedValue = observation.value as Quantity
    Assert.assertEquals(temperature, extractedValue.value.toDouble(), 0.0)
    Assert.assertEquals("C", extractedValue.unit)
    Assert.assertEquals("Cel", extractedValue.code)

    // The extraction map copies QuestionnaireResponse.encounter onto the Observation,
    // which is what scopes the CQL lookup to this visit.
    Assert.assertEquals("Encounter/$ENCOUNTER_ID", observation.encounter.reference)

    observation.id = TRIAGE_OBSERVATION_ID
    fhirEngine.create(observation)
    return observation
  }

  @Test
  fun `generated CQL reads back the temperature recorded earlier in this encounter`() =
    runTest(timeout = 180.seconds) {
      recordTriageTemperature(temperature = 38.0)
      indexGeneratedLibraries()

      val assessLibraryUrl = questionnaire(ASSESS_QUESTIONNAIRE).cqfLibraryUrls().single()

      // FhirOperator refuses to run on the main thread, which is where runTest puts us.
      val result =
        withContext(realDispatcherProvider.io()) {
          fhirOperator.evaluateLibrary(
            assessLibraryUrl,
            "Patient/$PATIENT_ID",
            Parameters().apply {
              addParameter(
                Parameters.ParametersParameterComponent().apply {
                  name = "encounterid"
                  value = StringType(ENCOUNTER_ID)
                },
              )
            },
            Bundle().apply {
              addEntry(Bundle.BundleEntryComponent().setResource(patient))
              addEntry(Bundle.BundleEntryComponent().setResource(encounter))
            },
            setOf(LOAD_FEVER_CQL_DEFINE),
          ) as Parameters
        }

      val value = result.getParameter(LOAD_FEVER_CQL_DEFINE)?.value
      Assert.assertNotNull(
        "$LOAD_FEVER_CQL_DEFINE returned nothing from the current encounter",
        value,
      )
      Assert.assertEquals(38.0, (value as Quantity).value.toDouble(), 0.0)
    }

  /**
   * Opens the assessment questionnaire the way the app does, in [ENCOUNTER_ID], and returns the
   * populated response. Asserts first that the fixture's populate item starts empty, so a prefilled
   * assertion afterwards can only be the CQL's doing.
   */
  private suspend fun openAssessQuestionnaire(): QuestionnaireResponse? {
    val assessQuestionnaire = questionnaire(ASSESS_QUESTIONNAIRE)
    val loadFeverItem = assessQuestionnaire.itemByLinkId(LOAD_FEVER_LINK_ID)
    Assert.assertNotNull("fixture lost its $LOAD_FEVER_LINK_ID item", loadFeverItem)
    Assert.assertFalse(
      "$LOAD_FEVER_LINK_ID must start with no initial value",
      loadFeverItem!!.hasInitial(),
    )

    return questionnaireViewModel
      .populateQuestionnaire(
        questionnaire = assessQuestionnaire,
        questionnaireConfig =
          QuestionnaireConfig(
            id = assessQuestionnaire.logicalId,
            resourceType = ResourceType.Patient,
            resourceIdentifier = PATIENT_ID,
            type = "DEFAULT",
          ),
        actionParameters =
          listOf(
            ActionParameter(
              key = "encounterId",
              paramType = ActionParameterType.QUESTIONNAIRE_RESPONSE_POPULATION_RESOURCE,
              resourceType = ResourceType.Encounter,
              value = ENCOUNTER_ID,
            ),
          ),
      )
      .first
  }

  @Test
  fun `second questionnaire in the same encounter prefills the temperature recorded by the first`() =
    runTest(timeout = 180.seconds) {
      recordTriageTemperature(temperature = 38.0)
      indexGeneratedLibraries()

      // ── Open the second questionnaire in the same encounter ───────────────────────
      val populatedResponse = openAssessQuestionnaire()

      // ── 5. The CQL initialExpression resolved to the triage temperature ──────────
      Assert.assertNotNull("populateQuestionnaire returned no response", populatedResponse)
      val populated = populatedResponse!!.itemByLinkId(LOAD_FEVER_LINK_ID)
      Assert.assertNotNull(
        "CQL initialExpression did not populate $LOAD_FEVER_LINK_ID",
        populated,
      )
      val answer = populated!!.answerFirstRep.value as Quantity
      Assert.assertEquals(38.0, answer.value.toDouble(), 0.0)
      Assert.assertEquals("C", answer.unit)
    }

  @Test
  fun `nothing recorded this encounter leaves the populate item unanswered`() =
    runTest(timeout = 180.seconds) {
      // Same questionnaire and library, but no triage Observation: GetEncounterObservationValue
      // finds nothing, so the CQL define is null and the item must stay unanswered rather than
      // be seeded with an empty one - a fake answer on a hidden item feeds the form's
      // calculations. Measured shape of that result: the Parameters entry is present under the
      // define's name with no value and no resource (`hasValue() == false`), which is why the
      // `cqlResultValue != null` guard is what catches it here.
      fhirEngine.create(patient, encounter)
      indexGeneratedLibraries()

      val populatedResponse = openAssessQuestionnaire()

      Assert.assertNotNull("populateQuestionnaire returned no response", populatedResponse)
      val populated = populatedResponse!!.itemByLinkId(LOAD_FEVER_LINK_ID)
      Assert.assertTrue(
        "a null CQL result must not seed an answer, but $LOAD_FEVER_LINK_ID got " +
          "${populated?.answer?.map { it.value }}",
        populated == null || !populated.hasAnswer(),
      )
    }

  // ---------------------------------------------------------------------------------
  // fixtures
  // ---------------------------------------------------------------------------------

  private fun questionnaire(fileName: String): Questionnaire =
    "$FIXTURE_DIR/questionnaire/$fileName".readFile().decodeResourceFromString()

  /**
   * HAPI executes `StructureMap.group[]`, and TRICC's JSON shell carries the FML in `text.div`
   * only, so the `.map` is compiled here the same way `push-to-fhir.sh` compiles it before upload,
   * then overlaid with the shell's identity. That id/url is what the Questionnaire's
   * `targetStructureMap` extension points at, which is how
   * [QuestionnaireViewModel.performExtraction] resolves it.
   */
  private fun compiledTriageStructureMap(): StructureMap {
    val shell =
      "$FIXTURE_DIR/structure-map/$TRIAGE_STRUCTURE_MAP_SHELL"
        .readFile()
        .decodeResourceFromString<StructureMap>()
    val fml = "$FIXTURE_DIR/structure-map/$TRIAGE_STRUCTURE_MAP_FML".readFile()
    return StructureMapUtilities(transformSupportServices.simpleWorkerContext)
      .parse(fml, shell.name ?: "TriageExtract")
      .apply {
        id = shell.idElement.idPart
        url = shell.url
        status = Enumerations.PublicationStatus.ACTIVE
      }
  }

  /** A filled-in triage form: temperature 38 C, in this patient's encounter. */
  private fun triageResponse(temperature: Double) =
    QuestionnaireResponse().apply {
      status = QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
      subject = Reference("Patient/$PATIENT_ID")
      encounter = Reference("Encounter/$ENCOUNTER_ID")
      authored = java.util.Date()
      addItem(
        QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
          linkId = "start_triage"
          addItem(
            QuestionnaireResponse.QuestionnaireResponseItemComponent().apply {
              linkId = FEVER_LINK_ID
              addAnswer(
                QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().apply {
                  value =
                    Quantity().apply {
                      value = temperature.toBigDecimal()
                      unit = "C"
                      system = "http://unitsofmeasure.org"
                      code = "Cel"
                    }
                },
              )
            },
          )
        },
      )
    }

  /**
   * Compiles every generated `.cql` to ELM and indexes it under the canonical url its Library shell
   * declares — the same url the `cqf-library` extension points at. [CqlBuilder.compile] fails the
   * test on any translation error, so a Helper library that does not compile cannot pass silently.
   */
  private suspend fun indexGeneratedLibraries() {
    val shells =
      "$FIXTURE_DIR/library"
        .readDir()
        .filter { it.name.endsWith(".json") }
        .map { it to it.readText().decodeResourceFromString<Library>() }
        // Helper first, so the libraries that `include` it are indexed after it exists.
        .sortedByDescending { (_, library) -> library.name.endsWith("Helper") }

    shells.forEach { (shellFile, shell) ->
      val cql = File(shellFile.parentFile, "${shell.name}.cql").readText()
      val translator = compileCql(cql, shellFile.parentFile)
      val elm = translator.toELM().identifier
      val library =
        CqlBuilder.assembleFhirLib(
            cqlStr = cql,
            jsonElmStr = translator.toJson(),
            xmlElmStr = translator.toXml(),
            libName = elm.id,
            libVersion = elm.version,
          )
          .apply {
            // KnowledgeManager resolves an `include` by name/version and the
            // Questionnaire's cqf-library by url; both have to line up with what TRICC
            // generated.
            id = elm.id
            url = shell.url
          }
      knowledgeManager.index(writeToFile(library))
    }
  }

  /**
   * Translates CQL to ELM, resolving `include`d libraries from sibling `.cql` files in
   * [libraryDir]. [CqlBuilder.compile] only registers [FhirLibrarySourceProvider] (which supplies
   * FHIRHelpers), so it cannot translate TRICC's per-segment libraries — they all `include` the
   * generated Helper library.
   *
   * Fails the test on any translation error, which is the point: it is what stops non-compiling
   * generated CQL from reaching a device.
   */
  private fun compileCql(cqlText: String, libraryDir: File): CqlTranslator {
    val libraryManager =
      LibraryManager(ModelManager()).apply {
        librarySourceLoader.registerProvider(FhirLibrarySourceProvider())
        librarySourceLoader.registerProvider(
          LibrarySourceProvider { identifier ->
            File(libraryDir, "${identifier.id}.cql").takeIf { it.exists() }?.inputStream()
          },
        )
      }
    val translator = CqlTranslator.fromText(cqlText, libraryManager)
    if (translator.errors.isNotEmpty()) {
      Assert.fail(
        translator.errors.joinToString(
          prefix = "Generated CQL does not compile:\n",
          separator = "\n",
        ) {
          "  ${it.locator?.toLocator() ?: "[n/a]"}: ${it.message}"
        },
      )
    }
    return translator
  }

  private fun writeToFile(resource: Resource): File =
    File(context.filesDir, "${resource.resourceType.name}-${resource.logicalId}.json").apply {
      parentFile?.mkdirs()
      writeText(resource.encodeResourceToString())
    }

  private fun Questionnaire.itemByLinkId(
    target: String,
  ): Questionnaire.QuestionnaireItemComponent? = item.findItem(target) { it.linkId to it.item }

  private fun QuestionnaireResponse.itemByLinkId(
    target: String,
  ): QuestionnaireResponse.QuestionnaireResponseItemComponent? =
    item.findItem(target) { it.linkId to it.item }

  private fun <T> List<T>.findItem(target: String, parts: (T) -> Pair<String?, List<T>>): T? {
    forEach { candidate ->
      val (linkId, children) = parts(candidate)
      if (linkId == target) return candidate
      children.findItem(target, parts)?.let {
        return it
      }
    }
    return null
  }

  companion object {
    private const val FIXTURE_DIR = "content/tricc/fever-encounter-populate"

    private const val PATIENT_ID = "patient-fever-e2e"
    private const val ENCOUNTER_ID = "encounter-fever-e2e"

    private const val TRIAGE_QUESTIONNAIRE = "Questionnaire-questionnaire-triage.json"
    private const val ASSESS_QUESTIONNAIRE = "Questionnaire-questionnaire-history-and-physical.json"
    private const val TRIAGE_STRUCTURE_MAP_FML = "StructureMap-fever-triage-triage-extract.map"
    private const val TRIAGE_STRUCTURE_MAP_SHELL = "StructureMap-fever-triage-triage-extract.json"

    /** Concept code TRICC derives from the node name, written onto `Observation.code`. */
    private const val FEVER_CODE = "fever"

    /** Versioned capture slot linkId on the triage form. */
    private const val FEVER_LINK_ID = "fever_Vv_1"

    /** Hidden populate item on the assessment form, fed by `Calc_load_fever`. */
    private const val LOAD_FEVER_LINK_ID = "load_fever"

    /** CQL define backing [LOAD_FEVER_LINK_ID]'s `initialExpression`. */
    private const val LOAD_FEVER_CQL_DEFINE = "Calc_load_fever"

    private const val TRIAGE_OBSERVATION_ID = "obs-fever-triage"
  }
}
