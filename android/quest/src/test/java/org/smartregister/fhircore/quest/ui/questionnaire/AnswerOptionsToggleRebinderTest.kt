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

import android.app.Activity
import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.google.android.fhir.datacapture.QuestionnaireViewHolderType
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Expression
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Questionnaire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.smartregister.fhircore.quest.robolectric.RobolectricTest
import org.smartregister.fhircore.quest.ui.questionnaire.AnswerOptionsToggleRebinder.ANSWER_OPTIONS_TOGGLE_EXPRESSION_URL
import org.smartregister.fhircore.quest.ui.questionnaire.AnswerOptionsToggleRebinder.usesAnswerOptionsToggleExpression

class AnswerOptionsToggleRebinderTest : RobolectricTest() {

  private val application = ApplicationProvider.getApplicationContext<Application>()

  // ---------------------------------------------------------------------------------------------
  // usesAnswerOptionsToggleExpression
  // ---------------------------------------------------------------------------------------------

  @Test
  fun testUsesAnswerOptionsToggleExpressionReturnsFalseWhenExtensionAbsent() {
    val questionnaire =
      Questionnaire().apply {
        addItem(
          Questionnaire.QuestionnaireItemComponent().apply {
            linkId = "demo_filter"
            type = Questionnaire.QuestionnaireItemType.BOOLEAN
            addExtension(
              Extension(
                "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-enableWhenExpression",
                Expression().apply { expression = "true" },
              ),
            )
          },
        )
      }

    assertFalse(questionnaire.usesAnswerOptionsToggleExpression())
  }

  @Test
  fun testUsesAnswerOptionsToggleExpressionFindsNestedItem() {
    val questionnaire =
      Questionnaire().apply {
        addItem(
          Questionnaire.QuestionnaireItemComponent().apply {
            linkId = "group"
            type = Questionnaire.QuestionnaireItemType.GROUP
            addItem(
              Questionnaire.QuestionnaireItemComponent().apply {
                linkId = "select_why"
                type = Questionnaire.QuestionnaireItemType.CHOICE
                addExtension(answerOptionsToggleExtension())
              },
            )
          },
        )
      }

    assertTrue(questionnaire.usesAnswerOptionsToggleExpression())
  }

  // ---------------------------------------------------------------------------------------------
  // isChoiceRow - decoding QuestionnaireEditAdapter's packed (type shl 24) or subtype view type
  // ---------------------------------------------------------------------------------------------

  @Test
  fun testIsChoiceRowAcceptsChoiceQuestionRows() {
    listOf(
        QuestionnaireViewHolderType.CHECK_BOX_GROUP,
        QuestionnaireViewHolderType.RADIO_GROUP,
        QuestionnaireViewHolderType.DROP_DOWN,
        QuestionnaireViewHolderType.DIALOG_SELECT,
        QuestionnaireViewHolderType.AUTO_COMPLETE,
      )
      .forEach { assertTrue("$it", AnswerOptionsToggleRebinder.isChoiceRow(it.value)) }
  }

  @Test
  fun testIsChoiceRowRejectsNonChoiceQuestionRows() {
    listOf(
        QuestionnaireViewHolderType.EDIT_TEXT_SINGLE_LINE,
        QuestionnaireViewHolderType.BOOLEAN_TYPE_PICKER,
        QuestionnaireViewHolderType.GROUP,
        QuestionnaireViewHolderType.DISPLAY,
      )
      .forEach { assertFalse("$it", AnswerOptionsToggleRebinder.isChoiceRow(it.value)) }
  }

  @Test
  fun testIsChoiceRowRejectsNonQuestionAdapterItems() {
    // Navigation rows are packed as Type.NAVIGATION (ordinal 3) with subtype 0xFFFFFF.
    assertFalse(AnswerOptionsToggleRebinder.isChoiceRow((3 shl 24) or 0xFFFFFF))
    // Repeated group header, Type ordinal 1, subtype 0 - would otherwise decode as GROUP.
    assertFalse(AnswerOptionsToggleRebinder.isChoiceRow(1 shl 24))
  }

  @Test
  fun testIsChoiceRowRejectsCustomWidgetSubtypesWithoutCrashing() {
    // OpenSRP custom widgets take subtypes at or above the canonical widget count. Indexing the
    // enum with those would throw, so they must be rejected by the bounds check.
    val firstCustomSubtype = QuestionnaireViewHolderType.values().size
    assertFalse(AnswerOptionsToggleRebinder.isChoiceRow(firstCustomSubtype))
    assertFalse(AnswerOptionsToggleRebinder.isChoiceRow(firstCustomSubtype + 4))
  }

  // ---------------------------------------------------------------------------------------------
  // attach
  // ---------------------------------------------------------------------------------------------

  @Test
  fun testAttachDoesNothingWhenQuestionnaireHasNoToggleExpression() {
    val fixture = fixture(QuestionnaireViewHolderType.CHECK_BOX_GROUP)

    AnswerOptionsToggleRebinder.attach(fixture.root, TestLifecycleOwner(), Questionnaire())

    assertEquals(emptyList<Int>(), fixture.rebindsAfterChangeAt(0))
  }

  @Test
  fun testAttachRebindsOnlyChoiceRowsAndDoesNotRecurse() {
    val fixture =
      fixture(
        QuestionnaireViewHolderType.BOOLEAN_TYPE_PICKER, // 0 - "adding Angry ?"
        QuestionnaireViewHolderType.CHECK_BOX_GROUP, // 1 - "Why ?"
        QuestionnaireViewHolderType.EDIT_TEXT_SINGLE_LINE, // 2
        QuestionnaireViewHolderType.DROP_DOWN, // 3
      )

    AnswerOptionsToggleRebinder.attach(
      fixture.root,
      TestLifecycleOwner(),
      questionnaireWithToggle(),
    )

    // Only the choice rows are re-bound, and the re-entrant notifications our own rebind triggers
    // do not schedule another pass.
    assertEquals(listOf(1, 3), fixture.rebindsAfterChangeAt(0))
  }

  @Test
  fun testAttachSkipsTheFocusedRow() {
    val fixture =
      fixture(
        QuestionnaireViewHolderType.AUTO_COMPLETE, // 0 - user is typing here
        QuestionnaireViewHolderType.CHECK_BOX_GROUP, // 1
      )

    AnswerOptionsToggleRebinder.attach(
      fixture.root,
      TestLifecycleOwner(),
      questionnaireWithToggle(),
    )

    fixture.showInActivity()
    val focusedRow = requireNotNull(fixture.recyclerView.getChildAt(0)) { "no rows laid out" }
    assertTrue("row 0 could not take focus", focusedRow.requestFocus())

    assertEquals(listOf(1), fixture.rebindsAfterChangeAt(1))
  }

  @Test
  fun testObserverIsUnregisteredWhenLifecycleOwnerIsDestroyed() {
    val fixture = fixture(QuestionnaireViewHolderType.CHECK_BOX_GROUP)
    val lifecycleOwner = TestLifecycleOwner()

    AnswerOptionsToggleRebinder.attach(fixture.root, lifecycleOwner, questionnaireWithToggle())
    lifecycleOwner.destroy()

    assertEquals(emptyList<Int>(), fixture.rebindsAfterChangeAt(0))
  }

  // ---------------------------------------------------------------------------------------------
  // helpers
  // ---------------------------------------------------------------------------------------------

  private fun answerOptionsToggleExtension() =
    Extension(ANSWER_OPTIONS_TOGGLE_EXPRESSION_URL).apply {
      addExtension(Extension("option", Coding().apply { code = "demo.angry" }))
      addExtension(
        Extension(
          "expression",
          Expression().apply {
            language = "text/fhirpath"
            expression =
              "(%resource.repeat(item).where(linkId='demo_filter').answer.where(\$this.exists()).value = true)"
          },
        ),
      )
    }

  private fun questionnaireWithToggle() =
    Questionnaire().apply {
      addItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          linkId = "select_why"
          type = Questionnaire.QuestionnaireItemType.CHOICE
          addExtension(answerOptionsToggleExtension())
        },
      )
    }

  private fun fixture(vararg viewHolderTypes: QuestionnaireViewHolderType) =
    Fixture(application, viewHolderTypes.toList())

  /**
   * Reproduces the view hierarchy the SDC fragment builds: a RecyclerView carrying the library's
   * own id, so [AnswerOptionsToggleRebinder.attach] can find it, with an adapter that reports the
   * same packed view types [com.google.android.fhir.datacapture.QuestionnaireEditAdapter] would.
   */
  private class Fixture(
    private val application: Application,
    viewHolderTypes: List<QuestionnaireViewHolderType>,
  ) {
    val recyclerView =
      RecyclerView(application).apply {
        id = com.google.android.fhir.datacapture.R.id.questionnaire_edit_recycler_view
        layoutManager = LinearLayoutManager(application)
        adapter = FakeAdapter(viewHolderTypes)
      }

    val root: View = FrameLayout(application).apply { addView(recyclerView) }

    private val adapter = recyclerView.adapter!!

    private val rebinds = mutableListOf<Int>()

    init {
      adapter.registerAdapterDataObserver(
        object : RecyclerView.AdapterDataObserver() {
          override fun onItemRangeChanged(positionStart: Int, itemCount: Int) {
            repeat(itemCount) { rebinds.add(positionStart + it) }
          }
        },
      )
    }

    fun showInActivity() {
      val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
      activity.setContentView(root)
      recyclerView.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
      )
      recyclerView.layout(0, 0, 400, 800)
    }

    /**
     * Dispatches the change notification DiffUtil would emit for the row the user answered, then
     * returns the positions the rebinder asked to re-bind on the following frame.
     */
    fun rebindsAfterChangeAt(position: Int): List<Int> {
      adapter.notifyItemChanged(position)
      // Drop the triggering notification itself; only the rebinder's posted pass is of interest.
      rebinds.clear()
      shadowOf(application.mainLooper).idle()
      return rebinds.toList()
    }
  }

  private class FakeAdapter(private val viewHolderTypes: List<QuestionnaireViewHolderType>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount() = viewHolderTypes.size

    // Question rows carry Type.QUESTION (ordinal 0) in the high byte, so the packed view type is
    // just the QuestionnaireViewHolderType value.
    override fun getItemViewType(position: Int) = viewHolderTypes[position].value

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
      object :
        RecyclerView.ViewHolder(
          View(parent.context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 100)
          },
        ) {}

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
  }

  private class TestLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    init {
      registry.currentState = Lifecycle.State.RESUMED
    }

    override val lifecycle: Lifecycle
      get() = registry

    fun destroy() {
      registry.currentState = Lifecycle.State.DESTROYED
    }
  }
}
