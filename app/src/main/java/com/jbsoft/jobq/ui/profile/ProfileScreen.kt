package com.jbsoft.jobq.ui.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jbsoft.jobq.R
import com.jbsoft.jobq.domain.cv.CvFailure
import com.jbsoft.jobq.domain.cv.CvImportState
import com.jbsoft.jobq.domain.cv.CvImporter
import com.jbsoft.jobq.domain.model.CvDocument
import com.jbsoft.jobq.domain.model.CvProfile
import com.jbsoft.jobq.domain.repository.CvRepository
import com.jbsoft.jobq.ui.components.EmptyState
import com.jbsoft.jobq.ui.components.Pill
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.koin.androidx.compose.koinViewModel

data class ProfileUiState(val document: CvDocument?, val import: CvImportState)

class ProfileViewModel(repository: CvRepository, private val importer: CvImporter) : ViewModel() {
	/** Null while loading. */
	val state: StateFlow<ProfileUiState?> = combine(repository.observe(), importer.state, ::ProfileUiState)
		.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	fun import(uri: String) = importer.import(uri)
	fun reanalyze() = importer.reanalyze()

	fun remove() = importer.remove()
}

@Composable
fun ProfileScreen(onOpenModels: () -> Unit, contentPadding: PaddingValues, viewModel: ProfileViewModel = koinViewModel()) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
		if (uri != null) viewModel.import(uri.toString())
	}
	val pickPdf = { picker.launch(arrayOf("application/pdf")) }
	val ui = state ?: return
	val busy = ui.import is CvImportState.Reading || ui.import is CvImportState.Analyzing

	Column(
		Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(contentPadding)
			.padding(horizontal = 16.dp, vertical = 24.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineMedium)

		ImportStatus(ui.import, onOpenModels)

		val document = ui.document
		if (document == null) {
			if (!busy) {
				EmptyState(stringResource(R.string.profile_empty_title), stringResource(R.string.profile_empty_body)) {
					Button(onClick = pickPdf) { Text(stringResource(R.string.profile_import)) }
				}
			}
		} else {
			val profile = document.profile
			if (profile != null) {
				ProfileCard(profile)
			} else if (!busy) {
				Text(stringResource(R.string.profile_not_analyzed), color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			Text(document.fileName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				FilledTonalButton(onClick = pickPdf, enabled = !busy) { Text(stringResource(R.string.profile_replace)) }
				OutlinedButton(onClick = viewModel::reanalyze, enabled = !busy) { Text(stringResource(R.string.profile_reanalyze)) }
			}
			TextButton(onClick = viewModel::remove, enabled = !busy) {
				Text(stringResource(R.string.profile_remove), color = if (busy) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error)
			}
		}

		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
			Spacer(Modifier.width(8.dp))
			Text(stringResource(R.string.profile_privacy), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
private fun ImportStatus(state: CvImportState, onOpenModels: () -> Unit) {
	when (state) {
		CvImportState.Idle -> Unit
		CvImportState.Reading, CvImportState.Analyzing -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
			Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
				Text(
					stringResource(if (state is CvImportState.Reading) R.string.profile_reading else R.string.profile_analyzing),
					style = MaterialTheme.typography.bodyLarge,
				)
				LinearProgressIndicator(Modifier.fillMaxWidth())
			}
		}
		is CvImportState.Failed -> Card(
			colors = CardDefaults.cardColors(
				containerColor = if (state.reason == CvFailure.NoModel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
			),
		) {
			Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					stringResource(
						when (state.reason) {
							CvFailure.Unreadable -> R.string.cv_failed_unreadable
							CvFailure.NoText -> R.string.cv_failed_no_text
							CvFailure.NoModel -> R.string.cv_failed_no_model
							CvFailure.AnalysisFailed -> R.string.cv_failed_analysis
							CvFailure.StorageFailed -> R.string.cv_failed_storage
						}
					),
					style = MaterialTheme.typography.bodyLarge,
				)
				if (state.reason == CvFailure.NoModel) {
					FilledTonalButton(onClick = onOpenModels) { Text(stringResource(R.string.profile_go_to_models)) }
				}
			}
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileCard(profile: CvProfile) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
		Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
			Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
				if (profile.name.isNotBlank()) Text(profile.name, style = MaterialTheme.typography.titleLarge)
				if (profile.headline.isNotBlank()) Text(profile.headline, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
				if (profile.yearsExperience > 0) {
					Text(
						pluralStringResource(R.plurals.profile_years, profile.yearsExperience, profile.yearsExperience),
						style = MaterialTheme.typography.bodyMedium,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
					)
				}
			}
			if (profile.summary.isNotBlank()) Text(profile.summary, style = MaterialTheme.typography.bodyLarge)

			if (profile.skills.isNotEmpty()) {
				Section(stringResource(R.string.profile_skills)) {
					FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						profile.skills.forEach { Pill(it) }
					}
				}
			}
			if (profile.roles.isNotEmpty()) {
				Section(stringResource(R.string.profile_roles)) {
					profile.roles.forEach { role ->
						Column {
							Text(role.title, style = MaterialTheme.typography.bodyLarge)
							Text(
								listOf(role.company, role.period).filter { it.isNotBlank() }.joinToString(" · "),
								style = MaterialTheme.typography.bodyMedium,
								color = MaterialTheme.colorScheme.onSurfaceVariant,
							)
						}
					}
				}
			}
			if (profile.education.isNotEmpty()) {
				Section(stringResource(R.string.profile_education)) {
					profile.education.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
				}
			}
			if (profile.languages.isNotEmpty()) {
				Section(stringResource(R.string.profile_languages)) {
					Text(profile.languages.joinToString(), style = MaterialTheme.typography.bodyLarge)
				}
			}
		}
	}
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(title, style = MaterialTheme.typography.titleMedium)
		content()
	}
}
