package com.jbsoft.jobq.ui.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jbsoft.jobq.domain.model.SearchResults
import com.jbsoft.jobq.domain.repository.CvRepository
import com.jbsoft.jobq.domain.repository.JobRepository
import com.jbsoft.jobq.domain.repository.ModelRepository
import com.jbsoft.jobq.domain.repository.SettingsRepository
import com.jbsoft.jobq.domain.search.JobSearchCoordinator
import com.jbsoft.jobq.domain.search.SearchState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
	val hasModel: Boolean,
	val hasCv: Boolean,
	/** A CV is imported but the model has not analysed it yet. */
	val cvPending: Boolean,
	val search: SearchState,
	val results: SearchResults?,
) {
	val ready get() = hasModel && hasCv
	val busy get() = search is SearchState.Understanding || search is SearchState.Fetching || search is SearchState.Ranking
}

class SearchViewModel(
	private val coordinator: JobSearchCoordinator,
	jobs: JobRepository,
	cv: CvRepository,
	models: ModelRepository,
	private val settings: SettingsRepository,
) : ViewModel() {

	var request by mutableStateOf("")
		private set

	/** Null while the first values load. */
	val state: StateFlow<SearchUiState?> = combine(
		models.observeModels(),
		cv.observe(),
		coordinator.state,
		jobs.observeLatestResults(),
	) { modelList, document, search, results ->
		SearchUiState(
			hasModel = modelList.any { it.active },
			hasCv = document?.profile != null,
			cvPending = document != null && document.profile == null,
			search = search,
			results = results,
		)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

	init {
		viewModelScope.launch {
			val last = settings.lastRequest.first()
			if (request.isEmpty()) request = last
		}
	}

	fun onRequestChange(value: String) {
		request = value
	}

	fun search() {
		val text = request.trim()
		viewModelScope.launch { settings.setLastRequest(text) }
		coordinator.search(text)
	}

	fun cancel() = coordinator.cancel()
}
