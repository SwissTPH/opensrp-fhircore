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

package org.smartregister.fhircore.engine.util

import com.google.android.fhir.FhirEngine
import com.google.android.fhir.db.ResourceNotFoundException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.Binary
import org.hl7.fhir.r4.model.Extension
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.ResourceType
import org.hl7.fhir.r4.model.StringType
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.smartregister.fhircore.engine.configuration.app.AuthConfiguration
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.util.QuestionnaireMediaResolver.Companion.EXTENSION_ITEM_ANSWER_MEDIA
import org.smartregister.fhircore.engine.util.QuestionnaireMediaResolver.Companion.EXTENSION_ITEM_MEDIA

class QuestionnaireMediaResolverTest {

  private val fhirEngine: FhirEngine = mockk()
  private val configService: ConfigService = mockk()
  private lateinit var questionnaireMediaResolver: QuestionnaireMediaResolver

  private val imageBytes = byteArrayOf(1, 2, 3, 4)

  @Before
  fun setUp() {
    every { configService.provideAuthConfiguration() } returns
      AuthConfiguration(
        oauthServerBaseUrl = "https://example.org/auth/",
        fhirServerBaseUrl = "https://example.org/fhir/",
        clientId = "test-client",
        accountType = "org.smartregister.test",
      )
    questionnaireMediaResolver = QuestionnaireMediaResolver(fhirEngine, configService)
  }

  @Test
  fun `resolveMediaAttachments should point item media at an absolute Binary url`() = runBlocking {
    mockBinary(binaryId = BINARY_ID, contentType = "image/png", data = imageBytes)
    val questionnaire = questionnaireWithItemMedia(attachment(url = "Binary/$BINARY_ID"))

    questionnaireMediaResolver.resolveMediaAttachments(questionnaire)

    val attachment = questionnaire.itemMediaAttachment()
    Assert.assertEquals("https://example.org/fhir/Binary/$BINARY_ID", attachment.url)
    Assert.assertEquals("image/png", attachment.contentType)
  }

  @Test
  fun `resolveMediaAttachments should keep inline item media data when present`() = runBlocking {
    mockBinary(binaryId = BINARY_ID, contentType = "image/png", data = imageBytes)
    val questionnaire =
      questionnaireWithItemMedia(
        attachment(url = "Binary/$BINARY_ID", contentType = "image/png", data = imageBytes),
      )

    questionnaireMediaResolver.resolveMediaAttachments(questionnaire)

    val attachment = questionnaire.itemMediaAttachment()
    Assert.assertArrayEquals(imageBytes, attachment.data)
    Assert.assertEquals("https://example.org/fhir/Binary/$BINARY_ID", attachment.url)
  }

  @Test
  fun `resolveMediaAttachments should not inline item media data from the Binary`() = runBlocking {
    mockBinary(binaryId = BINARY_ID, contentType = "image/png", data = imageBytes)
    val questionnaire = questionnaireWithItemMedia(attachment(url = "Binary/$BINARY_ID"))

    questionnaireMediaResolver.resolveMediaAttachments(questionnaire)

    val attachment = questionnaire.itemMediaAttachment()
    Assert.assertFalse(attachment.hasData())
    Assert.assertEquals("https://example.org/fhir/Binary/$BINARY_ID", attachment.url)
    Assert.assertEquals("image/png", attachment.contentType)
  }

  @Test
  fun `resolveMediaAttachments should keep the inline item media data when the Binary is missing`() =
    runBlocking {
      coEvery { fhirEngine.get(ResourceType.Binary, BINARY_ID) } throws
        ResourceNotFoundException(ResourceType.Binary.name, BINARY_ID)
      val questionnaire =
        questionnaireWithItemMedia(
          attachment(url = "Binary/$BINARY_ID", contentType = "image/png", data = imageBytes),
        )

      questionnaireMediaResolver.resolveMediaAttachments(questionnaire)

      val attachment = questionnaire.itemMediaAttachment()
      Assert.assertArrayEquals(imageBytes, attachment.data)
      Assert.assertEquals("https://example.org/fhir/Binary/$BINARY_ID", attachment.url)
    }

  @Test
  fun `resolveMediaAttachments should keep an absolute item media url as is`() = runBlocking {
    mockBinary(binaryId = BINARY_ID, contentType = "image/png", data = imageBytes)
    val absoluteUrl = "https://another.example.org/fhir/Binary/$BINARY_ID"
    val questionnaire = questionnaireWithItemMedia(attachment(url = absoluteUrl))

    questionnaireMediaResolver.resolveMediaAttachments(questionnaire)

    Assert.assertEquals(absoluteUrl, questionnaire.itemMediaAttachment().url)
  }

  @Test
  fun `resolveMediaAttachments should ignore item media that does not reference a Binary`() =
    runBlocking {
      val questionnaire =
        questionnaireWithItemMedia(attachment(contentType = "image/png", data = imageBytes))

      questionnaireMediaResolver.resolveMediaAttachments(questionnaire)

      val attachment = questionnaire.itemMediaAttachment()
      Assert.assertFalse(attachment.hasUrl())
      Assert.assertArrayEquals(imageBytes, attachment.data)
    }

  @Test
  fun `resolveMediaAttachments should resolve item media of nested items`() = runBlocking {
    mockBinary(binaryId = BINARY_ID, contentType = "image/png", data = imageBytes)
    val nestedItem =
      Questionnaire.QuestionnaireItemComponent().apply {
        linkId = "nested-item"
        addExtension(Extension(EXTENSION_ITEM_MEDIA, attachment(url = "Binary/$BINARY_ID")))
      }
    val questionnaire =
      Questionnaire().apply {
        addItem(
          Questionnaire.QuestionnaireItemComponent().apply {
            linkId = "group"
            type = Questionnaire.QuestionnaireItemType.GROUP
            addItem(nestedItem)
          },
        )
      }

    questionnaireMediaResolver.resolveMediaAttachments(questionnaire)

    Assert.assertEquals(
      "https://example.org/fhir/Binary/$BINARY_ID",
      (nestedItem.getExtensionByUrl(EXTENSION_ITEM_MEDIA).value as Attachment).url,
    )
  }

  @Test
  fun `resolveMediaAttachments should inline the answer option media data`() = runBlocking {
    mockBinary(binaryId = BINARY_ID, contentType = "image/png", data = imageBytes)
    val answerOption =
      Questionnaire.QuestionnaireItemAnswerOptionComponent(StringType("yes")).apply {
        addExtension(Extension(EXTENSION_ITEM_ANSWER_MEDIA, attachment(url = "Binary/$BINARY_ID")))
      }
    val questionnaire =
      Questionnaire().apply {
        addItem(
          Questionnaire.QuestionnaireItemComponent().apply {
            linkId = "choice-item"
            type = Questionnaire.QuestionnaireItemType.CHOICE
            addAnswerOption(answerOption)
          },
        )
      }

    questionnaireMediaResolver.resolveMediaAttachments(questionnaire)

    val attachment = answerOption.getExtensionByUrl(EXTENSION_ITEM_ANSWER_MEDIA).value as Attachment
    Assert.assertArrayEquals(imageBytes, attachment.data)
    Assert.assertEquals("image/png", attachment.contentType)
  }

  private fun mockBinary(binaryId: String, contentType: String, data: ByteArray) {
    coEvery { fhirEngine.get(ResourceType.Binary, binaryId) } returns
      Binary().apply {
        id = binaryId
        this.contentType = contentType
        this.data = data
      }
  }

  private fun attachment(
    url: String? = null,
    contentType: String? = null,
    data: ByteArray? = null,
  ) =
    Attachment().apply {
      url?.let { this.url = it }
      contentType?.let { this.contentType = it }
      data?.let { this.data = it }
    }

  private fun questionnaireWithItemMedia(attachment: Attachment) =
    Questionnaire().apply {
      addItem(
        Questionnaire.QuestionnaireItemComponent().apply {
          linkId = "media-item"
          type = Questionnaire.QuestionnaireItemType.DISPLAY
          addExtension(Extension(EXTENSION_ITEM_MEDIA, attachment))
        },
      )
    }

  private fun Questionnaire.itemMediaAttachment() =
    itemFirstRep.getExtensionByUrl(EXTENSION_ITEM_MEDIA).value as Attachment

  companion object {
    private const val BINARY_ID = "0eadaee6-1965-5862-ba94-fab36b971abf"
  }
}
