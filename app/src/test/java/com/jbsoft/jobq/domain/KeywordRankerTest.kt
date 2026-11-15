package com.jbsoft.jobq.domain

import com.jbsoft.jobq.domain.model.CvProfile
import com.jbsoft.jobq.domain.model.JobListing
import com.jbsoft.jobq.domain.model.SearchCriteria
import com.jbsoft.jobq.domain.search.KeywordRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeywordRankerTest {

	private val ranker = KeywordRanker()

	private fun job(id: String, title: String, description: String = "", location: String? = null, remote: Boolean = false) =
		JobListing(id, "test", "Test", title, "Acme", location, null, description, "https://example.com/$id", remote)

	@Test
	fun `title matches outrank description matches and unrelated jobs are dropped`() {
		val jobs = listOf(
			job("body", "Software Developer", "We build Android and iOS apps."),
			job("title", "Android Developer", "Kotlin."),
			job("none", "Accountant", "Bookkeeping and payroll."),
		)

		val ranked = ranker.rank(jobs, SearchCriteria(queries = listOf("android developer")), null)

		assertEquals(listOf("title", "body"), ranked.map { it.job.id })
	}

	@Test
	fun `generic words alone do not make a match`() {
		val jobs = listOf(job("java", "Senior Java Developer", "Spring Boot."))

		val ranked = ranker.rank(jobs, SearchCriteria(queries = listOf("senior android developer")), null)

		assertTrue(ranked.isEmpty())
	}

	@Test
	fun `CV skills break ties between equally titled jobs`() {
		val jobs = listOf(
			job("plain", "Android Developer", "Java and XML layouts."),
			job("skilled", "Android Developer", "Kotlin, Compose and coroutines."),
		)

		val ranked = ranker.rank(jobs, SearchCriteria(queries = listOf("android developer")), CvProfile(skills = listOf("Kotlin", "Compose")))

		assertEquals("skilled", ranked.first().job.id)
	}

	@Test
	fun `jobs in the wanted place or remote outrank jobs elsewhere`() {
		val jobs = listOf(
			job("berlin", "Android Developer", location = "Berlin"),
			job("vienna", "Android Developer", location = "Vienna, Austria"),
			job("remote", "Android Developer", remote = true),
		)

		val ranked = ranker.rank(jobs, SearchCriteria(queries = listOf("android developer"), location = "Vienna", remote = true), null)

		assertEquals("berlin", ranked.last().job.id)
		assertTrue(ranked.last().relevance < ranked.first().relevance)
	}

	@Test
	fun `tokenizer keeps symbols that distinguish languages`() {
		assertEquals(listOf("c++", "c#", "node", "js"), KeywordRanker.tokenize("C++, C# / Node.js"))
	}
}
