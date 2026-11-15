package com.jbsoft.jobq.data.remote

import com.jbsoft.jobq.domain.model.JobListing
import com.jbsoft.jobq.domain.repository.JobSource
import com.jbsoft.jobq.domain.repository.SourceQuery
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** We Work Remotely's public RSS feed of remote jobs. */
class WeWorkRemotelySource(private val client: OkHttpClient) : JobSource {
	override val id = "weworkremotely"
	override val displayName = "We Work Remotely"
	override val homepage = "https://weworkremotely.com"

	override suspend fun fetch(query: SourceQuery): List<JobListing> = ioCall {
		val request = Request.Builder().url(FEED_URL).build()
		val xml = client.newCall(request).execute().use { response ->
			if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
			response.body.string()
		}
		parseFeed(xml)
	}

	fun parseFeed(xml: String): List<JobListing> =
		Jsoup.parse(xml, "", Parser.xmlParser()).select("item").mapNotNull { item ->
			val link = item.selectFirst("link")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
			val rawTitle = item.selectFirst("title")?.text().orEmpty()
			// Titles are "Company: Role".
			val company = rawTitle.substringBefore(": ", "").trim()
			val title = rawTitle.substringAfter(": ").trim()
			if (title.isEmpty()) return@mapNotNull null
			JobListing(
				id = "$id:${link.substringAfterLast('/')}",
				sourceId = id,
				sourceName = displayName,
				title = title,
				company = company,
				location = item.selectFirst("region")?.text()?.takeIf { it.isNotBlank() },
				salary = null,
				description = htmlToText(item.selectFirst("description")?.text().orEmpty()),
				sourceUrl = link,
				remote = true,
				tags = listOfNotNull(item.selectFirst("category")?.text()?.takeIf { it.isNotBlank() }),
				postedAtMillis = item.selectFirst("pubDate")?.text()?.let {
					runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
				},
			)
		}

	private companion object {
		const val FEED_URL = "https://weworkremotely.com/remote-jobs.rss"
	}
}
