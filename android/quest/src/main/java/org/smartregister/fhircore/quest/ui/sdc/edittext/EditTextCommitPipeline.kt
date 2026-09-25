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

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce

/**
 * How long a field waits for typing to stop before the value is handed to the questionnaire view
 * model (which is what re-evaluates `calculatedExpression`, `enableWhen` and validation).
 *
 * Short enough that a calculated answer such as BMI visibly tracks what is being typed, long enough
 * that a FHIRPath pass is not run on every keystroke. Blur, `Done`/`Next` and view-holder recycling
 * all flush immediately, so this window only decides *when* an answer is saved, never *whether*.
 */
const val EDIT_TEXT_COMMIT_DEBOUNCE_MILLIS = 300L

/**
 * Owns the text of a single questionnaire edit-text question and decides when that text is handed
 * to the questionnaire view model.
 *
 * This exists because the SDK's equivalent (the private `EditTextFieldState` inside
 * `com.google.android.fhir.datacapture.views.compose.EditTextFieldItem`) loses keystrokes:
 * * it drops the first emission of its `snapshotFlow` by position (`drop(1)`), assuming that
 *   emission is always the initial value. The collector is launched into a dispatcher, so a
 *   keystroke can land before collection starts - and then it is the first emission, and it is the
 *   one that gets dropped.
 * * it is `remember`ed on the `QuestionnaireViewItem`, which changes as soon as the answer does, so
 *   every committed keystroke tears the pipeline down and builds a new one - constantly re-opening
 *   the window above.
 *
 * Typing `180` into a height question that feeds a BMI `calculatedExpression` therefore calculates
 * against `18`, and the real value only arrives when the field loses focus.
 *
 * This class never drops by position: [commit] compares against what was last handed over, so a
 * value that arrives before collection starts is still committed, and a duplicate is not.
 */
@Stable
internal class EditTextCommitPipeline(initialText: String) {

  /** The text currently in the field. Written by the text field on every keystroke. */
  var fieldValue by mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length)))

  /** Whether the field currently holds focus. Drives whether model updates may be adopted. */
  var isFocused by mutableStateOf(false)

  /** The last value handed to the view model. */
  var committedText: String = initialText
    private set

  /** True when the field holds an edit that the view model has not been told about yet. */
  fun hasPendingEdit(): Boolean = fieldValue.text != committedText

  /**
   * Commits typed text once typing pauses for [debounceMillis]. Suspends until cancelled.
   *
   * [onCommit] may be cancelled when a newer value supersedes it; [committedText] is only advanced
   * once it has completed, so a cancelled commit is always followed by the newer one rather than
   * being silently forgotten.
   */
  @OptIn(FlowPreview::class)
  suspend fun collectAndCommit(
    debounceMillis: Long = EDIT_TEXT_COMMIT_DEBOUNCE_MILLIS,
    onCommit: suspend (String) -> Unit,
  ) {
    snapshotFlow { fieldValue.text }.debounce(debounceMillis).collectLatest { commit(it, onCommit) }
  }

  /** Commits the current text immediately, without waiting out the debounce window. */
  suspend fun flush(onCommit: suspend (String) -> Unit) = commit(fieldValue.text, onCommit)

  /**
   * Replaces the field text with [modelText] when the view model's value has moved on underneath
   * us - a populated answer, a value normalised on commit (`180` -> `180.0`), or a view holder
   * recycled onto another question.
   *
   * Refuses to do so while the field is focused or while an edit is still pending, so this can
   * never overwrite what is being typed. Returns true if the field was updated.
   */
  fun adoptModelText(modelText: String): Boolean {
    if (isFocused || hasPendingEdit() || modelText == fieldValue.text) return false
    fieldValue = TextFieldValue(modelText, TextRange(modelText.length))
    committedText = modelText
    return true
  }

  private suspend fun commit(text: String, onCommit: suspend (String) -> Unit) {
    if (text == committedText) return
    onCommit(text)
    committedText = text
  }
}
