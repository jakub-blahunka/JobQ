package com.jbsoft.jobq.domain.ai

data class LlmRequest(
	val system: String,
	val user: String,
	val maxTokens: Int,
	val temperature: Float = 0.2f,
	/** GBNF grammar the reply must follow; see [JsonShape.toGbnf]. */
	val grammar: String? = null,
)

sealed class LlmException(message: String) : Exception(message) {
	/** No model has been downloaded and selected yet. */
	class NoModel : LlmException("No AI model is installed")
	class PromptTooLong : LlmException("The text does not fit the model's context window")
	class Failed(message: String) : LlmException(message)
}

/** Text generation that runs entirely on the device. */
interface LlmEngine {
	/**
	 * Generates a reply. One request runs at a time; others wait. Cancelling the calling
	 * coroutine stops generation.
	 *
	 * @throws LlmException
	 */
	suspend fun generate(request: LlmRequest): String
}
