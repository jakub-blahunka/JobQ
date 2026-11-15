package com.jbsoft.jobq.domain.ai

import com.jbsoft.jobq.domain.model.CvProfile
import com.jbsoft.jobq.domain.model.SearchCriteria
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Turns a natural-language request ("Senior Android jobs in Vienna, €120k+, remote") into [SearchCriteria]. */
class RequestInterpreter(private val engine: LlmEngine, private val json: Json) {

	/**
	 * Falls back to [heuristicCriteria] when the model's reply is unusable, so a search
	 * always has something to go on.
	 *
	 * @throws LlmException.NoModel when no model is installed.
	 */
	suspend fun interpret(request: String, profile: CvProfile?): SearchCriteria {
		val fallback = heuristicCriteria(request, profile)
		if (request.isBlank()) return fallback

		val candidate = profile?.let {
			"\nThe candidate is a ${it.headline.ifBlank { "professional" }} with skills: ${it.skills.take(12).joinToString()}.\n"
		}.orEmpty()
		val reply = try {
			engine.generate(
				LlmRequest(
					system = "You turn job-search requests into structured search criteria. Reply with JSON only.",
					user = """
						|Request: "$request"
						|$candidate
						|Return JSON with these fields:
						|queries: 2 to 4 short job titles to search for, in English, lowercase, e.g. "android developer"
						|skills: up to 8 skills or technologies the request implies
						|location: the city or country asked for, or null
						|minSalary: the minimum yearly salary as a whole number (120k means 120000), or null
						|currency: the salary's ISO currency code such as EUR, or null
						|remote: true if remote work is wanted or acceptable
						|hybrid: true if hybrid work is wanted or acceptable
						|seniority: the level asked for
					""".trimMargin(),
					maxTokens = 300,
					temperature = 0.1f,
					grammar = GRAMMAR,
				)
			)
		} catch (e: LlmException.NoModel) {
			throw e
		} catch (e: LlmException) {
			return fallback
		}

		val parsed = try {
			json.decodeFromString<SearchCriteria>(reply)
		} catch (e: SerializationException) {
			return fallback
		}
		val queries = parsed.queries.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
		return parsed.copy(
			queries = queries.ifEmpty { fallback.queries },
			skills = parsed.skills.map { it.trim() }.filter { it.isNotEmpty() },
			location = parsed.location?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
				?.split(' ')?.joinToString(" ") { word -> word.replaceFirstChar(Char::uppercase) },
			minSalary = parsed.minSalary?.takeIf { it > 0 } ?: fallback.minSalary,
			currency = parsed.currency?.trim()?.uppercase()?.takeIf { it.length == 3 } ?: fallback.currency,
			seniority = parsed.seniority?.takeIf { it != "any" },
		)
	}

	private companion object {
		val GRAMMAR = JsonShape.Obj(
			"queries" to JsonShape.ListOf(JsonShape.Text, 4),
			"skills" to JsonShape.ListOf(JsonShape.Text, 8),
			"location" to JsonShape.Nullable(JsonShape.Text),
			"minSalary" to JsonShape.Nullable(JsonShape.Integer),
			"currency" to JsonShape.Nullable(JsonShape.Text),
			"remote" to JsonShape.Bool,
			"hybrid" to JsonShape.Bool,
			"seniority" to JsonShape.OneOf(listOf("any", "junior", "mid", "senior", "lead")),
		).toGbnf()
	}
}

private val SALARY = Regex("""([€$£])?\s*(\d{2,3})(?:[.,]?(\d{3})|\s?k)\b\s*(€|eur|usd|gbp|chf)?""", RegexOption.IGNORE_CASE)
private val LOCATION = Regex("""\b(?:in|near|around)\s+(\p{Lu}[\p{L}.-]+(?:\s\p{Lu}[\p{L}.-]+)?)""")
private val NOISE = Regex(
	"""\b(find|show|search|get|give|me|for|jobs?|positions?|roles?|vacanc(?:y|ies)|openings?|please|some|any|a|an|the|with|and|or|i|want|need|looking|remote|hybrid|on-?site|full-?time|part-?time)\b""",
	RegexOption.IGNORE_CASE,
)

/** Model-free reading of a request: good enough to search with, and the fallback when the model fails. */
fun heuristicCriteria(request: String, profile: CvProfile?): SearchCriteria {
	val salary = SALARY.find(request)
	val minSalary = salary?.let { m ->
		val head = m.groupValues[2].toInt()
		val tail = m.groupValues[3]
		if (tail.isNotEmpty()) head * 1000 + tail.toInt() else head * 1000
	}
	val currency = salary?.let { m ->
		when ((m.groupValues[1] + m.groupValues[4]).lowercase()) {
			"€", "eur" -> "EUR"
			"$", "usd" -> "USD"
			"£", "gbp" -> "GBP"
			"chf" -> "CHF"
			else -> null
		}
	}
	val location = LOCATION.find(request)?.groupValues?.get(1)

	// What is left after removing the salary, the location and filler words is the job title.
	var title = request
	salary?.let { title = title.replace(it.value, " ") }
	location?.let { title = title.replace(Regex("""\b(?:in|near|around)\s+${Regex.escape(it)}"""), " ") }
	title = title.replace(NOISE, " ").replace(Regex("""[^\p{L}\p{N}+#. -]"""), " ").lowercase()
		.split(' ').map { it.trim('.', '-') }.filter { word -> word.any(Char::isLetterOrDigit) }.joinToString(" ")

	val seniority = listOf("junior", "senior", "lead", "mid").firstOrNull { Regex("\\b$it\\b", RegexOption.IGNORE_CASE).containsMatchIn(request) }
	val queries = listOfNotNull(
		title.takeIf { it.length >= 3 },
		profile?.headline?.lowercase()?.takeIf { it.isNotBlank() && title.length < 3 },
	)
	return SearchCriteria(
		queries = queries,
		skills = emptyList(),
		location = location,
		minSalary = minSalary,
		currency = currency,
		remote = request.contains("remote", ignoreCase = true),
		hybrid = request.contains("hybrid", ignoreCase = true),
		seniority = seniority,
	)
}
