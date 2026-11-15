package com.jbsoft.jobq.data.ai

import android.util.Log
import com.jbsoft.jobq.domain.ai.LlmEngine
import com.jbsoft.jobq.domain.ai.LlmException
import com.jbsoft.jobq.domain.ai.LlmRequest
import com.jbsoft.jobq.domain.repository.ModelRepository
import com.jbsoft.jobq.llama.LlamaException
import com.jbsoft.jobq.llama.LlamaModel
import com.jbsoft.jobq.llama.PromptTooLongException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** [LlmEngine] backed by llama.cpp. Keeps the active model loaded between requests. */
class LlamaEngine(private val models: ModelRepository) : LlmEngine {

	private val mutex = Mutex()
	// llama.cpp runs its own thread pool; this lane only serialises calls into it.
	private val dispatcher = Dispatchers.Default.limitedParallelism(1)
	private var loadedPath: String? = null
	private var loaded: LlamaModel? = null

	override suspend fun generate(request: LlmRequest): String = mutex.withLock {
		val path = models.activeModelPath() ?: throw LlmException.NoModel()
		try {
			coroutineScope {
				val model = withContext(dispatcher) { modelFor(path) }
				// Native generation cannot be suspended, so cancellation is relayed to it as a flag.
				val canceller = launch(start = CoroutineStart.UNDISPATCHED) {
					try {
						awaitCancellation()
					} finally {
						model.cancel()
					}
				}
				try {
					withContext(dispatcher) {
						model.generate(request.system, request.user, request.maxTokens, request.temperature, request.grammar)
					}?.also(::logReply) ?: throw CancellationException("Generation cancelled")
				} finally {
					canceller.cancel()
				}
			}
		} catch (e: PromptTooLongException) {
			throw LlmException.PromptTooLong()
		} catch (e: LlamaException) {
			throw LlmException.Failed(e.message ?: "Inference failed")
		}
	}

	/**
	 * Replies contain CV content, so they are logged only on request:
	 * `adb shell setprop log.tag.JobQLlm DEBUG`.
	 */
	private fun logReply(reply: String) {
		if (Log.isLoggable(LOG_TAG, Log.DEBUG)) Log.d(LOG_TAG, reply)
	}

	private fun modelFor(path: String): LlamaModel {
		loaded?.let { if (loadedPath == path) return it }
		loaded?.close()
		loaded = null
		return LlamaModel.load(path, CONTEXT_TOKENS, threads()).also {
			loaded = it
			loadedPath = path
		}
	}

	// More threads than fast cores slows inference down on big.LITTLE phones.
	private fun threads() = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)

	private companion object {
		const val CONTEXT_TOKENS = 4096
		const val LOG_TAG = "JobQLlm"
	}
}
