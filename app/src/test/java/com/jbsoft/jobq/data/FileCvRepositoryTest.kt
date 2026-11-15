package com.jbsoft.jobq.data

import com.jbsoft.jobq.data.cv.CvCipher
import com.jbsoft.jobq.data.cv.FileCvRepository
import com.jbsoft.jobq.domain.model.CvDocument
import com.jbsoft.jobq.domain.model.CvProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException

@OptIn(ExperimentalCoroutinesApi::class)
class FileCvRepositoryTest {

	@get:Rule
	val folder = TemporaryFolder()

	/** Stands in for the Keystore: XOR with a key byte, and rejects data made with another key. */
	private class FakeCipher(private val key: Byte) : CvCipher {
		override fun encrypt(plain: ByteArray) = byteArrayOf(key) + plain.map { (it.toInt() xor key.toInt()).toByte() }
		override fun decrypt(encrypted: ByteArray): ByteArray {
			if (encrypted.isEmpty() || encrypted[0] != key) throw GeneralSecurityException("wrong key")
			return encrypted.drop(1).map { (it.toInt() xor key.toInt()).toByte() }.toByteArray()
		}
	}

	private val cipher = FakeCipher(0x5A)
	private val document = CvDocument("anna.pdf", "Anna Example, Android developer in Vienna", profile = null, importedAtMillis = 42)
	private val pdf = "%PDF-1.4 Anna Example".toByteArray()

	private fun TestScope.repository(dir: File, cipher: CvCipher = this@FileCvRepositoryTest.cipher) =
		FileCvRepository(dir, cipher, Json, UnconfinedTestDispatcher(testScheduler))

	private fun cvDir() = File(folder.root, "documents/cv")

	@Test
	fun `an imported CV survives a restart, with the profile added later`() = runTest {
		val analysed = document.copy(profile = CvProfile(name = "Anna Example", skills = listOf("Kotlin")))
		repository(cvDir()).apply {
			import(document, pdf)
			update(analysed)
		}

		val reopened = repository(cvDir())

		assertEquals(analysed, reopened.current())
		assertEquals(analysed, reopened.observe().first())
	}

	@Test
	fun `nothing readable is written to disk`() = runTest {
		repository(cvDir()).import(document, pdf)

		val files = cvDir().listFiles().orEmpty()
		assertEquals(setOf("cv.pdf.enc", "cv.json.enc"), files.map { it.name }.toSet())
		for (file in files) {
			val content = file.readBytes().toString(Charsets.ISO_8859_1)
			assertFalse("${file.name} contains plain text", content.contains("Anna") || content.contains("%PDF"))
		}
		assertArrayEquals(pdf, cipher.decrypt(File(cvDir(), "cv.pdf.enc").readBytes()))
	}

	@Test
	fun `clearing deletes both files and observers see the CV disappear`() = runTest {
		val repository = repository(cvDir())
		val seen = mutableListOf<CvDocument?>()
		val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.observe().toList(seen) }

		repository.import(document, pdf)
		repository.clear()
		collector.cancel()

		assertEquals(listOf(null, document, null), seen)
		assertTrue(cvDir().listFiles().orEmpty().isEmpty())
		assertNull(repository(cvDir()).current())
	}

	@Test
	fun `importing a new CV replaces the previous one and its file`() = runTest {
		val repository = repository(cvDir())
		repository.import(document.copy(profile = CvProfile(name = "Old")), pdf)

		val newPdf = "%PDF-1.7 other".toByteArray()
		repository.import(document.copy(fileName = "new.pdf"), newPdf)

		assertEquals("new.pdf", repository.current()?.fileName)
		assertNull(repository.current()?.profile)
		assertArrayEquals(newPdf, cipher.decrypt(File(cvDir(), "cv.pdf.enc").readBytes()))
	}

	@Test
	fun `a CV encrypted with a key this device no longer has reads as no CV`() = runTest {
		repository(cvDir()).import(document, pdf)

		val otherDevice = repository(cvDir(), FakeCipher(0x11))

		assertNull(otherDevice.current())
	}
}
