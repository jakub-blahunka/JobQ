package com.jbsoft.jobq.ui.detail

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jbsoft.jobq.R
import com.jbsoft.jobq.domain.model.JobMatch
import com.jbsoft.jobq.domain.model.RankedJob
import com.jbsoft.jobq.ui.components.EmptyState
import com.jbsoft.jobq.ui.components.Pill
import com.jbsoft.jobq.ui.components.ScoreRing
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
	jobId: String,
	onBack: () -> Unit,
	viewModel: JobDetailViewModel = koinViewModel { parametersOf(jobId) },
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val item = state.item
	val context = LocalContext.current

	Scaffold(
		topBar = {
			TopAppBar(
				title = {},
				navigationIcon = {
					IconButton(onClick = onBack) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
					}
				},
				actions = {
					if (item != null) {
						IconButton(onClick = viewModel::toggleSaved) {
							if (item.saved) {
								Icon(Icons.Filled.Favorite, contentDescription = stringResource(R.string.action_unsave), tint = MaterialTheme.colorScheme.primary)
							} else {
								Icon(Icons.Filled.FavoriteBorder, contentDescription = stringResource(R.string.action_save))
							}
						}
					}
				},
			)
		},
		bottomBar = {
			if (item != null) {
				Surface(shadowElevation = 8.dp) {
					Button(
						onClick = {
							// Applications always go to the original posting, as the sources require.
							try {
								context.startActivity(Intent(Intent.ACTION_VIEW, item.job.sourceUrl.toUri()))
							} catch (e: ActivityNotFoundException) {
								// No browser installed; nothing sensible to do.
							}
						},
						modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp).height(52.dp),
					) {
						Text(stringResource(R.string.detail_open_original))
					}
				}
			}
		},
	) { padding ->
		when {
			state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
			item == null -> EmptyState(stringResource(R.string.detail_not_found), "", Modifier.padding(padding))
			else -> JobDetail(item, state.analysis, viewModel::analyze, Modifier.padding(padding))
		}
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JobDetail(item: RankedJob, analysis: Analysis, onAnalyze: () -> Unit, modifier: Modifier = Modifier) {
	val job = item.job
	Column(
		modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
			Text(job.title, style = MaterialTheme.typography.headlineMedium)
			Text(
				listOfNotNull(job.company.takeIf { it.isNotBlank() }, job.location).joinToString(" · "),
				style = MaterialTheme.typography.titleMedium,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
			)
			FlowRow(
				Modifier.padding(top = 4.dp),
				horizontalArrangement = Arrangement.spacedBy(6.dp),
				verticalArrangement = Arrangement.spacedBy(6.dp),
			) {
				if (job.remote) Pill(stringResource(R.string.job_remote), emphasized = true)
				job.salary?.let { Pill(it) }
				job.tags.take(4).forEach { Pill(it) }
			}
		}

		val match = item.match
		if (match != null && match.aiScored) {
			MatchCard(match)
		} else {
			AnalyzeCard(analysis, onAnalyze)
		}

		HorizontalDivider()
		Text(stringResource(R.string.detail_description), style = MaterialTheme.typography.titleLarge)
		Text(job.description, style = MaterialTheme.typography.bodyLarge)
		Text(
			stringResource(R.string.detail_source, job.sourceName),
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
	}
}

@Composable
private fun MatchCard(match: JobMatch) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				ScoreRing(match.score, size = 76.dp)
				Spacer(Modifier.width(16.dp))
				Text(match.summary, style = MaterialTheme.typography.bodyLarge)
			}
			if (match.strengths.isNotEmpty()) {
				Reasons(stringResource(R.string.detail_strengths), match.strengths, Icons.Filled.Check, MaterialTheme.colorScheme.primary)
			}
			if (match.missing.isNotEmpty()) {
				Reasons(stringResource(R.string.detail_missing), match.missing, Icons.Filled.Close, MaterialTheme.colorScheme.secondary)
			}
		}
	}
}

@Composable
private fun Reasons(title: String, reasons: List<String>, icon: ImageVector, tint: Color) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		Text(title, style = MaterialTheme.typography.titleMedium)
		reasons.forEach { reason ->
			Row {
				Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(18.dp))
				Spacer(Modifier.width(8.dp))
				Text(reason, style = MaterialTheme.typography.bodyMedium)
			}
		}
	}
}

@Composable
private fun AnalyzeCard(analysis: Analysis, onAnalyze: () -> Unit) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
		Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
			when (analysis) {
				Analysis.Running -> {
					Text(stringResource(R.string.detail_analyzing), style = MaterialTheme.typography.bodyLarge)
					LinearProgressIndicator(Modifier.fillMaxWidth())
				}
				else -> {
					Text(
						stringResource(if (analysis == Analysis.Failed) R.string.detail_analyze_failed else R.string.detail_not_analyzed),
						style = MaterialTheme.typography.bodyLarge,
						color = if (analysis == Analysis.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
					)
					FilledTonalButton(onClick = onAnalyze) { Text(stringResource(R.string.detail_analyze)) }
				}
			}
		}
	}
}
