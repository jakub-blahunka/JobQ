package com.jbsoft.jobq.domain.ai

import com.jbsoft.jobq.domain.model.CvProfile
import com.jbsoft.jobq.domain.model.JobListing
import com.jbsoft.jobq.domain.model.JobMatch
import com.jbsoft.jobq.domain.model.SearchCriteria
import com.jbsoft.jobq.domain.search.KeywordRanker
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** Compares one vacancy with the candidate's CV and preferences using the local model. */
class JobScorer(private val engine: LlmEngine, private val json: Json) {

	/** @throws LlmException when the model is missing or its reply is unusable. */
	suspend fun score(job: JobListing, profile: CvProfile, request: String, criteria: SearchCriteria): JobMatch {
		val reply = engine.generate(
			LlmRequest(
				system = "You are a careful recruiter. Judge how well a candidate fits a job using only the information given. Be strict: do not assume skills the CV does not mention. Reply with JSON only.",
				user = """
					|CANDIDATE
					|${candidateBlock(profile)}
					|Looking for: ${wantsLine(request, criteria)}
					|
					|JOB
					|Title: ${job.title}
					|Company: ${job.company}
					|Location: ${job.location ?: "not stated"}${if (job.remote) " (remote)" else ""}
					|Salary: ${job.salary ?: "not stated"}
					|Description: ${job.description.take(DESCRIPTION_CHARS)}
					|
					|Return JSON with these fields:
					|requirements: the 3 to 6 most important skills or qualifications this job asks for, each with "name" (a few words) and "met" (true only if the candidate's skills or roles show it)
					|summary: one sentence saying whether this job suits the candidate, and why
					|score: 0 to 100, where 90+ is an excellent fit, 70 is a good fit with gaps, 40 is a weak fit and below 20 is a different profession
				""".trimMargin(),
				maxTokens = 300,
				temperature = 0.2f,
				grammar = GRAMMAR,
			)
		)
		val parsed = try {
			json.decodeFromString<Reply>(reply)
		} catch (e: SerializationException) {
			throw LlmException.Failed("The model's answer was cut off")
		}
		val known = knownTerms(profile)
		val requirements = parsed.requirements
			.map { it.copy(name = it.name.trim()) }
			.filter { it.name.isNotEmpty() }
			.distinctBy { it.name.lowercase() }
			// The model sometimes calls a skill missing that the CV plainly lists; the CV wins.
			.map { it.copy(met = it.met || it.isCoveredBy(known)) }
		val wrongPlace = wrongPlace(job, criteria)
		val score = blendedScore(parsed.score.coerceIn(0, 100), requirements)
		return JobMatch(
			score = if (wrongPlace != null) (score - WRONG_PLACE_PENALTY).coerceAtLeast(0) else score,
			summary = parsed.summary.trim(),
			strengths = requirements.filter { it.met }.map { it.name },
			missing = requirements.filterNot { it.met }.map { it.name } + listOfNotNull(wrongPlace),
			aiScored = true,
		)
	}

	/**
	 * Small models are poor at putting one number on a fit: they give a design job 60 and an
	 * exact match 60. They are much better at the itemised judgement, so the share of
	 * requirements met carries most of the score and the model's own number adjusts it.
	 */
	private fun blendedScore(modelScore: Int, requirements: List<Requirement>): Int {
		if (requirements.size < MIN_REQUIREMENTS_TO_BLEND) return modelScore
		val metShare = requirements.count { it.met }.toDouble() / requirements.size
		return (REQUIREMENTS_WEIGHT * metShare * 100 + (1 - REQUIREMENTS_WEIGHT) * modelScore).roundToInt()
	}

	/** Where a job is can be checked directly, which is more dependable than asking the model. */
	private fun wrongPlace(job: JobListing, criteria: SearchCriteria): String? {
		val wanted = criteria.location ?: return null
		val place = job.location ?: return null
		if (place.contains(wanted, ignoreCase = true) || (job.remote && criteria.remote)) return null
		return "Based in $place, not $wanted"
	}

	private fun knownTerms(profile: CvProfile): Set<String> =
		(profile.skills + profile.headline + profile.roles.map { it.title } + profile.languages)
			.flatMap(KeywordRanker::tokenize).toSet()

	private fun Requirement.isCoveredBy(known: Set<String>): Boolean {
		val terms = KeywordRanker.tokenize(name) - FILLER
		return terms.isNotEmpty() && known.containsAll(terms)
	}

	private fun candidateBlock(profile: CvProfile) = buildString {
		appendLine("Headline: ${profile.headline.ifBlank { "not stated" }}")
		appendLine("Experience: ${profile.yearsExperience} years")
		appendLine("Skills: ${profile.skills.take(20).joinToString()}")
		val roles = profile.roles.take(3).joinToString("; ") { "${it.title} at ${it.company} (${it.period})" }
		if (roles.isNotEmpty()) appendLine("Recent roles: $roles")
		if (profile.languages.isNotEmpty()) append("Languages: ${profile.languages.joinToString()}")
	}.trimEnd()

	private fun wantsLine(request: String, criteria: SearchCriteria) = buildString {
		append(request.ifBlank { criteria.queries.joinToString() })
		criteria.minSalary?.let { append(" | minimum salary $it ${criteria.currency.orEmpty()}".trimEnd()) }
		criteria.location?.let { append(" | location $it") }
		if (criteria.remote) append(" | remote ok")
		if (criteria.hybrid) append(" | hybrid ok")
	}

	@Serializable
	private data class Requirement(val name: String = "", val met: Boolean = false)

	@Serializable
	private data class Reply(
		val requirements: List<Requirement> = emptyList(),
		val summary: String = "",
		val score: Int = 0,
	)

	private companion object {
		const val DESCRIPTION_CHARS = 1500
		const val MIN_REQUIREMENTS_TO_BLEND = 2
		const val REQUIREMENTS_WEIGHT = 0.6
		const val WRONG_PLACE_PENALTY = 15

		/** Words in a requirement's name that say nothing about what the skill is. */
		val FILLER = setOf("experience", "with", "knowledge", "of", "in", "and", "skills", "strong", "proficiency", "familiarity", "development")

		// The itemised judgement comes before the score so the number is conditioned on it.
		val GRAMMAR = JsonShape.Obj(
			"requirements" to JsonShape.ListOf(JsonShape.Obj("name" to JsonShape.Text, "met" to JsonShape.Bool), 6),
			"summary" to JsonShape.Text,
			"score" to JsonShape.Percent,
		).toGbnf()
	}
}
