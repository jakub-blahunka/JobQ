package com.jbsoft.jobq.domain.model

/** A vacancy normalised from any [com.jbsoft.jobq.domain.repository.JobSource]. */
data class JobListing(
	/** Stable across refreshes: `<sourceId>:<id at the source>`. */
	val id: String,
	val sourceId: String,
	val sourceName: String,
	val title: String,
	val company: String,
	val location: String?,
	val salary: String?,
	/** Plain text; HTML is stripped when the listing is normalised. */
	val description: String,
	/** The original posting. Always shown to the user and used for "apply". */
	val sourceUrl: String,
	val remote: Boolean,
	val tags: List<String> = emptyList(),
	val postedAtMillis: Long? = null,
)

/** How well a listing fits the candidate. */
data class JobMatch(
	/** 0–100. */
	val score: Int,
	val summary: String,
	/** Requirements of the job that the candidate meets. */
	val strengths: List<String>,
	/** Requirements of the job that the candidate does not meet. */
	val missing: List<String>,
	/** False while the score is only the keyword estimate, before the model has judged it. */
	val aiScored: Boolean,
)

data class RankedJob(
	val job: JobListing,
	val match: JobMatch?,
	val saved: Boolean,
)

data class SearchResults(
	val request: String,
	val criteria: SearchCriteria,
	val jobs: List<RankedJob>,
)
