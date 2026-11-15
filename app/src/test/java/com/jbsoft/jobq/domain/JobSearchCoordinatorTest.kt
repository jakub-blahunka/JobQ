package com.jbsoft.jobq.domain

import com.jbsoft.jobq.domain.ai.JobScorer
import com.jbsoft.jobq.domain.ai.LlmEngine
import com.jbsoft.jobq.domain.ai.LlmException
import com.jbsoft.jobq.domain.ai.LlmRequest
import com.jbsoft.jobq.domain.ai.RequestInterpreter
import com.jbsoft.jobq.domain.model.CvDocument
import com.jbsoft.jobq.domain.model.CvProfile
import com.jbsoft.jobq.domain.model.JobListing
import com.jbsoft.jobq.domain.model.JobMatch
import com.jbsoft.jobq.domain.model.RankedJob
import com.jbsoft.jobq.domain.model.SearchCriteria
import com.jbsoft.jobq.domain.model.SearchResults
import com.jbsoft.jobq.domain.repository.CvRepository
import com.jbsoft.jobq.domain.repository.JobRepository
import com.jbsoft.jobq.domain.repository.JobSource
import com.jbsoft.jobq.domain.repository.SourceQuery
import com.jbsoft.jobq.domain.search.JobSearchCoordinator
import com.jbsoft.jobq.domain.search.KeywordRanker
import com.jbsoft.jobq.domain.search.SearchFailure
import com.jbsoft.jobq.domain.search.SearchState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class JobSearchCoordinatorTest {

	private val json = Json { ignoreUnknownKeys = true }
	private var clock = 1_000_000_000L

	private fun job(id: String, title: String) =
		JobListing("src:$id", "src", "Source", title, "Acme", null, null, "Kotlin and Compose", "https://example.com/$id", remote = true)

	/** Answers the request-interpretation prompt, then scores jobs; "Flaky" jobs get a truncated answer. */
	private class FakeEngine(var installed: Boolean = true, val location: String? = null) : LlmEngine {
		val scored = mutableListOf<String>()

		override suspend fun generate(request: LlmRequest): String {
			if (!installed) throw LlmException.NoModel()
			if (request.user.startsWith("Request:")) {
				return """{"queries":["android developer"],"skills":[],"location":${location?.let { "\"$it\"" }},"minSalary":null,"currency":null,"remote":true,"hybrid":false,"seniority":"any"}"""
			}
			val title = request.user.lineSequence().first { it.startsWith("Title: ") }.removePrefix("Title: ")
			scored += title
			if (title.contains("Flaky")) return """{"requirements":[{"name":"Kot"""
			if (title.contains("PHP")) {
				// Claims Kotlin is missing although the CV lists it, and over-scores a poor fit.
				return """{"requirements":[{"name":"PHP","met":false},{"name":"WordPress","met":false},{"name":"Kotlin experience","met":false}],"summary":"Good fit.","score":90}"""
			}
			return """{"requirements":[{"name":"Kotlin","met":true},{"name":"Android SDK","met":true}],"summary":"Good fit.","score":90}"""
		}
	}

	private class FakeSource(override val id: String, private val result: () -> List<JobListing>) : JobSource {
		override val displayName = "Source $id"
		override val homepage = "https://example.com"
		var calls = 0
		override suspend fun fetch(query: SourceQuery): List<JobListing> {
			calls++
			return result()
		}
	}

	private class FakeJobs : JobRepository {
		val listings = LinkedHashMap<String, JobListing>()
		val matches = LinkedHashMap<String, JobMatch>()
		val fetched = HashMap<String, Long>()

		override fun observeLatestResults(): Flow<SearchResults?> = flowOf(null)
		override fun observeSaved(): Flow<List<RankedJob>> = flowOf(emptyList())
		override fun observeJob(jobId: String): Flow<RankedJob?> = flowOf(null)
		override suspend fun setSaved(jobId: String, saved: Boolean) = Unit
		override suspend fun storeListings(listings: List<JobListing>) = listings.forEach { this.listings[it.id] = it }
		override suspend fun allListings() = listings.values.toList()
		override suspend fun prune(cutoffMillis: Long) = Unit
		override suspend fun beginSearch(request: String, criteria: SearchCriteria, matches: Map<String, JobMatch>): Long {
			this.matches.clear()
			this.matches.putAll(matches)
			return 1
		}
		override suspend fun saveMatch(jobId: String, match: JobMatch, searchId: Long?) { matches[jobId] = match }
		override suspend fun lastFetchedMillis(sourceKey: String) = fetched[sourceKey]
		override suspend fun markFetched(sourceKey: String, atMillis: Long) { fetched[sourceKey] = atMillis }
	}

	private class FakeCv(var document: CvDocument?) : CvRepository {
		override fun observe(): Flow<CvDocument?> = flowOf(document)
		override suspend fun current() = document
		override suspend fun import(document: CvDocument, pdf: ByteArray) { this.document = document }
		override suspend fun update(document: CvDocument) { this.document = document }
		override suspend fun clear() { document = null }
	}

	private val analysedCv = CvDocument("cv.pdf", "text", CvProfile(headline = "Android Developer", skills = listOf("Kotlin")), 0)

	private fun TestScope.coordinator(engine: FakeEngine, jobs: FakeJobs, cv: FakeCv, vararg sources: JobSource) = JobSearchCoordinator(
		scope = TestScope(UnconfinedTestDispatcher(testScheduler)),
		sources = sources.toList(),
		jobs = jobs,
		cv = cv,
		interpreter = RequestInterpreter(engine, json),
		scorer = JobScorer(engine, json),
		ranker = KeywordRanker(),
		now = { clock },
	)

	@Test
	fun `scores the shortlist with the model and keeps keyword estimates for the rest`() = runTest {
		val engine = FakeEngine()
		val jobs = FakeJobs()
		val listings = (1..12).map { job("$it", if (it == 3) "Lead Android Developer" else "Android Developer $it") } + job("x", "Accountant")
		val coordinator = coordinator(engine, jobs, FakeCv(analysedCv), FakeSource("a") { listings })

		coordinator.search("android jobs, remote")
		advanceUntilIdle()

		assertEquals(SearchState.Finished(found = 12, unreachableSources = emptyList()), coordinator.state.value)
		assertEquals(JobSearchCoordinator.AI_SHORTLIST, engine.scored.size)
		assertEquals(JobSearchCoordinator.AI_SHORTLIST, jobs.matches.values.count { it.aiScored })
		assertEquals(12 - JobSearchCoordinator.AI_SHORTLIST, jobs.matches.values.count { !it.aiScored })
		assertFalse("unrelated job must not be a result", "src:x" in jobs.matches)
	}

	@Test
	fun `unmet requirements pull an inflated score down and the CV overrides a wrong verdict`() = runTest {
		val jobs = FakeJobs()
		val coordinator = coordinator(
			FakeEngine(), jobs, FakeCv(analysedCv),
			FakeSource("a") { listOf(job("1", "Android Developer"), job("2", "PHP Developer with Android exposure")) },
		)

		coordinator.search("android")
		advanceUntilIdle()

		val good = jobs.matches.getValue("src:1")
		assertEquals(96, good.score)
		assertEquals(listOf("Kotlin", "Android SDK"), good.strengths)

		val poor = jobs.matches.getValue("src:2")
		assertEquals(56, poor.score)
		assertEquals(listOf("Kotlin experience"), poor.strengths)
		assertEquals(listOf("PHP", "WordPress"), poor.missing)
	}

	@Test
	fun `a job in the wrong place loses points and says so`() = runTest {
		val jobs = FakeJobs()
		val onSite = job("1", "Android Developer").copy(location = "London", remote = false)
		val coordinator = coordinator(FakeEngine(location = "vienna"), jobs, FakeCv(analysedCv), FakeSource("a") { listOf(onSite) })

		coordinator.search("android jobs in vienna")
		advanceUntilIdle()

		val match = jobs.matches.getValue("src:1")
		assertEquals(81, match.score)
		assertEquals(listOf("Based in London, not Vienna"), match.missing)
	}

	@Test
	fun `description-only matches are listed but not sent to the model when titles match`() = runTest {
		val engine = FakeEngine()
		val jobs = FakeJobs()
		val bodyOnly = job("2", "Backend Engineer").copy(description = "Some Android knowledge is a plus.")
		val coordinator = coordinator(engine, jobs, FakeCv(analysedCv), FakeSource("a") { listOf(job("1", "Android Developer"), bodyOnly) })

		coordinator.search("android")
		advanceUntilIdle()

		assertEquals(listOf("Android Developer"), engine.scored)
		assertFalse(jobs.matches.getValue("src:2").aiScored)
	}

	@Test
	fun `an unreachable source is reported and the others still produce results`() = runTest {
		val jobs = FakeJobs()
		val coordinator = coordinator(
			FakeEngine(), jobs, FakeCv(analysedCv),
			FakeSource("down") { throw IOException("offline") },
			FakeSource("up") { listOf(job("1", "Android Developer")) },
		)

		coordinator.search("android")
		advanceUntilIdle()

		assertEquals(SearchState.Finished(found = 1, unreachableSources = listOf("Source down")), coordinator.state.value)
		assertTrue(jobs.matches.getValue("src:1").aiScored)
	}

	@Test
	fun `a truncated model answer leaves that job with its keyword estimate`() = runTest {
		val jobs = FakeJobs()
		val coordinator = coordinator(
			FakeEngine(), jobs, FakeCv(analysedCv),
			FakeSource("a") { listOf(job("1", "Android Developer"), job("2", "Flaky Android Developer")) },
		)

		coordinator.search("android")
		advanceUntilIdle()

		assertTrue(coordinator.state.value is SearchState.Finished)
		assertTrue(jobs.matches.getValue("src:1").aiScored)
		assertFalse(jobs.matches.getValue("src:2").aiScored)
	}

	@Test
	fun `a recently refreshed source is not fetched again until the interval has passed`() = runTest {
		val source = FakeSource("a") { listOf(job("1", "Android Developer")) }
		val coordinator = coordinator(FakeEngine(), FakeJobs(), FakeCv(analysedCv), source)

		coordinator.search("android")
		advanceUntilIdle()
		coordinator.search("android")
		advanceUntilIdle()
		assertEquals(1, source.calls)

		clock += JobSearchCoordinator.REFRESH_INTERVAL_MILLIS + 1
		coordinator.search("android")
		advanceUntilIdle()
		assertEquals(2, source.calls)
	}

	@Test
	fun `fails without a CV or without a model`() = runTest {
		val noCv = coordinator(FakeEngine(), FakeJobs(), FakeCv(null))
		noCv.search("android")
		advanceUntilIdle()
		assertEquals(SearchState.Failed(SearchFailure.NoCv), noCv.state.value)

		val noModel = coordinator(FakeEngine(installed = false), FakeJobs(), FakeCv(analysedCv))
		noModel.search("android")
		advanceUntilIdle()
		assertEquals(SearchState.Failed(SearchFailure.NoModel), noModel.state.value)
	}
}
