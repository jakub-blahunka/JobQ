package com.jbsoft.jobq.domain.model

import kotlinx.serialization.Serializable

/** What the local model extracted from the CV. */
@Serializable
data class CvProfile(
	val name: String = "",
	val headline: String = "",
	val summary: String = "",
	val yearsExperience: Int = 0,
	val skills: List<String> = emptyList(),
	val roles: List<CvRole> = emptyList(),
	val education: List<String> = emptyList(),
	val languages: List<String> = emptyList(),
)

@Serializable
data class CvRole(
	val title: String = "",
	val company: String = "",
	val period: String = "",
)

data class CvDocument(
	val fileName: String,
	val text: String,
	/** Null until the model has analysed the text. */
	val profile: CvProfile?,
	val importedAtMillis: Long,
)
