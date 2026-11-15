package com.jbsoft.jobq.domain.search

import com.jbsoft.jobq.domain.model.CvProfile
import com.jbsoft.jobq.domain.model.JobListing
import com.jbsoft.jobq.domain.model.SearchCriteria
import kotlin.math.ln

/**
 * Fast keyword relevance, used to pick which listings are worth the model's time: scoring
 * one listing with an on-device model takes many seconds, so only the best few get it.
 */
class KeywordRanker {

	data class Scored(
		val job: JobListing,
		/** 0..1 */
		val relevance: Double,
		/** A search term appears in the job title, not just somewhere in the description. */
		val titleHit: Boolean,
	)

	/** Returns relevant listings, best first. Listings that match no search term are left out. */
	fun rank(jobs: List<JobListing>, criteria: SearchCriteria, profile: CvProfile?): List<Scored> {
		val queryTerms = keywords(criteria.queries).toSet()
		val skillTerms = (criteria.skills + profile?.skills.orEmpty().take(15)).flatMap(::tokenize).toSet() - queryTerms - GENERIC
		if (queryTerms.isEmpty() && skillTerms.isEmpty()) return emptyList()

		val docs = jobs.map { job ->
			Doc(job, tokenize(job.title).toSet(), tokenize(job.tags.joinToString(" ") + " " + job.description).toSet())
		}
		// Rare terms say more than common ones: "android" should outweigh "software".
		val df = HashMap<String, Int>()
		for (doc in docs) for (term in queryTerms + skillTerms) {
			if (term in doc.title || term in doc.body) df.merge(term, 1, Int::plus)
		}
		fun idf(term: String) = ln(1.0 + docs.size / (1.0 + (df[term] ?: 0)))

		val best = queryTerms.sumOf { idf(it) * TITLE_WEIGHT } + skillTerms.sumOf { idf(it) * SKILL_WEIGHT }
		if (best <= 0.0) return emptyList()

		return docs.mapNotNull { doc ->
			var hits = 0.0
			var queryHit = queryTerms.isEmpty()
			var titleHit = false
			for (term in queryTerms) {
				when (term) {
					in doc.title -> { hits += idf(term) * TITLE_WEIGHT; queryHit = true; titleHit = true }
					in doc.body -> { hits += idf(term) * BODY_WEIGHT; queryHit = true }
				}
			}
			for (term in skillTerms) {
				if (term in doc.title || term in doc.body) hits += idf(term) * SKILL_WEIGHT
			}
			if (!queryHit || hits <= 0.0) return@mapNotNull null
			Scored(doc.job, (hits / best * placeFactor(doc.job, criteria)).coerceIn(0.0, 1.0), titleHit)
		}.sortedWith(compareByDescending<Scored> { it.titleHit }.thenByDescending { it.relevance })
	}

	/** Demotes, rather than drops, listings in the wrong place: location data in feeds is patchy. */
	private fun placeFactor(job: JobListing, criteria: SearchCriteria): Double {
		val wanted = criteria.location?.lowercase()
		val inPlace = wanted != null && job.location?.lowercase()?.contains(wanted) == true
		return when {
			inPlace -> 1.0
			job.remote && (criteria.remote || wanted == null) -> 1.0
			wanted == null && !criteria.remote -> 1.0
			job.remote -> 0.8
			else -> 0.5
		}
	}

	private class Doc(val job: JobListing, val title: Set<String>, val body: Set<String>)

	companion object {
		private const val TITLE_WEIGHT = 3.0
		private const val BODY_WEIGHT = 1.0
		private const val SKILL_WEIGHT = 0.5

		/** Words that appear in almost every listing or request and carry no signal. */
		private val GENERIC = setOf(
			"developer", "engineer", "entwickler", "senior", "junior", "lead", "staff", "principal", "mid",
			"job", "jobs", "and", "or", "the", "of", "in", "for", "with", "m", "w", "d", "f", "x",
		)

		private val SPLIT = Regex("""[^\p{L}\p{N}+#]+""")

		/** The words of [queries] that carry meaning: "senior android developer" gives "android". */
		fun keywords(queries: List<String>): List<String> = queries.flatMap(::tokenize).distinct() - GENERIC

		fun tokenize(text: String): List<String> =
			text.lowercase().split(SPLIT).filter { it.length > 1 || it == "c" || it == "r" }
	}
}
