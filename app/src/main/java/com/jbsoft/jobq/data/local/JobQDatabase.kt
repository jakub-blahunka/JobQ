package com.jbsoft.jobq.data.local

import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.RoomDatabase
import androidx.room3.Transaction
import androidx.room3.ColumnTypeConverter
import androidx.room3.ColumnTypeConverters
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

private const val MATCH_COLUMNS =
	"m.searchId AS m_searchId, m.jobId AS m_jobId, m.score AS m_score, m.summary AS m_summary, " +
		"m.strengths AS m_strengths, m.missing AS m_missing, m.aiScored AS m_aiScored"

/** The match to show for a job outside a result list: its newest model-scored one, else its newest. */
private const val BEST_MATCH_JOIN =
	"LEFT JOIN job_matches m ON m.jobId = j.id AND m.searchId = " +
		"(SELECT searchId FROM job_matches WHERE jobId = j.id ORDER BY aiScored DESC, searchId DESC LIMIT 1)"

@Dao
interface JobDao {
	@Insert(onConflict = OnConflictStrategy.IGNORE)
	suspend fun insertNew(jobs: List<JobEntity>)

	/** Refreshes a listing's content without touching its saved flag. */
	@Query(
		"UPDATE jobs SET title = :title, company = :company, location = :location, salary = :salary, " +
			"description = :description, sourceUrl = :sourceUrl, remote = :remote, tags = :tags, " +
			"postedAt = :postedAt, fetchedAt = :fetchedAt WHERE id = :id"
	)
	suspend fun refresh(
		id: String, title: String, company: String, location: String?, salary: String?, description: String,
		sourceUrl: String, remote: Boolean, tags: List<String>, postedAt: Long?, fetchedAt: Long,
	)

	@Transaction
	suspend fun store(jobs: List<JobEntity>) {
		insertNew(jobs)
		for (j in jobs) {
			refresh(j.id, j.title, j.company, j.location, j.salary, j.description, j.sourceUrl, j.remote, j.tags, j.postedAt, j.fetchedAt)
		}
	}

	@Query("SELECT * FROM jobs")
	suspend fun all(): List<JobEntity>

	@Query("UPDATE jobs SET saved = :saved WHERE id = :id")
	suspend fun setSaved(id: String, saved: Boolean)

	@Query("DELETE FROM jobs WHERE saved = 0 AND fetchedAt < :cutoff")
	suspend fun prune(cutoff: Long)

	@Query(
		"SELECT j.*, $MATCH_COLUMNS FROM job_matches m JOIN jobs j ON j.id = m.jobId " +
			"WHERE m.searchId = :searchId ORDER BY m.aiScored DESC, m.score DESC"
	)
	fun observeResults(searchId: Long): Flow<List<JobWithMatch>>

	@Query("SELECT j.*, $MATCH_COLUMNS FROM jobs j $BEST_MATCH_JOIN WHERE j.saved = 1 ORDER BY m.score DESC")
	fun observeSaved(): Flow<List<JobWithMatch>>

	@Query("SELECT j.*, $MATCH_COLUMNS FROM jobs j $BEST_MATCH_JOIN WHERE j.id = :id")
	fun observeJob(id: String): Flow<JobWithMatch?>

	@Query("SELECT * FROM searches ORDER BY id DESC LIMIT 1")
	fun observeLatestSearch(): Flow<SearchEntity?>

	@Insert
	suspend fun insertSearch(search: SearchEntity): Long

	@Upsert
	suspend fun upsertMatches(matches: List<MatchEntity>)

	@Query("SELECT searchId FROM job_matches WHERE jobId = :jobId ORDER BY searchId DESC LIMIT 1")
	suspend fun latestSearchIdFor(jobId: String): Long?

	/** Drops superseded result sets, except model-scored matches of saved jobs, which the Saved list shows. */
	@Query(
		"DELETE FROM job_matches WHERE searchId < :keepFrom " +
			"AND (aiScored = 0 OR jobId NOT IN (SELECT id FROM jobs WHERE saved = 1))"
	)
	suspend fun deleteMatchesBefore(keepFrom: Long)

	@Query("SELECT fetchedAt FROM source_fetches WHERE sourceKey = :key")
	suspend fun lastFetched(key: String): Long?

	@Upsert
	suspend fun markFetched(fetch: SourceFetchEntity)
}

@Dao
interface CvDao {
	@Query("SELECT * FROM cv WHERE id = 0")
	fun observe(): Flow<CvEntity?>

	@Query("SELECT * FROM cv WHERE id = 0")
	suspend fun get(): CvEntity?

	@Upsert
	suspend fun upsert(cv: CvEntity)

	@Query("DELETE FROM cv")
	suspend fun clear()
}

class Converters {
	@ColumnTypeConverter
	fun fromList(value: List<String>): String = Json.encodeToString(value)

	@ColumnTypeConverter
	fun toList(value: String): List<String> = Json.decodeFromString(value)
}

@Database(
	entities = [JobEntity::class, SearchEntity::class, MatchEntity::class, CvEntity::class, SourceFetchEntity::class],
	version = 1,
	exportSchema = false,
)
@ColumnTypeConverters(Converters::class)
abstract class JobQDatabase : RoomDatabase() {
	abstract fun jobDao(): JobDao
	abstract fun cvDao(): CvDao
}
