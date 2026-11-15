package com.jbsoft.jobq.data.model

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jbsoft.jobq.R
import com.jbsoft.jobq.data.settings.SettingsStore
import com.jbsoft.jobq.domain.model.AiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads one model file as a foreground job, so it survives the app being closed.
 * Interrupted downloads resume from the partial file with an HTTP range request.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params), KoinComponent {

	private val client: OkHttpClient by inject()
	private val settings: SettingsStore by inject()

	override suspend fun doWork(): Result {
		val model = inputData.getString(KEY_MODEL_ID)?.let(ModelCatalog::byId) ?: return Result.failure()
		val target = modelFile(applicationContext, model)
		if (target.exists()) return Result.success()

		val missing = model.sizeBytes - partFile(target).length()
		val free = (target.parentFile ?: applicationContext.filesDir).also { it.mkdirs() }.usableSpace
		if (free < missing + STORAGE_MARGIN_BYTES) {
			return Result.failure(workDataOf(KEY_OUT_OF_STORAGE to true))
		}

		// Fails when the app is in the background and notifications are off; the download still runs.
		runCatching { setForeground(foregroundInfo(model, 0)) }

		return try {
			download(model, target)
			if (settings.activeModelId.first() == null) settings.setActiveModelId(model.id)
			Result.success()
		} catch (e: InvalidModelException) {
			partFile(target).delete()
			Result.failure()
		} catch (e: IOException) {
			if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
		}
	}

	private suspend fun download(model: AiModel, target: File) = withContext(Dispatchers.IO) {
		target.parentFile?.mkdirs()
		val part = partFile(target)
		var have = if (part.exists()) part.length() else 0L
		if (have > model.sizeBytes) {
			part.delete()
			have = 0
		}

		if (have < model.sizeBytes) {
			val request = Request.Builder().url(model.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
				// A 200 to a range request means the server ignored it and is sending the whole file.
				val resumed = response.code == 206
				if (!resumed) have = 0
				FileOutputStream(part, resumed).use { out ->
					val source = response.body.byteStream()
					val buffer = ByteArray(256 * 1024)
					var lastReport = 0L
					while (true) {
						ensureActive()
						val read = source.read(buffer)
						if (read < 0) break
						out.write(buffer, 0, read)
						have += read
						val nowMs = System.currentTimeMillis()
						if (nowMs - lastReport > PROGRESS_INTERVAL_MS) {
							lastReport = nowMs
							report(model, have)
						}
					}
				}
			}
		}

		if (part.length() != model.sizeBytes || !part.isGguf()) throw InvalidModelException()
		if (!part.renameTo(target)) throw IOException("Could not move the downloaded file into place")
	}

	private suspend fun report(model: AiModel, downloaded: Long) {
		setProgress(workDataOf(KEY_DOWNLOADED to downloaded, KEY_TOTAL to model.sizeBytes))
		val percent = (downloaded * 100 / model.sizeBytes).toInt()
		runCatching {
			applicationContext.getSystemService(NotificationManager::class.java).notify(notificationId(model), notification(model, percent))
		}
	}

	private fun foregroundInfo(model: AiModel, percent: Int): ForegroundInfo {
		val notification = notification(model, percent)
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
			ForegroundInfo(notificationId(model), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		} else {
			ForegroundInfo(notificationId(model), notification)
		}
	}

	private fun notification(model: AiModel, percent: Int): Notification {
		val manager = applicationContext.getSystemService(NotificationManager::class.java)
		manager.createNotificationChannel(
			NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.download_channel), NotificationManager.IMPORTANCE_LOW)
		)
		return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.stat_sys_download)
			.setContentTitle(applicationContext.getString(R.string.download_notification_title, model.name))
			.setProgress(100, percent, false)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.addAction(0, applicationContext.getString(R.string.action_cancel), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
			.build()
	}

	private fun notificationId(model: AiModel) = 1000 + ModelCatalog.models.indexOf(model)

	private fun File.isGguf(): Boolean = inputStream().use { input ->
		val magic = ByteArray(4)
		input.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF"
	}

	/** The server sent something that is not the expected model file. Retrying will not help. */
	private class InvalidModelException : IOException()

	companion object {
		const val KEY_MODEL_ID = "model_id"
		const val KEY_DOWNLOADED = "downloaded"
		const val KEY_TOTAL = "total"
		const val KEY_OUT_OF_STORAGE = "out_of_storage"
		/** Room left for the app's database and the system after the model is stored. */
		private const val STORAGE_MARGIN_BYTES = 200L * 1024 * 1024
		private const val CHANNEL_ID = "model_downloads"
		private const val MAX_ATTEMPTS = 5
		private const val PROGRESS_INTERVAL_MS = 500L

		fun modelFile(context: Context, model: AiModel) = File(File(context.filesDir, "models"), model.fileName)
		fun partFile(target: File) = File(target.path + ".part")
	}
}
