package com.jbsoft.jobq.domain.repository

import com.jbsoft.jobq.domain.model.CvDocument
import com.jbsoft.jobq.domain.model.JobListing
import com.jbsoft.jobq.domain.model.JobMatch
import com.jbsoft.jobq.domain.model.ModelStatus
import com.jbsoft.jobq.domain.model.RankedJob
import com.jbsoft.jobq.domain.model.SearchCriteria
import com.jbsoft.jobq.domain.model.SearchResults
import kotlinx.coroutines.flow.Flow

/** What to ask a source for. Sources apply whatever subset their feed supports. */
data class SourceQuery(
	/** Job-title phrases, e.g. "senior android developer". */
	val terms: List<String>,
	/** The distinctive single words of [terms], e.g. "android", for feeds that search by one tag. */
	val keywords: List<String>,
	val location: String?,
	val remoteOnly: Boolean,
)

/**
 * One provider of job listings. Implementations use a feed the provider publishes for
 * reuse (JSON API or RSS) and normalise it to [JobListing], keeping the original URL.
 */
interface JobSource {
	val id: String
	val displayName: String
	val homepage: String

	/** @throws java.io.IOException when the feed cannot be reached or parsed. */
	suspend fun fetch(query: SourceQuery): List<JobListing>
}

interface JobRepository {
	/** Results of the most recent search, best match first; null before the first search. */
	fun observeLatestResults(): Flow<SearchResults?>
	fun observeSaved(): Flow<List<RankedJob>>
	fun observeJob(jobId: String): Flow<RankedJob?>
	suspend fun setSaved(jobId: String, saved: Boolean)

	suspend fun storeListings(listings: List<JobListing>)
	/** Every listing kept on the device, including ones fetched by earlier searches. */
	suspend fun allListings(): List<JobListing>
	/** Drops unsaved listings fetched before [cutoffMillis]. */
	suspend fun prune(cutoffMillis: Long)

	/** Starts a new result set and returns its id. */
	suspend fun beginSearch(request: String, criteria: SearchCriteria, matches: Map<String, JobMatch>): Long
	/** Stores [match] for [jobId] in [searchId], or in the job's most recent result set when null. */
	suspend fun saveMatch(jobId: String, match: JobMatch, searchId: Long? = null)

	suspend fun lastFetchedMillis(sourceKey: String): Long?
	suspend fun markFetched(sourceKey: String, atMillis: Long)
}

/** A CV file as picked by the user: the original bytes and the text read from them. */
class ExtractedCv(val fileName: String, val pdf: ByteArray, val text: String)

interface CvTextExtractor {
	/** @throws java.io.IOException when the document cannot be read. */
	suspend fun extract(uri: String): ExtractedCv
}

/**
 * Holds the one CV the user has imported. Implementations keep it in app-private,
 * encrypted storage: it is the most sensitive data the app has.
 */
interface CvRepository {
	fun observe(): Flow<CvDocument?>
	suspend fun current(): CvDocument?
	/** Stores a newly imported CV, replacing any previous one and its original file. */
	suspend fun import(document: CvDocument, pdf: ByteArray)
	/** Updates the stored CV's details, e.g. after analysis. The original file is kept. */
	suspend fun update(document: CvDocument)
	/** Deletes the CV, its original file and everything derived from it. */
	suspend fun clear()
}

interface ModelRepository {
	fun observeModels(): Flow<List<ModelStatus>>
	fun download(modelId: String)
	suspend fun cancelDownload(modelId: String)
	suspend fun delete(modelId: String)
	suspend fun select(modelId: String)
	/** Absolute path of the active model's file, or null when none is ready. */
	suspend fun activeModelPath(): String?
}

interface SettingsRepository {
	val lastRequest: Flow<String>
	suspend fun setLastRequest(request: String)
}
