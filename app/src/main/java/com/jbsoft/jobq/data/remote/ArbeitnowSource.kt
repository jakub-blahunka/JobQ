package com.jbsoft.jobq.data.remote

import com.jbsoft.jobq.domain.model.JobListing
import com.jbsoft.jobq.domain.repository.JobSource
import com.jbsoft.jobq.domain.repository.SourceQuery
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Query

interface ArbeitnowApi {
	@GET("api/job-board-api")
	suspend fun jobs(@Query("page") page: Int): ArbeitnowPage
}

@Serializable
data class ArbeitnowPage(val data: List<ArbeitnowJob> = emptyList())

@Serializable
data class ArbeitnowJob(
	val slug: String,
	@SerialName("company_name") val companyName: String = "",
	val title: String = "",
	val description: String = "",
	val remote: Boolean = false,
	val url: String,
	val tags: List<String> = emptyList(),
	@SerialName("job_types") val jobTypes: List<String> = emptyList(),
	val location: String? = null,
	@SerialName("created_at") val createdAt: Long? = null,
)

/**
 * Arbeitnow's free public job-board API: mostly German and other European vacancies.
 * The feed has no search parameter, so the newest pages are fetched whole and filtered on the device.
 * Terms: https://www.arbeitnow.com/blog/job-board-api (link back to the listing).
 */
class ArbeitnowSource(private val api: ArbeitnowApi) : JobSource {
	override val id = "arbeitnow"
	override val displayName = "Arbeitnow"
	override val homepage = "https://www.arbeitnow.com"

	override suspend fun fetch(query: SourceQuery): List<JobListing> = ioCall {
		(1..PAGES).flatMap { page -> api.jobs(page).data }.map { it.toListing() }
	}

	private fun ArbeitnowJob.toListing() = JobListing(
		id = "$id:$slug",
		sourceId = id,
		sourceName = displayName,
		title = unescapeHtml(title),
		company = unescapeHtml(companyName),
		location = location?.takeIf { it.isNotBlank() },
		salary = null,
		description = htmlToText(description),
		sourceUrl = url,
		remote = remote,
		tags = tags + jobTypes,
		postedAtMillis = createdAt?.let { it * 1000 },
	)

	private companion object {
		/** 300 listings per page, newest first. */
		const val PAGES = 2
	}
}
