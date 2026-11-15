package com.jbsoft.jobq.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jbsoft.jobq.domain.ai.JobScorer
import com.jbsoft.jobq.domain.ai.LlmException
import com.jbsoft.jobq.domain.model.RankedJob
import com.jbsoft.jobq.domain.model.SearchCriteria
import com.jbsoft.jobq.domain.repository.CvRepository
import com.jbsoft.jobq.domain.repository.JobRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class Analysis { Idle, Running, Failed }

data class JobDetailUiState(
	val loading: Boolean = true,
	val item: RankedJob? = null,
	val analysis: Analysis = Analysis.Idle,
)

class JobDetailViewModel(
	private val jobId: String,
	private val jobs: JobRepository,
	private val cv: CvRepository,
	private val scorer: JobScorer,
) : ViewModel() {

	private val analysis = MutableStateFlow(Analysis.Idle)
	private var analysisJob: Job? = null

	val state: StateFlow<JobDetailUiState> = combine(jobs.observeJob(jobId), analysis) { item, analysis ->
		JobDetailUiState(loading = false, item = item, analysis = analysis)
	}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), JobDetailUiState())

	fun toggleSaved() {
		val item = state.value.item ?: return
		viewModelScope.launch { jobs.setSaved(jobId, !item.saved) }
	}

	/** Has the model compare this listing with the CV; used for listings outside the search's shortlist. */
	fun analyze() {
		val job = state.value.item?.job ?: return
		if (analysisJob?.isActive == true) return
		analysisJob = viewModelScope.launch {
			analysis.value = Analysis.Running
			val profile = cv.current()?.profile
			val search = jobs.observeLatestResults().first()
			analysis.value = try {
				if (profile == null) throw LlmException.Failed("No analysed CV")
				jobs.saveMatch(jobId, scorer.score(job, profile, search?.request.orEmpty(), search?.criteria ?: SearchCriteria()))
				Analysis.Idle
			} catch (e: LlmException) {
				Analysis.Failed
			}
		}
	}
}
