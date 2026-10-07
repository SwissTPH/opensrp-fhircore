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

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.RecyclerView
import com.google.android.fhir.datacapture.QuestionnaireViewHolderType
import org.hl7.fhir.r4.model.Questionnaire

/**
 * Forces the questionnaire RecyclerView to re-bind choice rows whenever anything in the
 * questionnaire changes, so that `sdc-questionnaire-answerOptionsToggleExpression` results are
 * actually painted.
 *
 * Why this exists: in data-capture `1.3.0-preview10-SNAPSHOT` the toggle expression *is*
 * re-evaluated on every answer change (`QuestionnaireViewModel` rebuilds every
 * `QuestionnaireViewItem` with a fresh `enabledAnswerOptions`), but `QuestionnaireEditAdapter`'s
 * `DiffCallbacks.QUESTIONS.areContentsTheSame` only compares item identity, response and validation
 * result. It never compares `enabledAnswerOptions`, so DiffUtil reports "contents unchanged", no
 * `onBindViewHolder` runs, and the widget keeps painting the stale option list. Flipping the item's
 * `enableWhenExpression` appears to "fix" it only because that removes and re-inserts the row.
 *
 * The adapter is a `ListAdapter` and `submitList` always swaps its backing list, so it already
 * holds the correct, fresh view item — a plain `notifyItemChanged(position)` is enough to paint it.
 *
 * This is a workaround. Delete it once the SDK compares `enabledAnswerOptions` in
 * `areContentsTheSame` and the `data-capture` dependency is bumped past that fix.
 */
object AnswerOptionsToggleRebinder {

  @VisibleForTesting
  internal const val ANSWER_OPTIONS_TOGGLE_EXPRESSION_URL =
    "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-answerOptionsToggleExpression"

  /**
   * Widget types whose rendered content is derived from
   * [com.google.android.fhir.datacapture.views.QuestionnaireViewItem.enabledAnswerOptions], and are
   * therefore the only rows an answer-options toggle can affect.
   */
  private val CHOICE_VIEW_HOLDER_TYPES =
    setOf(
      QuestionnaireViewHolderType.RADIO_GROUP,
      QuestionnaireViewHolderType.CHECK_BOX_GROUP,
      QuestionnaireViewHolderType.DROP_DOWN,
      QuestionnaireViewHolderType.DIALOG_SELECT,
      QuestionnaireViewHolderType.AUTO_COMPLETE,
    )

  /**
   * `QuestionnaireEditAdapter.getItemViewType` packs `(Type.ordinal shl 24) or subtype` into a
   * single int. `Type.QUESTION` is the first constant, so question rows carry `0` in the high byte.
   */
  private const val VIEW_TYPE_SUBTYPE_MASK = 0xFFFFFF

  private const val VIEW_TYPE_TYPE_SHIFT = 24

  private const val VIEW_TYPE_QUESTION = 0

  /**
   * Attaches the rebinder to the questionnaire fragment's [View], if [questionnaire] actually uses
   * answer-options toggle expressions. Questionnaires that do not are left completely untouched.
   *
   * The observer is unregistered when [lifecycleOwner] is destroyed. Pass the fragment's
   * `viewLifecycleOwner` so it is torn down together with the view it observes.
   */
  fun attach(fragmentView: View, lifecycleOwner: LifecycleOwner, questionnaire: Questionnaire) {
    if (!questionnaire.usesAnswerOptionsToggleExpression()) return

    val recyclerView =
      fragmentView.findViewById<RecyclerView>(
        com.google.android.fhir.datacapture.R.id.questionnaire_edit_recycler_view,
      ) ?: return
    val adapter = recyclerView.adapter ?: return

    // Not RecyclerView.post: View.post silently defers until the view is attached to a window, so
    // a nudge scheduled while detached would be dropped until re-attachment.
    val handler = Handler(Looper.getMainLooper())
    var nudgeScheduled = false

    fun rebindChoiceRows() {
      // Calling notify* while the RecyclerView is laying out throws; try again on the next frame.
      if (recyclerView.isComputingLayout) {
        handler.post { rebindChoiceRows() }
        return
      }
      // Leave the row the user is currently interacting with alone, so re-binding never steals
      // focus or clobbers in-progress input in an autocomplete or drop-down.
      val focusedPosition =
        recyclerView.focusedChild?.let { recyclerView.getChildAdapterPosition(it) }
          ?: RecyclerView.NO_POSITION
      for (position in 0 until adapter.itemCount) {
        if (position == focusedPosition) continue
        if (!isChoiceRow(adapter.getItemViewType(position))) continue
        adapter.notifyItemChanged(position)
      }
      nudgeScheduled = false
    }

    fun scheduleNudge() {
      // Our own notifyItemChanged calls re-enter this observer; the flag keeps that from looping.
      if (nudgeScheduled) return
      nudgeScheduled = true
      handler.post { rebindChoiceRows() }
    }

    val observer =
      object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = scheduleNudge()

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = scheduleNudge()

        override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) =
          scheduleNudge()

        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = scheduleNudge()

        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = scheduleNudge()

        override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
          scheduleNudge()
      }

    adapter.registerAdapterDataObserver(observer)
    lifecycleOwner.lifecycle.addObserver(
      object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
          handler.removeCallbacksAndMessages(null)
          runCatching { adapter.unregisterAdapterDataObserver(observer) }
          owner.lifecycle.removeObserver(this)
        }
      },
    )
  }

  /**
   * Returns whether the packed `viewType` identifies a question row rendered by one of the
   * [CHOICE_VIEW_HOLDER_TYPES] canonical widgets.
   *
   * Custom widgets registered through
   * [QuestionnaireItemViewHolderFactoryMatchersProviderFactoryImpl] take subtypes at or above the
   * number of canonical widgets, hence the bounds check before
   * [QuestionnaireViewHolderType.fromInt], which indexes straight into the enum values.
   */
  @VisibleForTesting
  internal fun isChoiceRow(viewType: Int): Boolean {
    if (viewType ushr VIEW_TYPE_TYPE_SHIFT != VIEW_TYPE_QUESTION) return false
    val subtype = viewType and VIEW_TYPE_SUBTYPE_MASK
    if (subtype >= QuestionnaireViewHolderType.values().size) return false
    return QuestionnaireViewHolderType.fromInt(subtype) in CHOICE_VIEW_HOLDER_TYPES
  }

  /**
   * Returns whether any item in the questionnaire, at any depth, carries an answer-options toggle
   * expression. The SDK's own accessor for this extension is `internal`, so the extension URL is
   * matched directly.
   */
  @VisibleForTesting
  internal fun Questionnaire.usesAnswerOptionsToggleExpression(): Boolean =
    item.anyItem { it.extension.any { ext -> ext.url == ANSWER_OPTIONS_TOGGLE_EXPRESSION_URL } }

  private fun List<Questionnaire.QuestionnaireItemComponent>.anyItem(
    predicate: (Questionnaire.QuestionnaireItemComponent) -> Boolean,
  ): Boolean = any { predicate(it) || it.item.anyItem(predicate) }
}
