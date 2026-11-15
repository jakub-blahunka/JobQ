package com.jbsoft.jobq.ui.models

import android.Manifest
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jbsoft.jobq.R
import com.jbsoft.jobq.domain.model.DownloadFailure
import com.jbsoft.jobq.domain.model.ModelState
import com.jbsoft.jobq.domain.model.ModelStatus
import com.jbsoft.jobq.domain.repository.ModelRepository
import com.jbsoft.jobq.ui.components.Pill
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

class ModelsViewModel(private val repository: ModelRepository) : ViewModel() {
	val models: StateFlow<List<ModelStatus>> =
		repository.observeModels().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

	fun download(id: String) = repository.download(id)
	fun cancel(id: String) = viewModelScope.launch { repository.cancelDownload(id) }
	fun delete(id: String) = viewModelScope.launch { repository.delete(id) }
	fun select(id: String) = viewModelScope.launch { repository.select(id) }
}

@Composable
fun ModelsScreen(contentPadding: PaddingValues, viewModel: ModelsViewModel = koinViewModel()) {
	val models by viewModel.models.collectAsStateWithLifecycle()

	// The download shows its progress in a notification. It runs either way, so the answer is not checked.
	var pendingDownload by remember { mutableStateOf<String?>(null) }
	val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		pendingDownload?.let(viewModel::download)
		pendingDownload = null
	}
	val startDownload = { id: String ->
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			pendingDownload = id
			notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
		} else {
			viewModel.download(id)
		}
	}

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = contentPadding.calculateTopPadding() + 24.dp,
			bottom = contentPadding.calculateBottomPadding() + 24.dp,
		),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		item(key = "header") {
			Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(stringResource(R.string.models_title), style = MaterialTheme.typography.headlineMedium)
				Text(stringResource(R.string.models_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
		}
		items(models, key = { it.model.id }) { status ->
			ModelCard(
				status = status,
				onDownload = { startDownload(status.model.id) },
				onCancel = { viewModel.cancel(status.model.id) },
				onDelete = { viewModel.delete(status.model.id) },
				onSelect = { viewModel.select(status.model.id) },
			)
		}
	}
}

@Composable
private fun ModelCard(status: ModelStatus, onDownload: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit, onSelect: () -> Unit) {
	val model = status.model
	val context = LocalContext.current
	val uriHandler = LocalUriHandler.current
	fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

	Card(
		colors = CardDefaults.cardColors(
			containerColor = if (status.active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
		),
	) {
		Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(model.name, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleLarge)
				if (model.recommended) Pill(stringResource(R.string.model_recommended), emphasized = true)
			}
			Text("${model.family} · ${size(model.sizeBytes)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			Text(model.blurb, style = MaterialTheme.typography.bodyLarge)
			TextButton(onClick = { uriHandler.openUri(model.licenseUrl) }, contentPadding = PaddingValues(0.dp)) {
				Text(stringResource(R.string.model_license, model.license), style = MaterialTheme.typography.bodyMedium)
			}

			when (val state = status.state) {
				ModelState.NotDownloaded -> Button(onClick = onDownload) { Text(stringResource(R.string.model_download, size(model.sizeBytes))) }
				is ModelState.Failed -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
					Text(
						when (state.reason) {
							DownloadFailure.NotEnoughStorage -> stringResource(R.string.model_failed_storage, size(model.sizeBytes))
							DownloadFailure.Other -> stringResource(R.string.model_failed)
						},
						Modifier.weight(1f),
						color = MaterialTheme.colorScheme.error,
					)
					FilledTonalButton(onClick = onDownload) { Text(stringResource(R.string.model_retry)) }
				}
				is ModelState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
					if (state.downloadedBytes > 0) {
						LinearProgressIndicator(progress = { state.downloadedBytes.toFloat() / state.totalBytes }, modifier = Modifier.fillMaxWidth())
					} else {
						LinearProgressIndicator(Modifier.fillMaxWidth())
					}
					Row(verticalAlignment = Alignment.CenterVertically) {
						Text(
							if (state.downloadedBytes > 0) {
								stringResource(R.string.model_downloading, size(state.downloadedBytes), size(state.totalBytes))
							} else {
								stringResource(R.string.model_waiting)
							},
							Modifier.weight(1f),
							style = MaterialTheme.typography.bodyMedium,
						)
						TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
					}
				}
				ModelState.Downloaded -> Row(verticalAlignment = Alignment.CenterVertically) {
					if (status.active) {
						Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
						Text(stringResource(R.string.model_in_use), Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
					} else {
						FilledTonalButton(onClick = onSelect) { Text(stringResource(R.string.model_use)) }
						Row(Modifier.weight(1f)) {}
					}
					IconButton(onClick = onDelete) {
						Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.model_delete_description, model.name))
					}
				}
			}
		}
	}
}
