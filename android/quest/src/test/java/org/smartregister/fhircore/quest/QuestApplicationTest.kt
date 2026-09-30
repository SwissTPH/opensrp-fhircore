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

import android.content.Intent
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.verify
import io.sentry.Sentry
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import kotlin.test.assertNotNull
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.smartregister.fhircore.quest.robolectric.RobolectricTest
import org.smartregister.fhircore.quest.ui.appsetting.AppSettingActivity

@HiltAndroidTest
class QuestApplicationTest : RobolectricTest() {

  @get:Rule(order = 0) var hiltRule = HiltAndroidRule(this)
  private lateinit var application: QuestApplication

  @Before
  fun setUp() {
    hiltRule.inject()
    application = QuestApplication()
    application.referenceUrlResolver = mockk()
    application.xFhirQueryResolver = mockk()
  }

  @Test
  fun testSentryMonitoringWhenDsnNotBlank() {
    val sentryDsn = "https://abc123@sentry.example.org/logs/1"
    val spyApp = spyk(application)
    val sentryOptions = spyk<SentryAndroidOptions>()

    mockkStatic(SentryAndroid::class) {
      every {
        SentryAndroid.init(any(), any<Sentry.OptionsConfiguration<SentryAndroidOptions>>())
      } answers
        {
          val optionsConfiguration = secondArg<Sentry.OptionsConfiguration<SentryAndroidOptions>>()
          optionsConfiguration.configure(sentryOptions)
        }

      spyApp.initSentryMonitoring(dsn = sentryDsn)

      Assert.assertEquals(sentryDsn, sentryOptions.dsn)
      Assert.assertEquals(1.0, sentryOptions.tracesSampleRate)
      Assert.assertTrue(sentryOptions.isEnableUserInteractionTracing)
      Assert.assertTrue(sentryOptions.isEnableUserInteractionBreadcrumbs)
      Assert.assertTrue(sentryOptions.isAttachScreenshot)
      Assert.assertTrue(sentryOptions.isAttachViewHierarchy)
      Assert.assertTrue(sentryOptions.isAnrEnabled)
      Assert.assertTrue(sentryOptions.isEnableAutoSessionTracking)
    }
  }

  @Test
  fun testSentryMonitoringSkippedWhenDsnIsGradlePlaceholder() {
    val spyApp = spyk(application)

    mockkStatic(SentryAndroid::class) {
      every {
        SentryAndroid.init(any(), any<Sentry.OptionsConfiguration<SentryAndroidOptions>>())
      } returns Unit

      // project-properties.gradle.kts substitutes this when local.properties has no SENTRY_DSN;
      // initialising with it would throw IllegalArgumentException and crash the app at startup.
      spyApp.initSentryMonitoring(dsn = "sample_SENTRY_DSN")

      verify(exactly = 0) {
        SentryAndroid.init(any(), any<Sentry.OptionsConfiguration<SentryAndroidOptions>>())
      }
    }
  }

  @Test
  fun testSentryMonitoringSkippedWhenDsnIsBlank() {
    val spyApp = spyk(application)

    mockkStatic(SentryAndroid::class) {
      every {
        SentryAndroid.init(any(), any<Sentry.OptionsConfiguration<SentryAndroidOptions>>())
      } returns Unit

      spyApp.initSentryMonitoring(dsn = "   ")

      verify(exactly = 0) {
        SentryAndroid.init(any(), any<Sentry.OptionsConfiguration<SentryAndroidOptions>>())
      }
    }
  }

  @Test
  fun testGetDataCaptureConfig() {
    val config = application.getDataCaptureConfig()

    Assert.assertNotNull(config)
  }

  @Test
  fun testGetDataCaptureConfigWhenAlreadySet() {
    val config = application.getDataCaptureConfig()
    val config2 = application.getDataCaptureConfig()

    Assert.assertEquals(config, config2)
  }

  @Test
  fun testGetWorkManagerConfiguration() {
    val config = application.workManagerConfiguration

    Assert.assertNotNull(config)
  }

  @Test
  fun testOnCreate() {
    hiltRule.inject()
    application.onCreate()

    val intent = Intent(application, AppSettingActivity::class.java)
    application.startActivity(intent)
    assertNotNull(application.referenceUrlResolver)
    assertNotNull(application.xFhirQueryResolver)
  }
}
