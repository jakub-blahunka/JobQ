package com.jbsoft.jobq.data.model

import com.jbsoft.jobq.domain.model.AiModel

/**
 * Models offered for download: 4-bit GGUF builds hosted on Hugging Face. Sizes are the
 * exact file sizes and are used to verify downloads.
 *
 * Check each licence before adding a model: the app ships to users who may use it commercially.
 */
object ModelCatalog {
	val models = listOf(
		AiModel(
			id = "llama-3.2-3b",
			name = "Llama 3.2 3B",
			family = "Meta",
			blurb = "Best balance of quality and speed. Needs about 4 GB of free RAM.",
			sizeBytes = 2_019_377_696,
			url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
			fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
			license = "Llama 3.2 Community License",
			licenseUrl = "https://www.llama.com/llama3_2/license/",
			recommended = true,
		),
		AiModel(
			id = "gemma-3-4b",
			name = "Gemma 3 4B",
			family = "Google",
			blurb = "Most accurate analysis, and the slowest. For phones with 8 GB of RAM or more.",
			sizeBytes = 2_489_894_016,
			url = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
			fileName = "gemma-3-4b-it-Q4_K_M.gguf",
			license = "Gemma Terms of Use",
			licenseUrl = "https://ai.google.dev/gemma/terms",
		),
		AiModel(
			id = "phi-4-mini",
			name = "Phi-4 mini",
			family = "Microsoft",
			blurb = "Strong reasoning for its size. For phones with 8 GB of RAM or more.",
			sizeBytes = 2_491_874_272,
			url = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
			fileName = "Phi-4-mini-instruct-Q4_K_M.gguf",
			license = "MIT",
			licenseUrl = "https://huggingface.co/microsoft/Phi-4-mini-instruct/blob/main/LICENSE",
		),
		AiModel(
			id = "gemma-3-1b",
			name = "Gemma 3 1B",
			family = "Google",
			blurb = "Small and fast, for older phones. Match explanations are noticeably rougher.",
			sizeBytes = 806_058_272,
			url = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
			fileName = "gemma-3-1b-it-Q4_K_M.gguf",
			license = "Gemma Terms of Use",
			licenseUrl = "https://ai.google.dev/gemma/terms",
		),
	)

	fun byId(id: String): AiModel? = models.firstOrNull { it.id == id }
}
