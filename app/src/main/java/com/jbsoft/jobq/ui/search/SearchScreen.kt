package com.jbsoft.jobq.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jbsoft.jobq.R
import com.jbsoft.jobq.domain.model.SearchCriteria
import com.jbsoft.jobq.domain.search.SearchFailure
import com.jbsoft.jobq.domain.search.SearchState
import com.jbsoft.jobq.ui.components.JobCard
import com.jbsoft.jobq.ui.components.Pill
import org.koin.androidx.compose.koinViewModel
import java.text.NumberFormat

@Composable
fun SearchScreen(
	onOpenJob: (String) -> Unit,
	onOpenModels: () -> Unit,
	onOpenProfile: () -> Unit,
	contentPadding: PaddingValues,
	viewModel: SearchViewModel = koinViewModel(),
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val ui = state
	if (ui == null) {
		Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
		return
	}

	val aiJobs = ui.results?.jobs.orEmpty().filter { it.match?.aiScored == true }
	val keywordJobs = ui.results?.jobs.orEmpty().filter { it.match?.aiScored != true }

	LazyColumn(
		modifier = Modifier.fillMaxSize(),
		contentPadding = PaddingValues(
			start = 16.dp,
			end = 16.dp,
			top = contentPadding.calculateTopPadding() + 24.dp,
			bottom = contentPadding.calculateBottomPadding() + 24.dp,
		),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		item(key = "header") {
			Column {
				Text(stringResource(R.string.search_title), style = MaterialTheme.typography.headlineMedium)
				Text(
					stringResource(R.string.search_tagline),
					style = MaterialTheme.typography.bodyLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
		}

		if (!ui.ready) {
			item(key = "setup") { SetupCard(ui, onOpenModels, onOpenProfile) }
		} else {
			item(key = "request") {
				RequestForm(
					request = viewModel.request,
					onRequestChange = viewModel::onRequestChange,
					busy = ui.busy,
					onSearch = viewModel::search,
				)
			}
		}

		item(key = "status") { SearchStatus(ui.search, onCancel = viewModel::cancel) }

		val results = ui.results
		if (results != null && (ui.ready || results.jobs.isNotEmpty())) {
			item(key = "criteria") { CriteriaRow(results.criteria) }
			if (results.jobs.isEmpty() && !ui.busy) {
				item(key = "none") {
					Text(stringResource(R.string.search_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
			if (aiJobs.isNotEmpty()) {
				item(key = "ai-title") { SectionTitle(stringResource(R.string.results_ai_section)) }
				items(aiJobs, key = { it.job.id }) { job -> JobCard(job, onClick = { onOpenJob(job.job.id) }, Modifier.animateItem()) }
			}
			if (keywordJobs.isNotEmpty()) {
				item(key = "kw-title") {
					Column {
						SectionTitle(stringResource(R.string.results_keyword_section))
						Text(
							stringResource(R.string.results_keyword_note),
							style = MaterialTheme.typography.bodyMedium,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
						)
					}
				}
				items(keywordJobs, key = { it.job.id }) { job -> JobCard(job, onClick = { onOpenJob(job.job.id) }, Modifier.animateItem()) }
			}
		}
	}
}

@Composable
private fun SectionTitle(text: String) {
	Text(text, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun RequestForm(request: String, onRequestChange: (String) -> Unit, busy: Boolean, onSearch: () -> Unit) {
	val keyboard = LocalSoftwareKeyboardController.current
	val submit = {
		keyboard?.hide()
		onSearch()
	}
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		OutlinedTextField(
			value = request,
			onValueChange = onRequestChange,
			modifier = Modifier.fillMaxWidth(),
			label = { Text(stringResource(R.string.search_label)) },
			placeholder = { Text(stringResource(R.string.search_hint)) },
			supportingText = { Text(stringResource(R.string.search_empty_request_note)) },
			minLines = 2,
			maxLines = 4,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
			keyboardActions = KeyboardActions(onSearch = { if (!busy) submit() }),
		)
		Button(onClick = submit, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
			Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(20.dp))
			Spacer(Modifier.size(8.dp))
			Text(stringResource(R.string.search_action))
		}
	}
}

@Composable
private fun SearchStatus(search: SearchState, onCancel: () -> Unit) {
	when (search) {
		SearchState.Idle -> Unit
		SearchState.Understanding -> ProgressCard(stringResource(R.string.stage_understanding), null, null, onCancel)
		SearchState.Fetching -> ProgressCard(stringResource(R.string.stage_fetching), null, null, onCancel)
		is SearchState.Ranking -> ProgressCard(
			stringResource(R.string.stage_ranking, search.done + 1, search.total),
			stringResource(R.string.stage_ranking_note),
			search.done.toFloat() / search.total,
			onCancel,
		)
		is SearchState.Finished -> if (search.unreachableSources.isNotEmpty()) {
			Notice(stringResource(R.string.search_sources_unreachable, search.unreachableSources.joinToString()))
		}
		is SearchState.Failed -> Notice(
			stringResource(
				when (search.reason) {
					SearchFailure.NoModel -> R.string.search_failed_no_model
					SearchFailure.NoCv -> R.string.search_failed_no_cv
					SearchFailure.Unexpected -> R.string.search_failed_unexpected
				}
			),
			error = true,
		)
	}
}

@Composable
private fun ProgressCard(title: String, note: String?, progress: Float?, onCancel: () -> Unit) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
		Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
				TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
			}
			val barModifier = Modifier.fillMaxWidth().padding(end = 8.dp)
			if (progress == null) LinearProgressIndicator(barModifier) else LinearProgressIndicator(progress = { progress }, modifier = barModifier)
			if (note != null) Text(note, Modifier.padding(end = 8.dp), style = MaterialTheme.typography.bodyMedium)
		}
	}
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
	Card(
		colors = CardDefaults.cardColors(
			containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
			contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
		),
	) {
		Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CriteriaRow(criteria: SearchCriteria) {
	val pills = buildList {
		addAll(criteria.queries)
		criteria.location?.let(::add)
		criteria.minSalary?.let {
			val amount = NumberFormat.getIntegerInstance().format(it)
			add(stringResource(R.string.criteria_min_salary, listOfNotNull(criteria.currency, amount).joinToString(" ")))
		}
		if (criteria.remote) add(stringResource(R.string.criteria_remote))
		if (criteria.hybrid) add(stringResource(R.string.criteria_hybrid))
		criteria.seniority?.let(::add)
	}
	if (pills.isEmpty()) return
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(stringResource(R.string.criteria_understood), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
		FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
			pills.forEach { Pill(it) }
		}
	}
}

@Composable
private fun SetupCard(ui: SearchUiState, onOpenModels: () -> Unit, onOpenProfile: () -> Unit) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
		Column(Modifier.padding(vertical = 16.dp)) {
			Text(stringResource(R.string.setup_title), Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.titleLarge)
			Spacer(Modifier.height(8.dp))
			SetupStep(
				number = 1,
				title = stringResource(R.string.setup_model_title),
				body = stringResource(R.string.setup_model_body),
				done = ui.hasModel,
				onClick = onOpenModels,
			)
			SetupStep(
				number = 2,
				title = stringResource(R.string.setup_cv_title),
				body = stringResource(if (ui.cvPending) R.string.setup_cv_analyzing else R.string.setup_cv_body),
				done = ui.hasCv,
				onClick = onOpenProfile,
			)
		}
	}
}

@Composable
private fun SetupStep(number: Int, title: String, body: String, done: Boolean, onClick: () -> Unit) {
	val doneLabel = stringResource(R.string.setup_done)
	TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
				if (done) {
					Icon(Icons.Filled.CheckCircle, contentDescription = doneLabel, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
				} else {
					Text("$number", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.secondary)
				}
			}
			Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
				Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
				Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			if (!done) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}
