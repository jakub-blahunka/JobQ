package com.jbsoft.jobq.data.cv

import com.jbsoft.jobq.data.local.CvDao
import com.jbsoft.jobq.data.local.CvEntity
import com.jbsoft.jobq.domain.model.CvDocument
import com.jbsoft.jobq.domain.model.CvProfile
import com.jbsoft.jobq.domain.repository.CvRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

class CvRepositoryImpl(private val dao: CvDao, private val json: Json) : CvRepository {

	override fun observe(): Flow<CvDocument?> = dao.observe().map { it?.toDomain() }

	override suspend fun current(): CvDocument? = dao.get()?.toDomain()

	override suspend fun save(document: CvDocument) = dao.upsert(
		CvEntity(
			fileName = document.fileName,
			text = document.text,
			profileJson = document.profile?.let { json.encodeToString(it) },
			importedAt = document.importedAtMillis,
		)
	)

	override suspend fun clear() = dao.clear()

	private fun CvEntity.toDomain() = CvDocument(
		fileName = fileName,
		text = text,
		profile = profileJson?.let { runCatching { json.decodeFromString<CvProfile>(it) }.getOrNull() },
		importedAtMillis = importedAt,
	)
}
