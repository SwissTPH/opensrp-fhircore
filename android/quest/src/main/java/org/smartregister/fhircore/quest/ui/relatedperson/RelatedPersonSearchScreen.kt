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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Scaffold
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.smartregister.fhircore.engine.ui.theme.DefaultColor
import org.smartregister.fhircore.engine.ui.theme.DividerColor
import org.smartregister.fhircore.engine.util.extension.RelatedPersonAgeFilter
import org.smartregister.fhircore.quest.R

const val RELATED_PERSON_SEARCH_FIELD_TAG = "relatedPersonSearchFieldTag"
const val RELATED_PERSON_AGE_UNDER_18_TAG = "relatedPersonAgeUnder18Tag"
const val RELATED_PERSON_AGE_18_PLUS_TAG = "relatedPersonAge18PlusTag"

@Composable
fun RelatedPersonSearchScreen(
  uiState: RelatedPersonSearchUiState,
  onQueryChanged: (String) -> Unit,
  onAgeFilterChanged: (RelatedPersonAgeFilter) -> Unit,
  onPatientSelected: (String) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Scaffold(
    modifier = modifier.fillMaxSize(),
    topBar = {
      TopAppBar(
        title = { Text(text = stringResource(R.string.related_person_search_title)) },
        navigationIcon = {
          IconButton(onClick = onClose) {
            Icon(
              imageVector = Icons.Filled.Close,
              contentDescription = stringResource(R.string.related_person_search_close),
            )
          }
        },
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
      Text(
        text = stringResource(R.string.related_person_age_filter_label),
        style = MaterialTheme.typography.subtitle2,
        color = DefaultColor,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AgeFilterChip(
          label = stringResource(R.string.related_person_age_under_18),
          selected = uiState.ageFilter == RelatedPersonAgeFilter.UNDER_18,
          onClick = { onAgeFilterChanged(RelatedPersonAgeFilter.UNDER_18) },
          modifier = Modifier.testTag(RELATED_PERSON_AGE_UNDER_18_TAG),
        )
        AgeFilterChip(
          label = stringResource(R.string.related_person_age_18_plus),
          selected = uiState.ageFilter == RelatedPersonAgeFilter.AGE_18_OR_OVER,
          onClick = { onAgeFilterChanged(RelatedPersonAgeFilter.AGE_18_OR_OVER) },
          modifier = Modifier.testTag(RELATED_PERSON_AGE_18_PLUS_TAG),
        )
      }
      Spacer(modifier = Modifier.height(16.dp))
      OutlinedTextField(
        value = uiState.query,
        onValueChange = onQueryChanged,
        modifier = Modifier.fillMaxWidth().testTag(RELATED_PERSON_SEARCH_FIELD_TAG),
        singleLine = true,
        placeholder = { Text(text = stringResource(R.string.related_person_search_hint)) },
      )
      Spacer(modifier = Modifier.height(16.dp))
      when {
        uiState.isLoading -> {
          CircularProgressIndicator(
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp),
            strokeWidth = 2.dp,
          )
        }
        uiState.results.isEmpty() -> {
          Text(
            text = stringResource(R.string.related_person_search_empty),
            color = DefaultColor,
            modifier = Modifier.padding(top = 24.dp),
          )
        }
        else -> {
          LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(uiState.results, key = { it.patientId }) { item ->
              Column(
                modifier =
                  Modifier.fillMaxWidth()
                    .clickable { onPatientSelected(item.patientId) }
                    .padding(vertical = 12.dp),
              ) {
                Text(
                  text = item.name,
                  style = MaterialTheme.typography.subtitle1,
                  fontWeight = FontWeight.SemiBold,
                )
                if (item.details.isNotBlank()) {
                  Text(
                    text = item.details,
                    style = MaterialTheme.typography.body2,
                    color = DefaultColor,
                  )
                }
              }
              Divider(color = DividerColor)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AgeFilterChip(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (selected) {
    TextButton(
      onClick = onClick,
      modifier = modifier,
      colors =
        ButtonDefaults.textButtonColors(
          backgroundColor = MaterialTheme.colors.primary.copy(alpha = 0.12f),
          contentColor = MaterialTheme.colors.primary,
        ),
    ) {
      Text(text = label)
    }
  } else {
    OutlinedButton(onClick = onClick, modifier = modifier) { Text(text = label) }
  }
}
