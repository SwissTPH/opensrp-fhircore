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

package org.smartregister.fhircore.quest.ui.sdc.edittext

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.dimensionResource
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.extensions.getRequiredOrOptionalText
import com.google.android.fhir.datacapture.extensions.localizedFlyoverAnnotatedString
import com.google.android.fhir.datacapture.views.QuestionnaireViewItem
import com.google.android.fhir.datacapture.views.compose.Header
import com.google.android.fhir.datacapture.views.compose.MediaItem
import com.google.android.fhir.datacapture.views.compose.UNIT_TEXT_TEST_TAG
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemComposeViewHolderDelegate
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent

/** See http://hl7.org/fhir/uv/sdc/StructureDefinition-sdc-questionnaire-itemMedia.html. */
private const val EXTENSION_ITEM_MEDIA_URL =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-itemMedia"

/** See http://hl7.org/fhir/R4/extension-questionnaire-unit.html. */
private const val EXTENSION_QUESTIONNAIRE_UNIT_URL =
  "http://hl7.org/fhir/StructureDefinition/questionnaire-unit"

/**
 * Renders a questionnaire text question exactly as the SDK's own
 * `com.google.android.fhir.datacapture.views.factories.EditTextViewHolderDelegate` does - header,
 * item media, text field, unit suffix - but backed by [DebouncedEditTextField], which does not lose
 * keystrokes while an answer is being committed. See [DebouncedEditTextField] for the details.
 *
 * The parameters mirror the SDK delegate's so that the per-type factories in
 * [DebouncedEditTextViewHolderFactories] stay a line-by-line match with the SDK ones they replace.
 */
class DebouncedEditTextViewHolderDelegate(
  private val keyboardOptions: KeyboardOptions,
  private val uiInputText: (QuestionnaireViewItem) -> String?,
  private val uiValidationMessage: (QuestionnaireViewItem, Context) -> String?,
  private val handleInput: suspend (String, QuestionnaireViewItem) -> Unit,
  private val isMultiLine: Boolean = false,
) : QuestionnaireItemComposeViewHolderDelegate {

  @Composable
  override fun Content(questionnaireViewItem: QuestionnaireViewItem) {
    val context = LocalContext.current
    val text = uiInputText(questionnaireViewItem) ?: ""
    val validationUiMessage = uiValidationMessage(questionnaireViewItem, context)
    val questionnaireItem = questionnaireViewItem.questionnaireItem
    val unit = questionnaireItem.unitCode
    // linkId alone is not unique inside a repeated group, and a holder recycled between two
    // instances of the same question would otherwise keep the previous instance's pending text.
    // The response item is stable across answer changes (the view model mutates it in place) and
    // unique per repeat, so it identifies the field actually being edited.
    val stateKey =
      "${questionnaireItem.linkId}#" +
        System.identityHashCode(questionnaireViewItem.getQuestionnaireResponseItem())

    Column(
      modifier =
        Modifier.padding(
          horizontal = dimensionResource(R.dimen.item_margin_horizontal),
          vertical = dimensionResource(R.dimen.item_margin_vertical),
        ),
    ) {
      Header(questionnaireViewItem)

      questionnaireItem.itemMediaAttachment?.let { MediaItem(it) }

      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        DebouncedEditTextField(
          modifier = Modifier.weight(1f),
          stateKey = stateKey,
          externalText = text,
          hint = questionnaireViewItem.enabledDisplayItems.localizedFlyoverAnnotatedString,
          helperText =
            validationUiMessage.takeIf { !it.isNullOrBlank() }
              ?: getRequiredOrOptionalText(questionnaireViewItem, context),
          isError = !validationUiMessage.isNullOrBlank(),
          isReadOnly = questionnaireItem.readOnly,
          keyboardOptions = keyboardOptions,
          isMultiLine = isMultiLine,
          onCommit = { input -> handleInput(input, questionnaireViewItem) },
        )

        if (!unit.isNullOrBlank()) {
          UnitText(unit)
        }
      }
    }
  }
}

@Composable
private fun UnitText(unitString: String) {
  Box(
    modifier = Modifier.padding(horizontal = dimensionResource(R.dimen.item_margin_horizontal)),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      unitString,
      style = MaterialTheme.typography.bodyMedium,
      modifier = Modifier.testTag(UNIT_TEXT_TEST_TAG),
    )
  }
}

/**
 * The SDK's equivalents of the two properties below are `internal`, so they are re-derived here
 * from the same extension URLs.
 */
private val QuestionnaireItemComponent.itemMediaAttachment: Attachment?
  get() =
    (getExtensionByUrl(EXTENSION_ITEM_MEDIA_URL)?.value as? Attachment)?.takeIf {
      it.hasContentType()
    }

private val QuestionnaireItemComponent.unitCode: String?
  get() =
    (extension.singleOrNull { it.url == EXTENSION_QUESTIONNAIRE_UNIT_URL }?.value as? Coding)?.code
