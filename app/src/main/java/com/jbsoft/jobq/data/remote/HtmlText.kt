package com.jbsoft.jobq.data.remote

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/** Converts a listing's HTML description to plain text, keeping paragraph and list breaks. */
fun htmlToText(html: String): String {
	val document = Jsoup.parse(html)
	document.select("script, style, img").remove()
	document.select("br").forEach { it.after("\\n") }
	document.select("p, div, h1, h2, h3, h4, h5, h6, ul, ol, tr").forEach { it.before("\\n\\n") }
	document.select("li").forEach { it.before("\\n• ") }
	return document.text()
		.replace("\\n", "\n")
		.replace(Regex("[ \\t\\u00A0]+"), " ")
		.replace(Regex(" ?\\n ?"), "\n")
		.replace(Regex("\\n{3,}"), "\n\n")
		.trim()
}

/** Decodes entities such as `&amp;` in titles and company names. */
fun unescapeHtml(text: String): String = Parser.unescapeEntities(text, false).trim()
