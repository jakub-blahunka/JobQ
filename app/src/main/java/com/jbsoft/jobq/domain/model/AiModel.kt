package com.jbsoft.jobq.domain.model

/** A downloadable GGUF model. */
data class AiModel(
	val id: String,
	val name: String,
	val family: String,
	val blurb: String,
	val sizeBytes: Long,
	val url: String,
	val fileName: String,
	val license: String,
	val licenseUrl: String,
	val recommended: Boolean = false,
)

enum class DownloadFailure { NotEnoughStorage, Other }

sealed interface ModelState {
	data object NotDownloaded : ModelState
	/** [totalBytes] is 0 until the server has reported a size. */
	data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelState
	data object Downloaded : ModelState
	data class Failed(val reason: DownloadFailure) : ModelState
}

data class ModelStatus(
	val model: AiModel,
	val state: ModelState,
	/** The model used for inference. At most one is active. */
	val active: Boolean,
)
