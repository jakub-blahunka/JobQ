package com.jbsoft.jobq.data.remote

import com.jbsoft.jobq.domain.model.JobListing
import com.jbsoft.jobq.domain.repository.JobSource
import com.jbsoft.jobq.domain.repository.SourceQuery
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.http.GET
import retrofit2.http.Query
import java.time.OffsetDateTime

interface JobicyApi {
	@GET("api/v2/remote-jobs")
	suspend fun jobs(@Query("count") count: Int, @Query("tag") tag: String?): JobicyResponse
}

@Serializable
data class JobicyResponse(val jobs: List<JobicyJob> = emptyList())

// The feed is loose about types (numbers as strings, a string where a list is usual), so
// those fields are read as raw JSON.
@Serializable
data class JobicyJob(
	val id: JsonPrimitive,
	val url: String,
	val jobTitle: String = "",
	val companyName: String = "",
	val jobIndustry: JsonElement? = null,
	val jobType: JsonElement? = null,
	val jobGeo: String? = null,
	val jobLevel: String? = null,
	val jobDescription: String = "",
	val pubDate: String? = null,
	val salaryMin: JsonPrimitive? = null,
	val salaryMax: JsonPrimitive? = null,
	val salaryCurrency: String? = null,
	val salaryPeriod: String? = null,
)

/**
 * Jobicy's public remote-jobs API (https://jobi.cy/apidocs). The feed asks that Jobicy is
 * credited with a link to the source and that applicants are sent to the original posting.
 */
class JobicySource(private val api: JobicyApi) : JobSource {
	override val id = "jobicy"
	override val displayName = "Jobicy"
	override val homepage = "https://jobicy.com"

	override suspend fun fetch(query: SourceQuery): List<JobListing> = ioCall {
		// The tag must be 3 to 20 characters, and a single word finds far more than a phrase.
		val tag = query.keywords.firstOrNull { it.length in 3..20 }
		api.jobs(count = 50, tag = tag).jobs.map { it.toListing() }
	}

	private fun JobicyJob.toListing() = JobListing(
		id = "${this@JobicySource.id}:${id.content}",
		sourceId = this@JobicySource.id,
		sourceName = displayName,
		title = unescapeHtml(jobTitle),
		company = unescapeHtml(companyName),
		location = jobGeo?.let(::unescapeHtml)?.takeIf { it.isNotBlank() },
		salary = salaryText(),
		description = htmlToText(jobDescription),
		sourceUrl = url,
		remote = true,
		tags = (jobIndustry.strings() + jobType.strings() + listOfNotNull(jobLevel)).map(::unescapeHtml).filter { it.isNotBlank() },
		postedAtMillis = pubDate?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
	)

	private fun JobicyJob.salaryText(): String? {
		val min = salaryMin?.contentOrNull?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
		val max = salaryMax?.contentOrNull?.toDoubleOrNull()?.toLong()?.takeIf { it > 0 }
		val range = when {
			min != null && max != null && min != max -> "%,d–%,d".format(min, max)
			min != null -> "%,d".format(min)
			max != null -> "%,d".format(max)
			else -> return null
		}
		return listOfNotNull(salaryCurrency, range, salaryPeriod?.let { "/ $it" }).joinToString(" ")
	}

	private fun JsonElement?.strings(): List<String> = when (this) {
		is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
		is JsonPrimitive -> listOfNotNull(contentOrNull)
		else -> emptyList()
	}
}
