package com.jbsoft.jobq.data.local

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(tableName = "jobs")
data class JobEntity(
	@PrimaryKey val id: String,
	val sourceId: String,
	val sourceName: String,
	val title: String,
	val company: String,
	val location: String?,
	val salary: String?,
	val description: String,
	val sourceUrl: String,
	val remote: Boolean,
	val tags: List<String>,
	val postedAt: Long?,
	val fetchedAt: Long,
	@ColumnInfo(defaultValue = "0") val saved: Boolean = false,
)

@Entity(tableName = "searches")
data class SearchEntity(
	@PrimaryKey(autoGenerate = true) val id: Long = 0,
	val request: String,
	val criteriaJson: String,
	val createdAt: Long,
)

@Entity(
	tableName = "job_matches",
	primaryKeys = ["searchId", "jobId"],
	foreignKeys = [
		ForeignKey(entity = JobEntity::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE),
		ForeignKey(entity = SearchEntity::class, parentColumns = ["id"], childColumns = ["searchId"], onDelete = ForeignKey.CASCADE),
	],
	indices = [Index("jobId")],
)
data class MatchEntity(
	val searchId: Long,
	val jobId: String,
	val score: Int,
	val summary: String,
	val strengths: List<String>,
	val missing: List<String>,
	val aiScored: Boolean,
)

/** Single row (id 0): the imported CV and what the model extracted from it. */
@Entity(tableName = "cv")
data class CvEntity(
	@PrimaryKey val id: Int = 0,
	val fileName: String,
	val text: String,
	val profileJson: String?,
	val importedAt: Long,
)

/** When a source was last refreshed for a query, to avoid polling feeds more than they allow. */
@Entity(tableName = "source_fetches")
data class SourceFetchEntity(
	@PrimaryKey val sourceKey: String,
	val fetchedAt: Long,
)

/** A job with its match, as selected by the queries in [JobDao] (match columns are prefixed `m_`). */
data class JobWithMatch(
	@Embedded val job: JobEntity,
	@Embedded(prefix = "m_") val match: MatchEntity?,
)
