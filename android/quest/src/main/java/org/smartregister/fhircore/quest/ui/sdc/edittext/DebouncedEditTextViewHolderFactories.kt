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

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.extensions.getValidationErrorMessage
import com.google.android.fhir.datacapture.extensions.itemControl
import com.google.android.fhir.datacapture.extensions.shouldUseDialog
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemComposeViewHolderDelegate
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemComposeViewHolderFactory
import java.text.DecimalFormat
import java.util.Locale
import org.hl7.fhir.r4.model.DecimalType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemComponent
import org.hl7.fhir.r4.model.Questionnaire.QuestionnaireItemType
import org.hl7.fhir.r4.model.QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent
import org.hl7.fhir.r4.model.StringType

/** See http://hl7.org/fhir/uv/sdc/StructureDefinition-sdc-questionnaire-answerExpression.html. */
private const val EXTENSION_ANSWER_EXPRESSION_URL =
  "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-answerExpression"

/**
 * Custom view holder factories that replace the SDK's own edit-text widgets with ones that do not
 * lose keystrokes while an answer is being committed. See [DebouncedEditTextField] for what the SDK
 * widgets get wrong and why it is visible as an incorrect `calculatedExpression` result.
 *
 * Everything other than the text field itself - the answer types, the draft-answer handling, the
 * validation messages, the keyboard options - is kept identical to the SDK factories these stand in
 * for, so that only the input pipeline changes.
 *
 * These are registered last in
 * [ org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireItemViewHolderFactoryMatchersProviderFactoryImpl],
 * so the OpenSRP widgets (password, barcode, QR code, location) keep priority over them.
 */
object DebouncedEditTextViewHolderFactories {

  /**
   * True for questions the SDK would render with a plain edit text.
   *
   * Custom matchers are consulted before the SDK looks at answer options or item controls, so this
   * has to exclude by hand everything that should render as a dropdown, radio group, dialog,
   * autocomplete, slider or phone number field. Anything not matched here is left to the SDK.
   */
  private fun QuestionnaireItemComponent.isPlainEditText(): Boolean =
    itemControl == null &&
      !shouldUseDialog &&
      answerOption.isEmpty() &&
      !hasAnswerValueSet() &&
      getExtensionByUrl(EXTENSION_ANSWER_EXPRESSION_URL) == null

  /** Stands in for `EditTextDecimalViewHolderFactory`. */
  object Decimal : QuestionnaireItemComposeViewHolderFactory {

    fun matcher(questionnaireItem: QuestionnaireItemComponent) =
      questionnaireItem.type == QuestionnaireItemType.DECIMAL && questionnaireItem.isPlainEditText()

    override fun getQuestionnaireItemViewHolderDelegate():
      QuestionnaireItemComposeViewHolderDelegate =
      DebouncedEditTextViewHolderDelegate(
        KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        uiInputText = {
          val answer = it.answers.singleOrNull()?.valueDecimalType?.value?.toString()
          val draftAnswer = it.draftAnswer?.toString()
          when {
            answer.isNullOrEmpty() && draftAnswer.isNullOrEmpty() -> ""
            answer?.toDoubleOrNull() != null -> answer
            else -> draftAnswer
          }
        },
        uiValidationMessage = { questionnaireViewItem, context ->
          if (questionnaireViewItem.draftAnswer != null) {
            context.getString(R.string.decimal_format_validation_error_msg)
          } else {
            getValidationErrorMessage(
              context,
              questionnaireViewItem,
              questionnaireViewItem.validationResult,
            )
          }
        },
        handleInput = { inputText, questionnaireViewItem ->
          // Unlike the SDK factory, an emptied field clears the answer instead of being recorded as
          // an unparseable draft (which leaves a "not a valid decimal" error on a blank field).
          // This matches what the SDK's own integer factory does.
          when {
            inputText.isEmpty() -> questionnaireViewItem.clearAnswer()
            inputText.toDoubleOrNull() != null ->
              questionnaireViewItem.setAnswer(
                QuestionnaireResponseItemAnswerComponent()
                  .setValue(DecimalType(inputText.toDouble().toString())),
              )
            else -> questionnaireViewItem.setDraftAnswer(inputText)
          }
        },
      )
  }

  /** Stands in for `EditTextIntegerViewHolderFactory`. */
  object Integer : QuestionnaireItemComposeViewHolderFactory {

    fun matcher(questionnaireItem: QuestionnaireItemComponent) =
      questionnaireItem.type == QuestionnaireItemType.INTEGER && questionnaireItem.isPlainEditText()

    override fun getQuestionnaireItemViewHolderDelegate():
      QuestionnaireItemComposeViewHolderDelegate =
      DebouncedEditTextViewHolderDelegate(
        KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        uiInputText = {
          val answer = it.answers.singleOrNull()?.valueIntegerType?.value?.toString()
          val draftAnswer = it.draftAnswer?.toString()
          when {
            answer.isNullOrEmpty() && draftAnswer.isNullOrEmpty() -> ""
            answer?.toIntOrNull() != null -> answer
            else -> draftAnswer
          }
        },
        uiValidationMessage = { questionnaireViewItem, context ->
          if (questionnaireViewItem.draftAnswer != null) {
            context.getString(
              R.string.integer_format_validation_error_msg,
              formatInteger(Int.MIN_VALUE),
              formatInteger(Int.MAX_VALUE),
            )
          } else {
            getValidationErrorMessage(
              context,
              questionnaireViewItem,
              questionnaireViewItem.validationResult,
            )
          }
        },
        handleInput = { inputText, questionnaireViewItem ->
          when {
            inputText.isEmpty() -> questionnaireViewItem.clearAnswer()
            inputText.toIntOrNull() != null ->
              questionnaireViewItem.setAnswer(
                QuestionnaireResponseItemAnswerComponent().setValue(IntegerType(inputText)),
              )
            else -> questionnaireViewItem.setDraftAnswer(inputText)
          }
        },
      )

    private fun formatInteger(value: Int): String =
      DecimalFormat.getInstance(Locale.getDefault()).format(value)
  }

  /** Stands in for `EditTextSingleLineViewHolderFactory`. */
  object SingleLineString : QuestionnaireItemComposeViewHolderFactory {

    fun matcher(questionnaireItem: QuestionnaireItemComponent) =
      questionnaireItem.type == QuestionnaireItemType.STRING && questionnaireItem.isPlainEditText()

    override fun getQuestionnaireItemViewHolderDelegate():
      QuestionnaireItemComposeViewHolderDelegate = stringDelegate(isMultiLine = false)
  }

  /** Stands in for `EditTextMultiLineViewHolderFactory`. */
  object MultiLineText : QuestionnaireItemComposeViewHolderFactory {

    fun matcher(questionnaireItem: QuestionnaireItemComponent) =
      questionnaireItem.type == QuestionnaireItemType.TEXT && questionnaireItem.isPlainEditText()

    override fun getQuestionnaireItemViewHolderDelegate():
      QuestionnaireItemComposeViewHolderDelegate = stringDelegate(isMultiLine = true)
  }

  private fun stringDelegate(isMultiLine: Boolean) =
    DebouncedEditTextViewHolderDelegate(
      KeyboardOptions(
        keyboardType = KeyboardType.Text,
        capitalization = KeyboardCapitalization.Sentences,
        imeAction = ImeAction.Done,
      ),
      uiInputText = { it.answers.singleOrNull()?.valueStringType?.value ?: "" },
      uiValidationMessage = { questionnaireViewItem, context ->
        getValidationErrorMessage(
          context,
          questionnaireViewItem,
          questionnaireViewItem.validationResult,
        )
      },
      handleInput = { inputText, questionnaireViewItem ->
        if (inputText.isEmpty()) {
          questionnaireViewItem.clearAnswer()
        } else {
          questionnaireViewItem.setAnswer(
            QuestionnaireResponseItemAnswerComponent().setValue(StringType(inputText)),
          )
        }
      },
      isMultiLine = isMultiLine,
    )
}
