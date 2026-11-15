package com.jbsoft.jobq.domain.ai

import com.jbsoft.jobq.domain.model.CvProfile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Extracts a structured [CvProfile] from CV text with the local model. */
class CvAnalyzer(private val engine: LlmEngine, private val json: Json) {

	/** @throws LlmException when no model is installed or the model's reply is unusable. */
	suspend fun analyze(cvText: String): CvProfile {
		// A long CV may not fit the context window; retry with less of it rather than fail.
		for (limit in TEXT_LIMITS) {
			try {
				return analyzeOnce(cvText.take(limit))
			} catch (e: LlmException.PromptTooLong) {
				if (limit == TEXT_LIMITS.last()) throw e
			}
		}
		throw LlmException.PromptTooLong()
	}

	private suspend fun analyzeOnce(text: String): CvProfile {
		val reply = engine.generate(
			LlmRequest(
				system = "You extract structured data from CVs. Use only facts stated in the CV. Reply with JSON only.",
				user = """
					|CV:
					|<<<
					|$text
					|>>>
					|
					|Return JSON with these fields:
					|name: the person's full name
					|headline: their current or most recent job title
					|summary: two sentences describing their professional profile
					|yearsExperience: total years of professional experience, as a whole number
					|skills: every skill, technology and tool the CV names, at most $MAX_SKILLS; do not add any it does not mention
					|roles: every job the CV lists, most recent first, each with title, company and period
					|education: degrees and certifications
					|languages: spoken languages
				""".trimMargin(),
				maxTokens = 900,
				temperature = 0.1f,
				grammar = GRAMMAR,
			)
		)
		return try {
			json.decodeFromString<CvProfile>(reply).cleaned()
		} catch (e: SerializationException) {
			throw LlmException.Failed("The model's CV analysis was cut off. Try a larger model.")
		}
	}

	private fun CvProfile.cleaned() = copy(
		name = name.trim(),
		headline = headline.trim(),
		summary = summary.trim(),
		yearsExperience = yearsExperience.coerceIn(0, 60),
		skills = skills.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
		roles = roles.filter { it.title.isNotBlank() },
		education = education.map { it.trim() }.filter { it.isNotEmpty() },
		languages = languages.map { it.trim() }.filter { it.isNotEmpty() },
	)

	private companion object {
		const val MAX_SKILLS = 25
		// Characters, not tokens: roughly 1700, 1000 and 550 tokens of English.
		val TEXT_LIMITS = listOf(6500, 3800, 2000)

		val GRAMMAR = JsonShape.Obj(
			"name" to JsonShape.Text,
			"headline" to JsonShape.Text,
			"summary" to JsonShape.Text,
			"yearsExperience" to JsonShape.Integer,
			"skills" to JsonShape.ListOf(JsonShape.Text, MAX_SKILLS),
			"roles" to JsonShape.ListOf(
				JsonShape.Obj("title" to JsonShape.Text, "company" to JsonShape.Text, "period" to JsonShape.Text),
				maxItems = 6,
			),
			"education" to JsonShape.ListOf(JsonShape.Text, 4),
			"languages" to JsonShape.ListOf(JsonShape.Text, 6),
		).toGbnf()
	}
}
