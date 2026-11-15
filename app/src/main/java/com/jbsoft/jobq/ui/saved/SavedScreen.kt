package com.jbsoft.jobq.ui.saved

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jbsoft.jobq.R
import com.jbsoft.jobq.domain.model.RankedJob
import com.jbsoft.jobq.domain.repository.JobRepository
import com.jbsoft.jobq.ui.components.EmptyState
import com.jbsoft.jobq.ui.components.JobCard
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.koin.androidx.compose.koinViewModel

class SavedViewModel(jobs: JobRepository) : ViewModel() {
	/** Null while loading. */
	val saved: StateFlow<List<RankedJob>?> =
		jobs.observeSaved().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun SavedScreen(onOpenJob: (String) -> Unit, contentPadding: PaddingValues, viewModel: SavedViewModel = koinViewModel()) {
	val saved by viewModel.saved.collectAsStateWithLifecycle()
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
		item(key = "title") { Text(stringResource(R.string.saved_title), style = MaterialTheme.typography.headlineMedium) }
		val list = saved
		if (list != null && list.isEmpty()) {
			item(key = "empty") { EmptyState(stringResource(R.string.saved_empty_title), stringResource(R.string.saved_empty_body)) }
		}
		items(list.orEmpty(), key = { it.job.id }) { job -> JobCard(job, onClick = { onOpenJob(job.job.id) }, Modifier.animateItem()) }
	}
}
