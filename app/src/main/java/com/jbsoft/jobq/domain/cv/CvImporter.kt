package com.jbsoft.jobq.domain.cv

import com.jbsoft.jobq.domain.ai.CvAnalyzer
import com.jbsoft.jobq.domain.ai.LlmException
import com.jbsoft.jobq.domain.model.CvDocument
import com.jbsoft.jobq.domain.repository.CvRepository
import com.jbsoft.jobq.domain.repository.CvTextExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface CvImportState {
	data object Idle : CvImportState
	data object Reading : CvImportState
	data object Analyzing : CvImportState
	data class Failed(val reason: CvFailure) : CvImportState
}

enum class CvFailure {
	Unreadable,
	/** The PDF has no text layer, e.g. a scan. */
	NoText,
	NoModel,
	AnalysisFailed,
}

/** Imports a CV and analyses it with the local model. Runs in [scope] so it survives navigation. */
class CvImporter(
	private val scope: CoroutineScope,
	private val extractor: CvTextExtractor,
	private val analyzer: CvAnalyzer,
	private val repository: CvRepository,
	private val now: () -> Long = System::currentTimeMillis,
) {
	private val _state = MutableStateFlow<CvImportState>(CvImportState.Idle)
	val state: StateFlow<CvImportState> = _state.asStateFlow()

	private var running: Job? = null

	fun import(uri: String) = launch {
		_state.value = CvImportState.Reading
		val extracted = try {
			extractor.extract(uri)
		} catch (e: IOException) {
			_state.value = CvImportState.Failed(CvFailure.Unreadable)
			return@launch
		}
		if (extracted.text.length < MIN_TEXT_CHARS) {
			_state.value = CvImportState.Failed(CvFailure.NoText)
			return@launch
		}
		// Keep the text even if analysis fails, so it can be retried without re-importing.
		val document = CvDocument(extracted.fileName, extracted.text, profile = null, importedAtMillis = now())
		repository.save(document)
		analyze(document)
	}

	/** Analyses the stored CV again, e.g. after installing a model or switching to a better one. */
	fun reanalyze() = launch {
		repository.current()?.let { analyze(it) }
	}

	fun dismissError() {
		if (_state.value is CvImportState.Failed) _state.value = CvImportState.Idle
	}

	private suspend fun analyze(document: CvDocument) {
		_state.value = CvImportState.Analyzing
		_state.value = try {
			repository.save(document.copy(profile = analyzer.analyze(document.text)))
			CvImportState.Idle
		} catch (e: LlmException.NoModel) {
			CvImportState.Failed(CvFailure.NoModel)
		} catch (e: LlmException) {
			CvImportState.Failed(CvFailure.AnalysisFailed)
		}
	}

	private fun launch(block: suspend () -> Unit) {
		running?.cancel()
		running = scope.launch {
			try {
				block()
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				_state.value = CvImportState.Failed(CvFailure.AnalysisFailed)
			}
		}
	}

	private companion object {
		const val MIN_TEXT_CHARS = 200
	}
}
