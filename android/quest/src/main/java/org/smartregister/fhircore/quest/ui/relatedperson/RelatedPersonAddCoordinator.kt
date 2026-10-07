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

package org.smartregister.fhircore.quest.ui.relatedperson

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentResultListener
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import dagger.hilt.android.EntryPointAccessors
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.hl7.fhir.r4.model.ResourceType
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.data.local.RelatedPersonLinkService
import org.smartregister.fhircore.engine.util.extension.RelatedPersonKinship
import org.smartregister.fhircore.engine.util.extension.RelatedPersonRole
import org.smartregister.fhircore.engine.util.extension.defaultAgeFilter
import org.smartregister.fhircore.engine.util.extension.extractLogicalIdUuid
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.di.RelatedPersonLinkEntryPoint
import org.smartregister.fhircore.quest.event.AppEvent
import org.smartregister.fhircore.quest.event.EventBus
import org.smartregister.fhircore.quest.ui.shared.QuestionnaireHandler
import timber.log.Timber

/**
 * Profile "Add related person" flow: who (child/mother/father/guardian) → caregiver? → search or
 * standard client registration → persist RelatedPerson. See
 * `feature/20260813-related-person-picker.md`.
 */
object RelatedPersonAddCoordinator {

  fun start(
    navController: NavController,
    subjectId: String?,
    registrationQuestionnaireId: String,
  ) {
    val context = navController.context
    val currentPatientId = subjectId?.extractLogicalIdUuid()
    if (currentPatientId.isNullOrBlank()) {
      context.showToast(context.getString(R.string.related_person_no_client), Toast.LENGTH_SHORT)
      return
    }
    val lifecycleOwner = context as? LifecycleOwner
    if (lifecycleOwner == null) {
      Timber.e("ADD_RELATED_PERSON requires a LifecycleOwner context")
      context.showToast(
        context.getString(R.string.related_person_unable_to_start),
        Toast.LENGTH_SHORT,
      )
      return
    }

    val entryPoint =
      EntryPointAccessors.fromApplication(
        context.applicationContext,
        RelatedPersonLinkEntryPoint::class.java,
      )

    lifecycleOwner.lifecycleScope.launch {
      runAddRelatedPersonFlow(
        context = context,
        navController = navController,
        service = entryPoint.relatedPersonLinkService(),
        eventBus = entryPoint.eventBus(),
        currentPatientId = currentPatientId,
        registrationQuestionnaireId = registrationQuestionnaireId,
      )
    }
  }

  internal suspend fun runAddRelatedPersonFlow(
    context: Context,
    navController: NavController,
    service: RelatedPersonLinkService,
    eventBus: EventBus,
    currentPatientId: String,
    registrationQuestionnaireId: String,
  ) {
    val who = awaitWhoChoice(context) ?: return
    val role = who.toRole()
    val kinship = who.toKinship() ?: awaitKinshipChoice(context) ?: return
    val childIdForDefault = if (role == RelatedPersonRole.CHILD) null else currentPatientId
    val defaultCaregiver =
      childIdForDefault == null || !service.hasPrimaryCaregiver(childIdForDefault)
    val isPrimaryCaregiver = awaitCaregiverChoice(context, defaultCaregiver) ?: return
    val source = awaitSourceChoice(context) ?: return
    val otherPatientId =
      when (source) {
        LinkSource.SEARCH -> awaitPatientSearch(context, currentPatientId, role)
        LinkSource.CREATE ->
          launchRegistrationAndAwaitPatientId(
            navController = navController,
            eventBus = eventBus,
            questionnaireId = registrationQuestionnaireId,
          )
      } ?: return

    val result =
      service.link(
        currentPatientId = currentPatientId,
        otherPatientId = otherPatientId,
        roleOfOther = role,
        kinship = kinship,
        isPrimaryCaregiver = isPrimaryCaregiver,
      )
    result
      .onSuccess { link ->
        val message =
          if (link.created) {
            context.getString(R.string.related_person_saved)
          } else {
            context.getString(R.string.related_person_already_linked)
          }
        context.showToast(message, Toast.LENGTH_SHORT)
        eventBus.triggerEvent(AppEvent.RefreshData)
      }
      .onFailure {
        Timber.e(it, "Failed to create RelatedPerson link")
        context.showToast(
          context.getString(R.string.related_person_save_failed),
          Toast.LENGTH_LONG,
        )
      }
  }

  private suspend fun awaitWhoChoice(context: Context): RelatedPersonWho? =
    awaitSingleChoice(
      context = context,
      title = context.getString(R.string.related_person_who_title),
      items =
        listOf(
          RelatedPersonWho.CHILD to context.getString(R.string.related_person_role_child),
          RelatedPersonWho.MOTHER to context.getString(R.string.related_person_role_mother),
          RelatedPersonWho.FATHER to context.getString(R.string.related_person_role_father),
          RelatedPersonWho.GUARDIAN to context.getString(R.string.related_person_role_guardian),
        ),
    )

  private suspend fun awaitKinshipChoice(context: Context): RelatedPersonKinship? =
    awaitSingleChoice(
      context = context,
      title = context.getString(R.string.related_person_kinship_title),
      items =
        listOf(
          RelatedPersonKinship.MOTHER to context.getString(R.string.related_person_role_mother),
          RelatedPersonKinship.FATHER to context.getString(R.string.related_person_role_father),
          RelatedPersonKinship.GUARDIAN to context.getString(R.string.related_person_role_guardian),
        ),
    )

  private suspend fun awaitCaregiverChoice(context: Context, defaultYes: Boolean): Boolean? {
    val yes = true to context.getString(R.string.related_person_caregiver_yes)
    val no = false to context.getString(R.string.related_person_caregiver_no)
    val items = if (defaultYes) listOf(yes, no) else listOf(no, yes)
    return awaitSingleChoice(
      context = context,
      title = context.getString(R.string.related_person_caregiver_title),
      items = items,
    )
  }

  private suspend fun awaitSourceChoice(context: Context): LinkSource? =
    awaitSingleChoice(
      context = context,
      title = context.getString(R.string.related_person_how_title),
      items =
        listOf(
          LinkSource.SEARCH to context.getString(R.string.related_person_search_existing),
          LinkSource.CREATE to context.getString(R.string.related_person_create_new),
        ),
    )

  private suspend fun <T> awaitSingleChoice(
    context: Context,
    title: String,
    items: List<Pair<T, String>>,
  ): T? = suspendCancellableCoroutine { cont ->
    val labels = items.map { it.second }.toTypedArray()
    val dialog =
      AlertDialog.Builder(context)
        .setTitle(title)
        .setItems(labels) { _, which -> cont.resume(items.getOrNull(which)?.first) {} }
        .setOnCancelListener { cont.resume(null) {} }
        .show()
    cont.invokeOnCancellation { dialog.dismiss() }
  }

  private suspend fun awaitPatientSearch(
    context: Context,
    excludePatientId: String,
    role: RelatedPersonRole,
  ): String? {
    val activity = context as? AppCompatActivity ?: return null
    return suspendCancellableCoroutine { cont ->
      val fm = activity.supportFragmentManager
      val listener = FragmentResultListener { _, bundle ->
        if (cont.isActive) {
          cont.resume(bundle.getString(RelatedPersonSearchDialogFragment.RESULT_PATIENT_ID)) {}
        }
      }
      fm.setFragmentResultListener(
        RelatedPersonSearchDialogFragment.REQUEST_KEY,
        activity,
        listener,
      )
      RelatedPersonSearchDialogFragment.newInstance(
          excludePatientId = excludePatientId,
          ageFilter = role.defaultAgeFilter(),
        )
        .show(fm, RelatedPersonSearchDialogFragment.TAG)
      cont.invokeOnCancellation {
        fm.clearFragmentResultListener(RelatedPersonSearchDialogFragment.REQUEST_KEY)
        (fm.findFragmentByTag(RelatedPersonSearchDialogFragment.TAG)
            as? RelatedPersonSearchDialogFragment)
          ?.dismissAllowingStateLoss()
      }
    }
  }

  private suspend fun launchRegistrationAndAwaitPatientId(
    navController: NavController,
    eventBus: EventBus,
    questionnaireId: String,
  ): String? {
    val handler = navController.context as? QuestionnaireHandler
    if (handler == null) {
      navController.context.showToast(
        navController.context.getString(R.string.related_person_unable_to_start),
        Toast.LENGTH_SHORT,
      )
      return null
    }
    val consumerId = "add-related-person-${UUID.randomUUID()}"
    handler.launchQuestionnaire(
      context = navController.context,
      questionnaireConfig =
        QuestionnaireConfig(
          id = questionnaireId,
          title = navController.context.getString(R.string.related_person_register_title),
          resourceType = ResourceType.Patient,
          saveButtonText = navController.context.getString(R.string.related_person_register_save),
        ),
      actionParams = emptyList(),
    )

    val submission =
      eventBus.events
        .getFor(consumerId)
        .filterIsInstance<AppEvent.OnSubmitQuestionnaire>()
        .first { it.questionnaireSubmission.questionnaireConfig.id == questionnaireId }
        .questionnaireSubmission

    val extractedPatientId =
      submission.extractedResourceIds
        .firstOrNull { it.resourceType.equals(ResourceType.Patient.name, ignoreCase = true) }
        ?.idPart
        ?.takeIf { it.isNotBlank() }
    if (extractedPatientId.isNullOrBlank()) {
      Timber.e("Client registration completed but no Patient id was extracted")
      navController.context.showToast(
        navController.context.getString(R.string.related_person_no_extracted_patient),
        Toast.LENGTH_LONG,
      )
      return null
    }
    return extractedPatientId
  }

  private enum class LinkSource {
    SEARCH,
    CREATE,
  }

  /** Q1: who the other person is. Child needs a follow-up kinship for the current adult. */
  private enum class RelatedPersonWho {
    CHILD,
    MOTHER,
    FATHER,
    GUARDIAN,
  }

  private fun RelatedPersonWho.toRole(): RelatedPersonRole =
    if (this == RelatedPersonWho.CHILD) RelatedPersonRole.CHILD else RelatedPersonRole.GUARDIAN

  private fun RelatedPersonWho.toKinship(): RelatedPersonKinship? =
    when (this) {
      RelatedPersonWho.CHILD -> null
      RelatedPersonWho.MOTHER -> RelatedPersonKinship.MOTHER
      RelatedPersonWho.FATHER -> RelatedPersonKinship.FATHER
      RelatedPersonWho.GUARDIAN -> RelatedPersonKinship.GUARDIAN
    }
}
