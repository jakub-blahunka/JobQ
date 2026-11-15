package com.jbsoft.jobq.data.model

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jbsoft.jobq.data.settings.SettingsStore
import com.jbsoft.jobq.domain.model.DownloadFailure
import com.jbsoft.jobq.domain.model.ModelState
import com.jbsoft.jobq.domain.model.ModelStatus
import com.jbsoft.jobq.domain.repository.ModelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class ModelRepositoryImpl(
	private val context: Context,
	private val settings: SettingsStore,
) : ModelRepository {

	private val workManager get() = WorkManager.getInstance(context)

	/** Bumped when files change outside WorkManager's view (deletion), to re-evaluate [observeModels]. */
	private val filesChanged = MutableStateFlow(0)

	override fun observeModels(): Flow<List<ModelStatus>> =
		combine(workManager.getWorkInfosByTagFlow(TAG), settings.activeModelId, filesChanged) { infos, activeId, _ ->
			ModelCatalog.models.map { model ->
				val info = infos.lastOrNull { workTag(model.id) in it.tags && it.state != WorkInfo.State.CANCELLED && it.state != WorkInfo.State.SUCCEEDED }
				val state = when {
					ModelDownloadWorker.modelFile(context, model).exists() -> ModelState.Downloaded
					info == null -> ModelState.NotDownloaded
					info.state == WorkInfo.State.FAILED -> ModelState.Failed(
						if (info.outputData.getBoolean(ModelDownloadWorker.KEY_OUT_OF_STORAGE, false)) DownloadFailure.NotEnoughStorage else DownloadFailure.Other
					)
					else -> ModelState.Downloading(
						downloadedBytes = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, 0),
						totalBytes = model.sizeBytes,
					)
				}
				ModelStatus(model, state, active = state == ModelState.Downloaded && model.id == activeId)
			}
		}.flowOn(Dispatchers.IO)

	override fun download(modelId: String) {
		val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
			.setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL_ID to modelId))
			.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
			.setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
			.addTag(TAG)
			.addTag(workTag(modelId))
			.build()
		workManager.enqueueUniqueWork(workTag(modelId), ExistingWorkPolicy.KEEP, request)
	}

	/** Cancelling discards the partial file; only interrupted downloads resume. */
	override suspend fun cancelDownload(modelId: String) {
		val model = ModelCatalog.byId(modelId) ?: return
		workManager.cancelUniqueWork(workTag(modelId))
		withContext(Dispatchers.IO) { ModelDownloadWorker.partFile(ModelDownloadWorker.modelFile(context, model)).delete() }
	}

	override suspend fun delete(modelId: String) {
		val model = ModelCatalog.byId(modelId) ?: return
		workManager.cancelUniqueWork(workTag(modelId))
		withContext(Dispatchers.IO) {
			val file = ModelDownloadWorker.modelFile(context, model)
			file.delete()
			ModelDownloadWorker.partFile(file).delete()
		}
		if (settings.activeModelId.first() == modelId) {
			// Fall back to another installed model, if there is one.
			settings.setActiveModelId(ModelCatalog.models.firstOrNull { it.id != modelId && ModelDownloadWorker.modelFile(context, it).exists() }?.id)
		}
		filesChanged.update { it + 1 }
	}

	override suspend fun select(modelId: String) = settings.setActiveModelId(modelId)

	override suspend fun activeModelPath(): String? = withContext(Dispatchers.IO) {
		val model = settings.activeModelId.first()?.let(ModelCatalog::byId) ?: return@withContext null
		ModelDownloadWorker.modelFile(context, model).takeIf { it.exists() }?.absolutePath
	}

	private companion object {
		const val TAG = "model-download"
		fun workTag(modelId: String) = "model-download:$modelId"
	}
}
