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
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Reads the text layer of a PDF picked with the system file picker. Runs on the device. */
class PdfCvTextExtractor(private val context: Context) : CvTextExtractor {

	private companion object {
		const val MAX_PDF_BYTES = 20 * 1024 * 1024
	}

	override suspend fun extract(uri: String): ExtractedCv = withContext(Dispatchers.IO) {
		PDFBoxResourceLoader.init(context)
		val parsed = uri.toUri()
		val pdf: ByteArray
		val text = try {
			val stream = context.contentResolver.openInputStream(parsed) ?: throw IOException("Cannot open $uri")
			pdf = stream.use { it.readBytesUpTo(MAX_PDF_BYTES) }
			PDDocument.load(pdf).use { document ->
				if (document.isEncrypted) throw IOException("PDF is password protected")
				PDFTextStripper().apply { sortByPosition = true }.getText(document)
			}
		} catch (e: IOException) {
			throw e
		} catch (e: Exception) {
			// PdfBox reports malformed files with assorted runtime exceptions; SAF with SecurityException.
			throw IOException("Cannot read PDF", e)
		}
		ExtractedCv(fileName = displayName(uri) ?: "CV.pdf", pdf = pdf, text = text.normalizeWhitespace())
	}

	private fun displayName(uri: String): String? = runCatching {
		context.contentResolver.query(uri.toUri(), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
			if (cursor.moveToFirst()) cursor.getString(0) else null
		}
	}.getOrNull()

	private fun InputStream.readBytesUpTo(limit: Int): ByteArray {
		val bytes = readNBytesCompat(limit + 1)
		if (bytes.size > limit) throw IOException("File is too large to be a CV")
		return bytes
	}

	private fun InputStream.readNBytesCompat(max: Int): ByteArray {
		val out = ByteArrayOutputStream()
		val buffer = ByteArray(64 * 1024)
		while (out.size() < max) {
			val read = read(buffer, 0, minOf(buffer.size, max - out.size()))
			if (read < 0) break
			out.write(buffer, 0, read)
		}
		return out.toByteArray()
	}

	private fun String.normalizeWhitespace() =
		replace(' ', ' ').replace(Regex("[ \\t]+"), " ").replace(Regex(" ?\\n ?"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()
}
