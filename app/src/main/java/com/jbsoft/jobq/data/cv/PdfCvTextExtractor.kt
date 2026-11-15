package com.jbsoft.jobq.data.cv

import android.content.Context
import android.provider.OpenableColumns
import androidx.core.net.toUri
import com.jbsoft.jobq.domain.repository.CvTextExtractor
import com.jbsoft.jobq.domain.repository.ExtractedCv
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Reads the text layer of a PDF picked with the system file picker. Runs on the device. */
class PdfCvTextExtractor(private val context: Context) : CvTextExtractor {

	override suspend fun extract(uri: String): ExtractedCv = withContext(Dispatchers.IO) {
		PDFBoxResourceLoader.init(context)
		val parsed = uri.toUri()
		val text = try {
			val stream = context.contentResolver.openInputStream(parsed) ?: throw IOException("Cannot open $uri")
			stream.use { input ->
				PDDocument.load(input).use { document ->
					if (document.isEncrypted) throw IOException("PDF is password protected")
					PDFTextStripper().apply { sortByPosition = true }.getText(document)
				}
			}
		} catch (e: IOException) {
			throw e
		} catch (e: Exception) {
			// PdfBox reports malformed files with assorted runtime exceptions; SAF with SecurityException.
			throw IOException("Cannot read PDF", e)
		}
		ExtractedCv(fileName = displayName(uri) ?: "CV.pdf", text = text.normalizeWhitespace())
	}

	private fun displayName(uri: String): String? = runCatching {
		context.contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else null
		}
	}.getOrNull()

	private fun String.normalizeWhitespace() =
		replace(' ', ' ').replace(Regex("[ \\t]+"), " ").replace(Regex(" ?\\n ?"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
}
