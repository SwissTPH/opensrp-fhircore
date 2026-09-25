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

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EditTextCommitPipelineTest {

  private val committed = mutableListOf<String>()

  private fun EditTextCommitPipeline.type(text: String) {
    fieldValue = TextFieldValue(text)
    // Outside a composition nothing applies the snapshot for us, so snapshotFlow has to be told.
    Snapshot.sendApplyNotifications()
  }

  /**
   * The regression this whole class exists for: the SDK field drops its flow's first emission by
   * position, so a keystroke that lands before the collector is dispatched is lost until the field
   * loses focus. Typing `180` into a height question then calculates BMI from `18`.
   */
  @Test
  fun `commits a keystroke that lands before collection starts`() = runTest {
    val pipeline = EditTextCommitPipeline("18")
    pipeline.type("180")

    val job = launch { pipeline.collectAndCommit(DEBOUNCE) { committed += it } }
    advanceTimeBy(DEBOUNCE + 1)
    runCurrent()

    assertEquals(listOf("180"), committed)
    assertEquals("180", pipeline.committedText)
    job.cancelAndJoin()
  }

  @Test
  fun `does not commit the initial value`() = runTest {
    val pipeline = EditTextCommitPipeline("18")

    val job = launch { pipeline.collectAndCommit(DEBOUNCE) { committed += it } }
    advanceTimeBy(DEBOUNCE + 1)
    runCurrent()

    assertEquals(emptyList<String>(), committed)
    job.cancelAndJoin()
  }

  @Test
  fun `commits only once typing pauses`() = runTest {
    val pipeline = EditTextCommitPipeline("")
    val job = launch { pipeline.collectAndCommit(DEBOUNCE) { committed += it } }
    runCurrent()

    pipeline.type("1")
    advanceTimeBy(DEBOUNCE / 2)
    pipeline.type("18")
    advanceTimeBy(DEBOUNCE / 2)
    pipeline.type("180")
    runCurrent()

    assertEquals(emptyList<String>(), committed)

    advanceTimeBy(DEBOUNCE + 1)
    runCurrent()

    assertEquals(listOf("180"), committed)
    job.cancelAndJoin()
  }

  @Test
  fun `flush commits a pending edit without waiting out the debounce window`() = runTest {
    val pipeline = EditTextCommitPipeline("")
    val job = launch { pipeline.collectAndCommit(DEBOUNCE) { committed += it } }
    runCurrent()

    pipeline.type("180")
    assertTrue(pipeline.hasPendingEdit())

    pipeline.flush { committed += it }

    assertEquals(listOf("180"), committed)
    assertFalse(pipeline.hasPendingEdit())

    // The debounced collector must not then commit the same value a second time.
    advanceTimeBy(DEBOUNCE + 1)
    runCurrent()
    assertEquals(listOf("180"), committed)
    job.cancelAndJoin()
  }

  @Test
  fun `adopts a value the view model normalised on commit`() {
    val pipeline = EditTextCommitPipeline("")
    pipeline.type("180")
    runBlockingCommit(pipeline, "180")

    assertTrue(pipeline.adoptModelText("180.0"))
    assertEquals("180.0", pipeline.fieldValue.text)
    assertEquals("180.0", pipeline.committedText)
  }

  @Test
  fun `refuses to overwrite text that is still being typed`() {
    val pipeline = EditTextCommitPipeline("")
    pipeline.type("180")

    assertFalse(pipeline.adoptModelText("55"))
    assertEquals("180", pipeline.fieldValue.text)
  }

  @Test
  fun `refuses to overwrite a focused field`() {
    val pipeline = EditTextCommitPipeline("55")
    pipeline.isFocused = true

    assertFalse(pipeline.adoptModelText("180"))
    assertEquals("55", pipeline.fieldValue.text)
  }

  private fun runBlockingCommit(pipeline: EditTextCommitPipeline, expected: String) = runTest {
    pipeline.flush { assertEquals(expected, it) }
  }

  private companion object {
    const val DEBOUNCE = 300L
  }
}
