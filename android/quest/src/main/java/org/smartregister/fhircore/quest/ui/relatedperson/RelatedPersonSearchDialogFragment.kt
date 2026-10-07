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

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import org.smartregister.fhircore.engine.ui.theme.AppTheme
import org.smartregister.fhircore.engine.util.extension.RelatedPersonAgeFilter

@AndroidEntryPoint
class RelatedPersonSearchDialogFragment : DialogFragment() {

  private val viewModel by viewModels<RelatedPersonSearchViewModel>()
  private var resultDelivered = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setStyle(STYLE_NORMAL, android.R.style.Theme_Material_Light_NoActionBar)
    val excludePatientId = requireArguments().getString(ARG_EXCLUDE_ID).orEmpty()
    val ageFilter =
      requireArguments().getString(ARG_AGE_FILTER)?.let {
        runCatching { RelatedPersonAgeFilter.valueOf(it) }
          .getOrDefault(RelatedPersonAgeFilter.UNDER_18)
      } ?: RelatedPersonAgeFilter.UNDER_18
    viewModel.initialize(excludePatientId, ageFilter)
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?,
  ): View {
    return ComposeView(requireContext()).apply {
      setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
      setContent {
        val uiState by viewModel.uiState.collectAsState()
        AppTheme {
          RelatedPersonSearchScreen(
            uiState = uiState,
            onQueryChanged = viewModel::onQueryChanged,
            onAgeFilterChanged = viewModel::onAgeFilterChanged,
            onPatientSelected = { patientId -> deliverResult(patientId) },
            onClose = { deliverResult(null) },
          )
        }
      }
    }
  }

  override fun onStart() {
    super.onStart()
    dialog
      ?.window
      ?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
  }

  override fun onCancel(dialog: DialogInterface) {
    deliverResult(null)
    super.onCancel(dialog)
  }

  private fun deliverResult(patientId: String?) {
    if (resultDelivered) return
    resultDelivered = true
    parentFragmentManager.setFragmentResult(
      REQUEST_KEY,
      bundleOf(RESULT_PATIENT_ID to patientId),
    )
    dismissAllowingStateLoss()
  }

  companion object {
    const val TAG = "RelatedPersonSearchDialog"
    const val REQUEST_KEY = "related_person_search_result"
    const val RESULT_PATIENT_ID = "patient_id"
    const val ARG_EXCLUDE_ID = "exclude_id"
    const val ARG_AGE_FILTER = "age_filter"

    fun newInstance(
      excludePatientId: String,
      ageFilter: RelatedPersonAgeFilter,
    ): RelatedPersonSearchDialogFragment {
      return RelatedPersonSearchDialogFragment().apply {
        arguments =
          bundleOf(
            ARG_EXCLUDE_ID to excludePatientId,
            ARG_AGE_FILTER to ageFilter.name,
          )
      }
    }
  }
}
