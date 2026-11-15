package com.jbsoft.jobq.domain.search

import com.jbsoft.jobq.domain.ai.JobScorer
import com.jbsoft.jobq.domain.ai.LlmException
import com.jbsoft.jobq.domain.ai.RequestInterpreter
import com.jbsoft.jobq.domain.model.JobMatch
import com.jbsoft.jobq.domain.repository.CvRepository
import com.jbsoft.jobq.domain.repository.JobRepository
import com.jbsoft.jobq.domain.repository.JobSource
import com.jbsoft.jobq.domain.repository.SourceQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.math.roundToInt

sealed interface SearchState {
	data object Idle : SearchState
	data object Understanding : SearchState
	data object Fetching : SearchState
	data class Ranking(val done: Int, val total: Int) : SearchState
	/** [unreachableSources] could not be refreshed; results then come from what was already on the device. */
	data class Finished(val found: Int, val unreachableSources: List<String>) : SearchState
	data class Failed(val reason: SearchFailure) : SearchState
}

enum class SearchFailure { NoModel, NoCv, Unexpected }

/**
 * Runs a job search end to end: understand the request, refresh listings from every
 * source, shortlist by keyword, then have the local model score the shortlist.
 *
 * Runs in [scope] rather than a screen's scope, so a search survives navigation.
 */
class JobSearchCoordinator(
	private val scope: CoroutineScope,
	private val sources: List<JobSource>,
	private val jobs: JobRepository,
	private val cv: CvRepository,
	private val interpreter: RequestInterpreter,
	private val scorer: JobScorer,
	private val ranker: KeywordRanker,
	private val now: () -> Long = System::currentTimeMillis,
) {
	private val _state = MutableStateFlow<SearchState>(SearchState.Idle)
	val state: StateFlow<SearchState> = _state.asStateFlow()

	private var running: Job? = null

	fun search(request: String) {
		running?.cancel()
		running = scope.launch {
			try {
				run(request.trim())
			} catch (e: CancellationException) {
				throw e
			} catch (e: LlmException.NoModel) {
				_state.value = SearchState.Failed(SearchFailure.NoModel)
			} catch (e: Exception) {
				_state.value = SearchState.Failed(SearchFailure.Unexpected)
			}
		}
	}

	fun cancel() {
		running?.cancel()
		running = null
		_state.value = SearchState.Idle
	}

	private suspend fun run(request: String) {
		val profile = cv.current()?.profile
		if (profile == null) {
			_state.value = SearchState.Failed(SearchFailure.NoCv)
			return
		}

		_state.value = SearchState.Understanding
		val criteria = interpreter.interpret(request, profile)

		_state.value = SearchState.Fetching
		val unreachable = refresh(
			SourceQuery(
				terms = criteria.queries,
				keywords = KeywordRanker.keywords(criteria.queries),
				location = criteria.location,
				remoteOnly = criteria.remote && !criteria.hybrid && criteria.location == null,
			)
		)
		jobs.prune(now() - RETENTION_MILLIS)

		val ranked = ranker.rank(jobs.allListings(), criteria, profile).take(MAX_RESULTS)
		val searchId = jobs.beginSearch(
			request,
			criteria,
			ranked.associate { it.job.id to JobMatch((it.relevance * 100).roundToInt(), "", emptyList(), emptyList(), aiScored = false) },
		)

		// A term buried in a description is weak evidence, so the model's time goes to title
		// matches. The rest stay in the results and can be analysed one by one.
		val shortlist = ranked.filter { it.titleHit }.take(AI_SHORTLIST).ifEmpty { ranked.take(FALLBACK_SHORTLIST) }
		shortlist.forEachIndexed { index, candidate ->
			_state.value = SearchState.Ranking(index, shortlist.size)
			try {
				jobs.saveMatch(candidate.job.id, scorer.score(candidate.job, profile, request, criteria), searchId)
			} catch (e: LlmException.NoModel) {
				throw e
			} catch (e: LlmException) {
				// One unusable answer should not sink the search; the keyword estimate stays.
			}
		}
		_state.value = SearchState.Finished(ranked.size, unreachable)
	}

	/** Refreshes every source that is due, in parallel. Returns the names of sources that failed. */
	private suspend fun refresh(query: SourceQuery): List<String> = supervisorScope {
		sources.map { source ->
			async {
				val key = source.id + "|" + query.keywords.firstOrNull().orEmpty()
				val last = jobs.lastFetchedMillis(key)
				if (last != null && now() - last < REFRESH_INTERVAL_MILLIS) return@async null
				try {
					jobs.storeListings(source.fetch(query))
					jobs.markFetched(key, now())
					null
				} catch (e: CancellationException) {
					throw e
				} catch (e: Exception) {
					source.displayName
				}
			}
		}.awaitAll().filterNotNull()
	}

	companion object {
		/** Listings the model scores per search. Each takes tens of seconds on a phone. */
		const val AI_SHORTLIST = 8
		/** Listings scored when no title matches the search at all. */
		const val FALLBACK_SHORTLIST = 3
		const val MAX_RESULTS = 40
		/** Feeds change about hourly, and their operators ask not to be polled more often. */
		const val REFRESH_INTERVAL_MILLIS = 60 * 60 * 1000L
		const val RETENTION_MILLIS = 30 * 24 * 60 * 60 * 1000L
	}
}
