package com.jbsoft.jobq.data.cv

import com.jbsoft.jobq.domain.model.CvDocument
import com.jbsoft.jobq.domain.model.CvProfile
import com.jbsoft.jobq.domain.repository.CvRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/**
 * Keeps the CV as two encrypted files in [directory], which must be inside the app's
 * private storage (`files/documents/cv`):
 *
 * - `cv.pdf.enc`: the original document, exactly as imported
 * - `cv.json.enc`: its file name, extracted text and the profile the model derived
 *
 * Nothing about the CV is stored anywhere else. Both files are encrypted with [cipher]
 * on top of the sandbox, so they are unreadable even to someone who copies them off a
 * rooted or backed-up device.
 */
class FileCvRepository(
	private val directory: File,
	private val cipher: CvCipher,
	private val json: Json,
	private val io: CoroutineDispatcher = Dispatchers.IO,
) : CvRepository {

	private val pdfFile = File(directory, "cv.pdf.enc")
	private val detailsFile = File(directory, "cv.json.enc")

	private val mutex = Mutex()
	/** Null until the files have been read once; then [Loaded], whose document is null when there is no CV. */
	private val state = MutableStateFlow<Loaded?>(null)

	private class Loaded(val document: CvDocument?)

	override fun observe(): Flow<CvDocument?> = flow {
		ensureLoaded()
		emitAll(state.map { it?.document })
	}

	override suspend fun current(): CvDocument? = ensureLoaded().document

	override suspend fun import(document: CvDocument, pdf: ByteArray) = write {
		writeEncrypted(pdfFile, pdf)
		writeEncrypted(detailsFile, json.encodeToString(document.toStored()).toByteArray())
		document
	}

	override suspend fun update(document: CvDocument) = write {
		writeEncrypted(detailsFile, json.encodeToString(document.toStored()).toByteArray())
		document
	}

	override suspend fun clear() = write {
		// Fail loudly rather than tell the user their CV is gone while a copy remains.
		for (file in listOf(pdfFile, detailsFile)) {
			if (file.exists() && !file.delete()) throw IOException("Could not delete ${file.name}")
		}
		null
	}

	private suspend fun write(block: () -> CvDocument?) = mutex.withLock {
		val document = withContext(io) { block() }
		state.value = Loaded(document)
	}

	private suspend fun ensureLoaded(): Loaded {
		state.value?.let { return it }
		return mutex.withLock {
			state.value ?: Loaded(withContext(io) { read() }).also { state.value = it }
		}
	}

	private fun read(): CvDocument? {
		val bytes = readDecrypted(detailsFile) ?: return null
		return try {
			json.decodeFromString<StoredCv>(bytes.decodeToString()).toDomain()
		} catch (e: SerializationException) {
			null
		}
	}

	/** Null when the file is missing or was encrypted with a key this device no longer has. */
	private fun readDecrypted(file: File): ByteArray? {
		if (!file.exists()) return null
		return try {
			cipher.decrypt(file.readBytes())
		} catch (e: GeneralSecurityException) {
			null
		} catch (e: IOException) {
			null
		}
	}

	/** Writes to a temporary file first, so a crash mid-write cannot leave a half-written CV. */
	private fun writeEncrypted(file: File, plain: ByteArray) {
		if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create ${directory.path}")
		val temp = File(directory, file.name + ".tmp")
		temp.writeBytes(cipher.encrypt(plain))
		if (!temp.renameTo(file)) {
			temp.delete()
			throw IOException("Could not write ${file.name}")
		}
	}

	@Serializable
	private data class StoredCv(
		val fileName: String,
		val text: String,
		val profile: CvProfile? = null,
		val importedAtMillis: Long,
	)

	private fun CvDocument.toStored() = StoredCv(fileName, text, profile, importedAtMillis)
	private fun StoredCv.toDomain() = CvDocument(fileName, text, profile, importedAtMillis)
}
