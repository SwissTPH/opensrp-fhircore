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

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.verify
import io.sentry.Breadcrumb
import io.sentry.IScope
import io.sentry.ScopeCallback
import io.sentry.Sentry
import io.sentry.SentryLevel
import org.junit.Assert
import org.junit.Test
import org.smartregister.fhircore.quest.robolectric.RobolectricTest

class ReleaseTreeTest : RobolectricTest() {

  private val releaseTree = ReleaseTree()

  @Test
  fun testIsLoggableDropsVerboseAndDebugOnly() {
    Assert.assertFalse(releaseTree.isLoggable(TAG, Log.VERBOSE))
    Assert.assertFalse(releaseTree.isLoggable(TAG, Log.DEBUG))
    Assert.assertTrue(releaseTree.isLoggable(TAG, Log.INFO))
    Assert.assertTrue(releaseTree.isLoggable(TAG, Log.WARN))
    Assert.assertTrue(releaseTree.isLoggable(TAG, Log.ERROR))
    Assert.assertTrue(releaseTree.isLoggable(TAG, Log.ASSERT))
  }

  @Test
  fun testErrorWithThrowableIsCapturedWithLogMessage() {
    val throwable = IllegalStateException("boom")
    val scope = mockk<IScope>(relaxed = true)
    val callbackSlot = slot<ScopeCallback>()

    mockkStatic(Sentry::class) {
      every { Sentry.captureException(throwable, capture(callbackSlot)) } returns mockk()

      releaseTree.log(Log.ERROR, TAG, MESSAGE, throwable)
      callbackSlot.captured.run(scope)

      verify { Sentry.captureException(throwable, any<ScopeCallback>()) }
      verify { scope.level = SentryLevel.ERROR }
      verify { scope.setExtra("log_message", MESSAGE) }
      verify { scope.setTag("logger", TAG) }
    }
  }

  @Test
  fun testErrorWithoutThrowableIsCapturedAsMessage() {
    val scope = mockk<IScope>(relaxed = true)
    val callbackSlot = slot<ScopeCallback>()

    mockkStatic(Sentry::class) {
      every { Sentry.captureMessage(MESSAGE, capture(callbackSlot)) } returns mockk()

      releaseTree.log(Log.ERROR, TAG, MESSAGE, null)
      callbackSlot.captured.run(scope)

      verify { Sentry.captureMessage(MESSAGE, any<ScopeCallback>()) }
      verify { scope.level = SentryLevel.ERROR }
    }
  }

  @Test
  fun testWarnIsCapturedAtWarningLevel() {
    val scope = mockk<IScope>(relaxed = true)
    val callbackSlot = slot<ScopeCallback>()

    mockkStatic(Sentry::class) {
      every { Sentry.captureMessage(MESSAGE, capture(callbackSlot)) } returns mockk()

      releaseTree.log(Log.WARN, TAG, MESSAGE, null)
      callbackSlot.captured.run(scope)

      verify { scope.level = SentryLevel.WARNING }
    }
  }

  @Test
  fun testInfoBecomesBreadcrumbRatherThanEvent() {
    val breadcrumbSlot = slot<Breadcrumb>()

    mockkStatic(Sentry::class) {
      every { Sentry.addBreadcrumb(capture(breadcrumbSlot)) } returns Unit

      releaseTree.log(Log.INFO, TAG, MESSAGE, null)

      Assert.assertEquals(MESSAGE, breadcrumbSlot.captured.message)
      Assert.assertEquals(TAG, breadcrumbSlot.captured.category)
      Assert.assertEquals(SentryLevel.INFO, breadcrumbSlot.captured.level)
      verify(exactly = 0) { Sentry.captureMessage(any<String>(), any<ScopeCallback>()) }
      verify(exactly = 0) { Sentry.captureException(any(), any<ScopeCallback>()) }
    }
  }

  companion object {
    private const val TAG = "SomeTag"
    private const val MESSAGE = "Failed to render questionnaire sample-questionnaire"
  }
}
