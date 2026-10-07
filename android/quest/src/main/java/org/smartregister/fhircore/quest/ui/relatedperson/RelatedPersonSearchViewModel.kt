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

package org.smartregister.fhircore.quest.ui.relatedperson

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.fhir.datacapture.extensions.logicalId
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Patient
import org.smartregister.fhircore.engine.data.local.RelatedPersonLinkService
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.extension.RelatedPersonAgeFilter
import org.smartregister.fhircore.engine.util.extension.extractAge
import org.smartregister.fhircore.engine.util.extension.extractGender

data class RelatedPersonSearchUiState(
  val query: String = "",
  val ageFilter: RelatedPersonAgeFilter = RelatedPersonAgeFilter.UNDER_18,
  val results: List<RelatedPersonSearchItem> = emptyList(),
  val isLoading: Boolean = false,
)

data class RelatedPersonSearchItem(
  val patientId: String,
  val name: String,
  val details: String,
)

@HiltViewModel
class RelatedPersonSearchViewModel
@Inject
constructor(
  private val relatedPersonLinkService: RelatedPersonLinkService,
  private val dispatcherProvider: DispatcherProvider,
  @ApplicationContext private val context: Context,
) : ViewModel() {

  private val _uiState = MutableStateFlow(RelatedPersonSearchUiState())
  val uiState: StateFlow<RelatedPersonSearchUiState> = _uiState.asStateFlow()

  private var excludePatientId: String = ""

  fun initialize(excludePatientId: String, ageFilter: RelatedPersonAgeFilter) {
    this.excludePatientId = excludePatientId
    _uiState.update { it.copy(ageFilter = ageFilter) }
    search()
  }

  fun onQueryChanged(query: String) {
    _uiState.update { it.copy(query = query) }
    search()
  }

  fun onAgeFilterChanged(ageFilter: RelatedPersonAgeFilter) {
    _uiState.update { it.copy(ageFilter = ageFilter) }
    search()
  }

  fun search() {
    val snapshot = _uiState.value
    viewModelScope.launch(dispatcherProvider.io()) {
      _uiState.update { it.copy(isLoading = true) }
      val patients =
        runCatching {
            relatedPersonLinkService.searchPatients(
              query = snapshot.query,
              ageFilter = snapshot.ageFilter,
              excludePatientId = excludePatientId,
            )
          }
          .getOrDefault(emptyList())
      _uiState.update {
        it.copy(isLoading = false, results = patients.map { patient -> patient.toItem() })
      }
    }
  }

  private fun Patient.toItem(): RelatedPersonSearchItem {
    val name = nameFirstRep.nameAsSingleString?.trim().orEmpty().ifBlank { logicalId }
    val gender = extractGender(context)
    val age = extractAge(context)
    val details = listOf(gender, age).filter { it.isNotBlank() }.joinToString(", ")
    return RelatedPersonSearchItem(patientId = logicalId, name = name, details = details)
  }
}
