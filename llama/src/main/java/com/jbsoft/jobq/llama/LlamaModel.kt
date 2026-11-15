package com.jbsoft.jobq.llama

/** Thrown when llama.cpp cannot load a model or run inference. */
open class LlamaException(message: String) : RuntimeException(message)

/** Thrown when the rendered prompt does not leave room for an answer in the context window. */
class PromptTooLongException(message: String) : LlamaException(message)

internal object LlamaNative {
	init {
		System.loadLibrary("jobq_llama")
	}

	external fun load(path: ByteArray, contextSize: Int, threads: Int): Long
	external fun free(handle: Long)
	external fun cancel(handle: Long)
	external fun generate(
		handle: Long,
		system: ByteArray,
		user: ByteArray,
		maxTokens: Int,
		temperature: Float,
		grammar: ByteArray?,
	): ByteArray?
}

/**
 * A GGUF model loaded into memory with one inference context.
 *
 * Not thread-safe: call [generate] and [close] from one thread at a time. [cancel] may be
 * called from any thread while [generate] is running.
 */
class LlamaModel private constructor(private var handle: Long) : AutoCloseable {

	/**
	 * Runs one system + user turn through the model's chat template and returns the reply,
	 * or null if [cancel] was called meanwhile.
	 *
	 * @param grammar optional GBNF grammar (start rule `root`) the reply must conform to.
	 * @throws PromptTooLongException if the prompt does not fit the context window.
	 */
	fun generate(
		system: String,
		user: String,
		maxTokens: Int,
		temperature: Float,
		grammar: String? = null,
	): String? {
		check(handle != 0L) { "Model is closed" }
		return LlamaNative.generate(
			handle,
			system.toByteArray(Charsets.UTF_8),
			user.toByteArray(Charsets.UTF_8),
			maxTokens,
			temperature,
			grammar?.toByteArray(Charsets.UTF_8),
		)?.toString(Charsets.UTF_8)
	}

	fun cancel() {
		val h = handle
		if (h != 0L) LlamaNative.cancel(h)
	}

	override fun close() {
		val h = handle
		handle = 0L
		if (h != 0L) LlamaNative.free(h)
	}

	companion object {
		/** @throws LlamaException if the file is not a loadable GGUF model. */
		fun load(path: String, contextSize: Int, threads: Int): LlamaModel =
			LlamaModel(LlamaNative.load(path.toByteArray(Charsets.UTF_8), contextSize, threads))
	}
}
