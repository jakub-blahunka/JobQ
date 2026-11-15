package com.jbsoft.jobq.domain.model

import kotlinx.serialization.Serializable

/** A natural-language job-search request, structured. */
@Serializable
data class SearchCriteria(
	/** Short job-title phrases to search for, e.g. "android developer". */
	val queries: List<String> = emptyList(),
	val skills: List<String> = emptyList(),
	val location: String? = null,
	/** Yearly, in [currency]. */
	val minSalary: Int? = null,
	val currency: String? = null,
	val remote: Boolean = false,
	val hybrid: Boolean = false,
	val seniority: String? = null,
)
