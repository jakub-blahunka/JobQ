package com.jbsoft.jobq.data.local

import com.jbsoft.jobq.domain.model.JobListing
import com.jbsoft.jobq.domain.model.JobMatch
import com.jbsoft.jobq.domain.model.RankedJob
import com.jbsoft.jobq.domain.model.SearchCriteria
import com.jbsoft.jobq.domain.model.SearchResults
import com.jbsoft.jobq.domain.repository.JobRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class JobRepositoryImpl(
	private val dao: JobDao,
	private val json: Json,
	private val now: () -> Long = System::currentTimeMillis,
) : JobRepository {

	@OptIn(ExperimentalCoroutinesApi::class)
	override fun observeLatestResults(): Flow<SearchResults?> =
		dao.observeLatestSearch().distinctUntilChanged().flatMapLatest { search ->
			if (search == null) {
				flowOf(null)
			} else {
				val criteria = runCatching { json.decodeFromString<SearchCriteria>(search.criteriaJson) }.getOrDefault(SearchCriteria())
				dao.observeResults(search.id).map { rows -> SearchResults(search.request, criteria, rows.map { it.toDomain() }) }
			}
		}

	override fun observeSaved(): Flow<List<RankedJob>> = dao.observeSaved().map { rows -> rows.map { it.toDomain() } }

	override fun observeJob(jobId: String): Flow<RankedJob?> = dao.observeJob(jobId).map { it?.toDomain() }

	override suspend fun setSaved(jobId: String, saved: Boolean) = dao.setSaved(jobId, saved)

	override suspend fun storeListings(listings: List<JobListing>) {
		val fetchedAt = now()
		dao.store(listings.map { it.toEntity(fetchedAt) })
	}

	override suspend fun allListings(): List<JobListing> = dao.all().map { it.toDomain() }

	override suspend fun prune(cutoffMillis: Long) = dao.prune(cutoffMillis)

	override suspend fun beginSearch(request: String, criteria: SearchCriteria, matches: Map<String, JobMatch>): Long {
		val searchId = dao.insertSearch(SearchEntity(request = request, criteriaJson = json.encodeToString(criteria), createdAt = now()))
		dao.upsertMatches(matches.map { (jobId, match) -> match.toEntity(searchId, jobId) })
		dao.deleteMatchesBefore(searchId)
		return searchId
	}

	override suspend fun saveMatch(jobId: String, match: JobMatch, searchId: Long?) {
		val target = searchId ?: dao.latestSearchIdFor(jobId) ?: return
		dao.upsertMatches(listOf(match.toEntity(target, jobId)))
	}

	override suspend fun lastFetchedMillis(sourceKey: String): Long? = dao.lastFetched(sourceKey)

	override suspend fun markFetched(sourceKey: String, atMillis: Long) = dao.markFetched(SourceFetchEntity(sourceKey, atMillis))
}

private fun JobListing.toEntity(fetchedAt: Long) = JobEntity(
	id = id, sourceId = sourceId, sourceName = sourceName, title = title, company = company, location = location,
	salary = salary, description = description, sourceUrl = sourceUrl, remote = remote, tags = tags,
	postedAt = postedAtMillis, fetchedAt = fetchedAt,
)

private fun JobEntity.toDomain() = JobListing(
	id = id, sourceId = sourceId, sourceName = sourceName, title = title, company = company, location = location,
	salary = salary, description = description, sourceUrl = sourceUrl, remote = remote, tags = tags,
	postedAtMillis = postedAt,
)

private fun JobMatch.toEntity(searchId: Long, jobId: String) = MatchEntity(searchId, jobId, score, summary, strengths, missing, aiScored)

private fun JobWithMatch.toDomain() = RankedJob(
	job = job.toDomain(),
	match = match?.let { JobMatch(it.score, it.summary, it.strengths, it.missing, it.aiScored) },
	saved = job.saved,
)
