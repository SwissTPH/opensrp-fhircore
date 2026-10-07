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
import com.google.android.fhir.get
import javax.inject.Inject
import javax.inject.Singleton
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.Binary
import org.hl7.fhir.r4.model.Questionnaire
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import timber.log.Timber

/**
 * Prepares the media attachments of a [Questionnaire] so that the SDC library renders them.
 *
 * Content authoring tools (e.g. TRICC) attach images to a questionnaire item through a `Binary`
 * resource that is synced together with the rest of the config content, e.g.
 *
 * ```json
 * {
 *   "url": "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-itemMedia",
 *   "valueAttachment": { "contentType": "image/png", "url": "Binary/0eadaee6-..." }
 * }
 * ```
 *
 * The SDC library cannot resolve such a relative reference on its own: it only hands the attachment
 * `url` over to [org.smartregister.fhircore.engine.data.remote.fhir.resource.ReferenceUrlResolver]
 * when `ca.uhn.fhir.util.UrlUtil.isValid` accepts it, which requires an absolute http(s) URL, and
 * answer option media is rendered from the inline `data` only. This resolver therefore rewrites
 * each media attachment before the questionnaire is rendered:
 * - `sdc-questionnaire-itemMedia`: the reference becomes an absolute `Binary` URL, which the
 *   `ReferenceUrlResolver` serves from the locally synced database. Inline `data`, when present, is
 *   kept so SDC can decode it without a URL fetch.
 * - `sdc-questionnaire-itemAnswerMedia`: the image bytes are inlined from the local `Binary`, as
 *   answer option media is only rendered from [Attachment.data].
 */
@Singleton
class QuestionnaireMediaResolver
@Inject
constructor(val fhirEngine: FhirEngine, val configService: ConfigService) {

  /**
   * Resolve the media attachments of all the [Questionnaire.item]s of [questionnaire], in place.
   */
  suspend fun resolveMediaAttachments(questionnaire: Questionnaire) {
    questionnaire.item?.forEach { resolveItemMediaAttachments(it) }
  }

  private suspend fun resolveItemMediaAttachments(item: Questionnaire.QuestionnaireItemComponent) {
    item.mediaAttachment(EXTENSION_ITEM_MEDIA)?.let { resolveItemMedia(it) }
    item.answerOption?.forEach { answerOption ->
      answerOption.mediaAttachment(EXTENSION_ITEM_ANSWER_MEDIA)?.let { resolveItemAnswerMedia(it) }
    }
    item.item?.forEach { resolveItemMediaAttachments(it) }
  }

  /**
   * Point the attachment at an absolute `Binary` URL so that the SDC library requests the image
   * through the app's url resolver. Existing inline `data` is left in place: SDC prefers it.
   */
  private suspend fun resolveItemMedia(attachment: Attachment) {
    val binaryId = attachment.binaryIdOrNull() ?: return
    if (!attachment.hasContentType()) {
      attachment.applyContentTypeOf(loadBinary(binaryId))
    }
    attachment.url = attachment.absoluteBinaryUrl(binaryId)
  }

  /**
   * Inline the bytes of the referenced `Binary`, the only form answer option media is rendered in.
   */
  private suspend fun resolveItemAnswerMedia(attachment: Attachment) {
    if (attachment.hasData()) return
    val binaryId = attachment.binaryIdOrNull() ?: return
    val binary = loadBinary(binaryId)?.takeIf { it.hasData() } ?: return

    attachment.data = binary.data
    attachment.applyContentTypeOf(binary)
  }

  private suspend fun loadBinary(binaryId: String): Binary? =
    try {
      fhirEngine.get<Binary>(binaryId)
    } catch (exception: Exception) {
      Timber.w(exception, "Binary/$binaryId referenced by a questionnaire media is not available")
      null
    }

  private fun Attachment.applyContentTypeOf(binary: Binary?) {
    if (!hasContentType() && binary?.hasContentType() == true) contentType = binary.contentType
  }

  /**
   * The logical id of the `Binary` this attachment points to, for both relative (`Binary/{id}`) and
   * absolute (`https://example.org/fhir/Binary/{id}`) references, or `null` when the attachment
   * does not reference a `Binary` at all.
   */
  private fun Attachment.binaryIdOrNull(): String? =
    url
      ?.takeIf { it.contains(BINARY_REFERENCE_PREFIX) }
      ?.substringAfterLast(BINARY_REFERENCE_PREFIX)
      ?.substringBefore("/")
      ?.substringBefore("?")
      ?.takeIf { it.isNotBlank() }

  private fun Attachment.absoluteBinaryUrl(binaryId: String): String =
    if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", true)) {
      url
    } else {
      val baseUrl = configService.provideAuthConfiguration().fhirServerBaseUrl.trimEnd('/')
      "$baseUrl/$BINARY_REFERENCE_PREFIX$binaryId"
    }

  private fun Questionnaire.QuestionnaireItemComponent.mediaAttachment(extensionUrl: String) =
    getExtensionByUrl(extensionUrl)?.value as? Attachment

  private fun Questionnaire.QuestionnaireItemAnswerOptionComponent.mediaAttachment(
    extensionUrl: String,
  ) = getExtensionByUrl(extensionUrl)?.value as? Attachment

  companion object {
    const val EXTENSION_ITEM_MEDIA =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-itemMedia"
    const val EXTENSION_ITEM_ANSWER_MEDIA =
      "http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-itemAnswerMedia"
    private const val BINARY_REFERENCE_PREFIX = "Binary/"
  }
}
