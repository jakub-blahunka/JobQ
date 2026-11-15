package com.jbsoft.jobq.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jbsoft.jobq.R
import com.jbsoft.jobq.domain.model.RankedJob

@Composable
fun scoreColor(score: Int): Color = when {
	score >= 75 -> MaterialTheme.colorScheme.primary
	score >= 50 -> MaterialTheme.colorScheme.secondary
	else -> MaterialTheme.colorScheme.outline
}

/** A match percentage inside a ring that fills with the score. */
@Composable
fun ScoreRing(score: Int, modifier: Modifier = Modifier, size: Dp = 52.dp) {
	val color = scoreColor(score)
	val track = MaterialTheme.colorScheme.surfaceVariant
	val description = stringResource(R.string.job_score_description, score)
	Box(
		modifier = modifier.size(size).clearAndSetSemantics { contentDescription = description },
		contentAlignment = Alignment.Center,
	) {
		Canvas(Modifier.fillMaxSize()) {
			val stroke = this.size.minDimension * 0.1f
			val inset = stroke / 2
			val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
			drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
			drawArc(color, -90f, 360f * score / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
		}
		Text(
			text = "$score",
			style = if (size >= 72.dp) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
			fontWeight = FontWeight.Bold,
			color = MaterialTheme.colorScheme.onSurface,
			textAlign = TextAlign.Center,
		)
	}
}

@Composable
fun Pill(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
	Surface(
		modifier = modifier,
		shape = RoundedCornerShape(50),
		color = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
		contentColor = if (emphasized) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
	) {
		Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun JobCard(item: RankedJob, onClick: () -> Unit, modifier: Modifier = Modifier) {
	val job = item.job
	val match = item.match
	Card(
		onClick = onClick,
		modifier = modifier.fillMaxWidth(),
		colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
		elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
	) {
		Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
			Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
				Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
				Text(
					listOfNotNull(job.company.takeIf { it.isNotBlank() }, job.location).joinToString(" · "),
					style = MaterialTheme.typography.bodyMedium,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				FlowRow(
					Modifier.padding(top = 4.dp),
					horizontalArrangement = Arrangement.spacedBy(6.dp),
					verticalArrangement = Arrangement.spacedBy(6.dp),
				) {
					if (job.remote) Pill(stringResource(R.string.job_remote), emphasized = true)
					job.salary?.let { Pill(it) }
					Pill(job.sourceName)
				}
				if (match != null && match.aiScored && match.summary.isNotBlank()) {
					Text(
						match.summary,
						Modifier.padding(top = 6.dp),
						style = MaterialTheme.typography.bodyMedium,
						maxLines = 3,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
			if (match != null && match.aiScored) {
				Spacer(Modifier.width(12.dp))
				ScoreRing(match.score)
			}
		}
	}
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
	Column(
		modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
		Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
		if (action != null) {
			Spacer(Modifier.size(8.dp))
			action()
		}
	}
}
