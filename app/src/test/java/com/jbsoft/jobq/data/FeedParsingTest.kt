package com.jbsoft.jobq.data

import com.jbsoft.jobq.data.remote.WeWorkRemotelySource
import com.jbsoft.jobq.data.remote.htmlToText
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedParsingTest {

	@Test
	fun `html descriptions become text with paragraph and list breaks`() {
		val text = htmlToText("<h2>About</h2><p>We build <b>apps</b>.</p><ul><li>Kotlin</li><li>Compose &amp; more</li></ul><script>x()</script>")

		assertEquals("About\n\nWe build apps.\n\n• Kotlin\n• Compose & more", text)
	}

	@Test
	fun `rss items are normalised to listings`() {
		val xml = """
			<?xml version="1.0" encoding="UTF-8"?>
			<rss version="2.0"><channel>
			<item>
				<title>Lemon.io: Senior Vue Developer</title>
				<region>Anywhere in the World</region>
				<category>Full-Stack Programming</category>
				<description>&lt;p&gt;&lt;strong&gt;Headquarters:&lt;/strong&gt; New York&lt;/p&gt;&lt;p&gt;Join us.&lt;/p&gt;</description>
				<pubDate>Sat, 14 Nov 2026 10:00:00 +0000</pubDate>
				<link>https://weworkremotely.com/remote-jobs/lemon-io-senior-vue-developer</link>
			</item>
			<item><title>Broken item without link</title></item>
			</channel></rss>
		""".trimIndent()

		val listings = WeWorkRemotelySource(OkHttpClient()).parseFeed(xml)

		assertEquals(1, listings.size)
		val job = listings.single()
		assertEquals("weworkremotely:lemon-io-senior-vue-developer", job.id)
		assertEquals("Senior Vue Developer", job.title)
		assertEquals("Lemon.io", job.company)
		assertEquals("Anywhere in the World", job.location)
		assertEquals("https://weworkremotely.com/remote-jobs/lemon-io-senior-vue-developer", job.sourceUrl)
		assertEquals(listOf("Full-Stack Programming"), job.tags)
		assertTrue(job.remote)
		assertEquals("Headquarters: New York\n\nJoin us.", job.description)
		assertEquals(1794650400000, job.postedAtMillis)
	}
}
