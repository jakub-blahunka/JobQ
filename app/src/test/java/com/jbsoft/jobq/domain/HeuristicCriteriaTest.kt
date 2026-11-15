package com.jbsoft.jobq.domain

import com.jbsoft.jobq.domain.ai.heuristicCriteria
import com.jbsoft.jobq.domain.model.CvProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HeuristicCriteriaTest {

	@Test
	fun `reads title, location, salary and work mode from the example request`() {
		val criteria = heuristicCriteria("Find Senior Android Developer jobs in Vienna, €120k+, remote or hybrid.", null)

		assertEquals(listOf("senior android developer"), criteria.queries)
		assertEquals("Vienna", criteria.location)
		assertEquals(120_000, criteria.minSalary)
		assertEquals("EUR", criteria.currency)
		assertTrue(criteria.remote)
		assertTrue(criteria.hybrid)
		assertEquals("senior", criteria.seniority)
	}

	@Test
	fun `reads a salary written out in full`() {
		val criteria = heuristicCriteria("backend engineer 85,000 USD", null)

		assertEquals(85_000, criteria.minSalary)
		assertEquals("USD", criteria.currency)
		assertEquals(listOf("backend engineer"), criteria.queries)
	}

	@Test
	fun `a plain title has no location, salary or work mode`() {
		val criteria = heuristicCriteria("data analyst", null)

		assertEquals(listOf("data analyst"), criteria.queries)
		assertNull(criteria.location)
		assertNull(criteria.minSalary)
		assertFalse(criteria.remote)
	}

	@Test
	fun `an empty request falls back to the CV headline`() {
		val criteria = heuristicCriteria("", CvProfile(headline = "Product Designer"))

		assertEquals(listOf("product designer"), criteria.queries)
	}
}
