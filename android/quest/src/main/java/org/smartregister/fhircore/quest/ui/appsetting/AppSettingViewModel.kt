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

package org.smartregister.fhircore.quest.ui.appsetting

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.net.UnknownHostException
import javax.inject.Inject
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.lang3.StringUtils
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Composition
import org.hl7.fhir.r4.model.ResourceType
import org.jetbrains.annotations.VisibleForTesting
import org.smartregister.fhircore.engine.BuildConfig
import org.smartregister.fhircore.engine.R
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.app.ConfigService
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.data.remote.fhir.resource.FhirResourceDataSource
import org.smartregister.fhircore.engine.di.NetworkModule
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.extractId
import org.smartregister.fhircore.engine.util.extension.getActivity
import org.smartregister.fhircore.engine.util.extension.launchActivityWithNoBackStackHistory
import org.smartregister.fhircore.engine.util.extension.retrieveCompositionSections
import org.smartregister.fhircore.engine.util.extension.retrieveImplementationGuideDefinitionResources
import org.smartregister.fhircore.quest.ui.login.LoginActivity
import org.smartregister.fhircore.quest.ui.main.AppMainActivity
import retrofit2.HttpException
import timber.log.Timber

typealias QuestBuildConfig = org.smartregister.fhircore.quest.BuildConfig

@HiltViewModel
class AppSettingViewModel
@Inject
constructor(
  val fhirResourceDataSource: FhirResourceDataSource,
  val defaultRepository: DefaultRepository,
  val sharedPreferencesHelper: SharedPreferencesHelper,
  val configService: ConfigService,
  val configurationRegistry: ConfigurationRegistry,
  val dispatcherProvider: DispatcherProvider,
) : ViewModel() {

  private var _isNonProxy = BuildConfig.IS_NON_PROXY_APK
  private val exceptionHandler = CoroutineExceptionHandler { _, exception -> Timber.e(exception) }

  val showProgressBar = MutableLiveData(false)

  private val _appId = MutableLiveData("")
  val appId
    get() = _appId

  private val _error = MutableLiveData("")
  val error: LiveData<String>
    get() = _error

  fun onApplicationIdChanged(appId: String) {
    // Deliberately not persisted here. The app ID is only written once its configurations have
    // actually loaded, otherwise an interrupted first run leaves an app ID behind that sends every
    // later launch down the "already configured" path, which never contacts the server again.
    _appId.value = appId
    _error.value = ""
  }

  private fun isDebugAppId(appId: String) =
    appId.endsWith(ConfigurationRegistry.DEBUG_SUFFIX, ignoreCase = true) &&
      sharedPreferencesHelper.isDebugVariant()

  /**
   * Fetch the [Composition] resource whose identifier matches the provided [appId]. Save the
   * composition resource and all the nested resources referenced in the
   * [Composition.SectionComponent].
   */
  fun fetchConfigurations(context: Context) {
    showProgressBar.postValue(true)
    val appId = appId.value?.trim()
    if (!appId.isNullOrEmpty()) {
      when {
        isDebugAppId(appId) -> loadConfigurations(context)
        else -> fetchRemoteConfigurations(appId, context)
      }
    }
  }

  private fun fetchRemoteConfigurations(appId: String?, context: Context) {
    viewModelScope.launch(exceptionHandler) {
      try {
        showProgressBar.postValue(true)

        Timber.i(
          "Fetching configs for app $appId with highest context-quantity ${QuestBuildConfig.VERSION_CODE}",
        )

        val compositionResource: Composition?

        val implementationGuideResource =
          configurationRegistry.fetchRemoteImplementationGuideByAppId(
            appId,
            QuestBuildConfig.VERSION_CODE,
          )

        compositionResource =
          if (implementationGuideResource != null) {
            configurationRegistry.addOrUpdate(implementationGuideResource)

            val compositionReference =
              implementationGuideResource
                .retrieveImplementationGuideDefinitionResources()[0]
                .reference
                .reference

            val compositionIdWithHistory = compositionReference?.substringAfter('/')
            val compositionId = compositionIdWithHistory?.substringBefore('/')
            val compositionVersion = compositionIdWithHistory?.substringAfterLast('/', "")

            withContext(dispatcherProvider.io()) {
              configurationRegistry.fetchRemoteCompositionById(compositionId, compositionVersion)
            }
          } else {
            withContext(dispatcherProvider.io()) {
              configurationRegistry.fetchRemoteCompositionByAppId(appId)
            }
          }

        if (compositionResource == null) {
          showProgressBar.postValue(false)
          _error.postValue(context.getString(R.string.application_not_supported, appId?.trim()))
          return@launch
        }

        // Save composition
        defaultRepository.createRemote(false, compositionResource)

        compositionResource
          .retrieveCompositionSections()
          .asSequence()
          .filter { it.hasFocus() && it.focus.hasReferenceElement() }
          .groupBy {
            it.focus.reference.substringBefore(
              ConfigurationRegistry.TYPE_REFERENCE_DELIMITER,
              missingDelimiterValue = "",
            )
          }
          .filter { it.key == ResourceType.Binary.name || it.key == ResourceType.Parameters.name }
          .forEach { entry: Map.Entry<String, List<Composition.SectionComponent>> ->
            val chunkedResourceIdList =
              entry.value.chunked(ConfigurationRegistry.MANIFEST_PROCESSOR_BATCH_SIZE)
            chunkedResourceIdList.forEach { parentIt ->
              Timber.d(
                "Fetching config resource ${entry.key}: with ids ${StringUtils.join(parentIt,",")}",
              )

              val resultBundle: Bundle =
                if (isNonProxy()) {
                  fhirResourceDataSourceGetBundle(
                    entry.key,
                    parentIt.map { it.focus.extractId() },
                  )
                } else {
                  fhirResourceDataSource.post(
                    requestBody =
                      generateRequestBundle(entry.key, parentIt.map { it.focus.extractId() })
                        .encodeResourceToString()
                        .toRequestBody(NetworkModule.JSON_MEDIA_TYPE),
                  )
                }

              resultBundle.entry.forEach { bundleEntryComponent ->
                if (bundleEntryComponent.resource != null) {
                  defaultRepository.createRemote(false, bundleEntryComponent.resource)
                }
              }
            }
          }

        Timber.d("Done fetching application configurations remotely")
        loadConfigurations(context, fetchWhenMissing = false)
      } catch (unknownHostException: UnknownHostException) {
        _error.postValue(context.getString(R.string.error_loading_config_no_internet))
        showProgressBar.postValue(false)
      } catch (httpException: HttpException) {
        if ((400..503).contains(httpException.response()!!.code())) {
          _error.postValue(context.getString(R.string.error_loading_config_general))
        } else {
          _error.postValue(context.getString(R.string.error_loading_config_http_error))
        }
        showProgressBar.postValue(false)
      }
    }
  }

  suspend fun fetchComposition(urlPath: String, context: Context): Composition? {
    return fhirResourceDataSource.getResource(urlPath).entryFirstRep.let {
      if (!it.hasResource()) {
        Timber.w("No response for composition resource on path $urlPath")
        showProgressBar.postValue(false)
        _error.postValue(context.getString(R.string.application_not_supported, appId.value?.trim()))
        return null
      }

      it.resource as Composition
    }
  }

  /**
   * Loads the configurations already on the device for the current app ID.
   *
   * [fetchWhenMissing] makes a device whose configurations are absent or incomplete recover on its
   * own: the setup is retried against the server instead of failing for good. That is what a first
   * run interrupted part way through leaves behind, and it used to be unrecoverable without
   * clearing the app data, because this path never contacts the server. It is off when called back
   * from [fetchRemoteConfigurations] so that a genuinely unsupported app ID cannot loop.
   */
  fun loadConfigurations(context: Context, fetchWhenMissing: Boolean = true) {
    appId.value?.trim()?.let { thisAppId ->
      viewModelScope.launch(dispatcherProvider.io()) {
        configurationRegistry.loadConfigurations(thisAppId, context) { loadConfigSuccessful ->
          when {
            loadConfigSuccessful -> {
              showProgressBar.postValue(false)
              sharedPreferencesHelper.write(SharedPreferenceKey.APP_ID.name, thisAppId)
              val activity = context.getActivity()
              when {
                org.smartregister.fhircore.quest.BuildConfig.SKIP_AUTHENTICATION ->
                  activity?.startActivity(Intent(context, AppMainActivity::class.java))
                else -> activity?.launchActivityWithNoBackStackHistory<LoginActivity>()
              }
            }
            fetchWhenMissing && !isDebugAppId(thisAppId) -> {
              Timber.w("Configurations missing for $thisAppId, fetching them from the server")
              fetchRemoteConfigurations(thisAppId, context)
            }
            else -> {
              showProgressBar.postValue(false)
              _error.postValue(context.getString(R.string.application_not_supported, thisAppId))
            }
          }
        }
      }
    }
  }

  private fun generateRequestBundle(resourceType: String, idList: List<String>): Bundle {
    val bundleEntryComponents = mutableListOf<Bundle.BundleEntryComponent>()

    idList.forEach {
      bundleEntryComponents.add(
        Bundle.BundleEntryComponent().apply {
          request =
            Bundle.BundleEntryRequestComponent().apply {
              url = "$resourceType/$it"
              method = Bundle.HTTPVerb.GET
            }
        },
      )
    }

    return Bundle().apply {
      type = Bundle.BundleType.BATCH
      entry = bundleEntryComponents
    }
  }

  private suspend fun fhirResourceDataSourceGetBundle(
    resourceType: String,
    resourceIds: List<String>,
  ): Bundle {
    val bundleEntryComponents = mutableListOf<Bundle.BundleEntryComponent>()

    resourceIds.forEach {
      val responseBundle =
        fhirResourceDataSource.getResource("$resourceType?${Composition.SP_RES_ID}=$it")
      responseBundle.let {
        bundleEntryComponents.add(
          Bundle.BundleEntryComponent().apply {
            resource = responseBundle.entry?.firstOrNull()?.resource
          },
        )
      }
    }
    return Bundle().apply {
      type = Bundle.BundleType.COLLECTION
      entry = bundleEntryComponents
    }
  }

  @VisibleForTesting fun isNonProxy(): Boolean = _isNonProxy
}
