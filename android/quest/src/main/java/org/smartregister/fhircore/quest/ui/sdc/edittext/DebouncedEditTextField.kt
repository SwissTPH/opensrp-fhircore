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

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.datacapture.R
import com.google.android.fhir.datacapture.extensions.tryUnwrapContext
import com.google.android.fhir.datacapture.views.compose.EDIT_TEXT_FIELD_TEST_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A questionnaire text field that hands what is typed to the view model as typing happens, without
 * waiting for the field to lose focus and without losing the keystrokes that land while an answer
 * is being committed. See [EditTextCommitPipeline] for what the SDK's own field gets wrong.
 *
 * Visually identical to the SDK's `EditTextFieldItem`, and it keeps the same test tag, so anything
 * driving the field in a UI test is unaffected.
 *
 * @param stateKey stable identity of the question instance this field is bound to. All state is
 *   keyed on it, so it must change when - and only when - the view holder is bound to a different
 *   question.
 * @param externalText the authoritative text for the current answer, as held by the view model.
 * @param onCommit hands a new value to the view model. Suspends, and may be cancelled if a newer
 *   value supersedes it.
 */
@Composable
internal fun DebouncedEditTextField(
  modifier: Modifier,
  stateKey: String,
  externalText: String,
  hint: AnnotatedString?,
  helperText: String?,
  isError: Boolean,
  isReadOnly: Boolean,
  keyboardOptions: KeyboardOptions,
  isMultiLine: Boolean,
  onCommit: suspend (String) -> Unit,
) {
  val focusManager = LocalFocusManager.current
  val keyboardController = LocalSoftwareKeyboardController.current
  val currentOnCommit = rememberUpdatedState(onCommit)

  // Outlives this composable, unlike rememberCoroutineScope, so a pending edit can still be flushed
  // while the view holder is being recycled away.
  val hostScope: CoroutineScope? = LocalContext.current.tryUnwrapContext()?.lifecycleScope

  // Keyed on the question, not on the QuestionnaireViewItem: the view item is replaced every time
  // an answer changes, and re-creating this state mid-typing is what loses keystrokes.
  val pipeline = remember(stateKey) { EditTextCommitPipeline(externalText) }

  val flushPendingEdit: () -> Unit = {
    if (pipeline.hasPendingEdit()) {
      hostScope?.launch { pipeline.flush { currentOnCommit.value(it) } }
    }
  }

  LaunchedEffect(stateKey) { pipeline.collectAndCommit { currentOnCommit.value(it) } }

  LaunchedEffect(stateKey, externalText, pipeline.isFocused) {
    pipeline.adoptModelText(externalText)
  }

  DisposableEffect(stateKey) { onDispose { flushPendingEdit() } }

  OutlinedTextField(
    value = pipeline.fieldValue,
    onValueChange = { pipeline.fieldValue = it },
    minLines = if (isMultiLine) 3 else 1,
    singleLine = !isMultiLine,
    modifier =
      modifier
        .onFocusChanged { focusState ->
          pipeline.isFocused = focusState.isFocused
          if (!focusState.isFocused) {
            keyboardController?.hide()
            flushPendingEdit()
          }
        }
        .testTag(EDIT_TEXT_FIELD_TEST_TAG),
    label = { hint?.let { Text(it) } },
    supportingText = { helperText?.let { Text(it) } },
    isError = isError,
    colors = OutlinedTextFieldDefaults.colors(),
    trailingIcon = {
      if (isError) {
        Icon(painter = painterResource(R.drawable.error_24px), contentDescription = "Error")
      }
    },
    readOnly = isReadOnly,
    enabled = !isReadOnly,
    keyboardOptions = keyboardOptions,
    keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
  )
}
