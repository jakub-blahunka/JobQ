package com.jbsoft.jobq.di

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.jbsoft.jobq.data.ai.LlamaEngine
import com.jbsoft.jobq.data.cv.CvRepositoryImpl
import com.jbsoft.jobq.data.cv.PdfCvTextExtractor
import com.jbsoft.jobq.data.local.JobQDatabase
import com.jbsoft.jobq.data.local.JobRepositoryImpl
import com.jbsoft.jobq.data.model.ModelRepositoryImpl
import com.jbsoft.jobq.data.remote.ArbeitnowApi
import com.jbsoft.jobq.data.remote.ArbeitnowSource
import com.jbsoft.jobq.data.remote.JobicyApi
import com.jbsoft.jobq.data.remote.JobicySource
import com.jbsoft.jobq.data.remote.WeWorkRemotelySource
import com.jbsoft.jobq.data.remote.userAgentInterceptor
import com.jbsoft.jobq.data.settings.SettingsStore
import com.jbsoft.jobq.domain.ai.CvAnalyzer
import com.jbsoft.jobq.domain.ai.JobScorer
import com.jbsoft.jobq.domain.ai.LlmEngine
import com.jbsoft.jobq.domain.ai.RequestInterpreter
import com.jbsoft.jobq.domain.cv.CvImporter
import com.jbsoft.jobq.domain.repository.CvRepository
import com.jbsoft.jobq.domain.repository.CvTextExtractor
import com.jbsoft.jobq.domain.repository.JobRepository
import com.jbsoft.jobq.domain.repository.JobSource
import com.jbsoft.jobq.domain.repository.ModelRepository
import com.jbsoft.jobq.domain.repository.SettingsRepository
import com.jbsoft.jobq.domain.search.JobSearchCoordinator
import com.jbsoft.jobq.domain.search.KeywordRanker
import com.jbsoft.jobq.ui.detail.JobDetailViewModel
import com.jbsoft.jobq.ui.models.ModelsViewModel
import com.jbsoft.jobq.ui.profile.ProfileViewModel
import com.jbsoft.jobq.ui.saved.SavedViewModel
import com.jbsoft.jobq.ui.search.SearchViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.binds
import org.koin.dsl.module
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Outlives screens: searches and CV analysis keep running while the user navigates. */
private val AppScope = named("appScope")

val appModule = module {
	single {
		Json {
			ignoreUnknownKeys = true
			isLenient = true
			coerceInputValues = true
			explicitNulls = false
		}
	}
	single { OkHttpClient.Builder().addInterceptor(userAgentInterceptor).build() }
	single(AppScope) { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

	// Persistence
	single {
		Room.databaseBuilder<JobQDatabase>(androidContext(), "jobq.db")
			.setDriver(BundledSQLiteDriver())
			.build()
	}
	single { SettingsStore(androidContext()) } binds arrayOf(SettingsRepository::class)
	single<JobRepository> { JobRepositoryImpl(get<JobQDatabase>().jobDao(), get()) }
	single<CvRepository> { CvRepositoryImpl(get<JobQDatabase>().cvDao(), get()) }
	single<CvTextExtractor> { PdfCvTextExtractor(androidContext()) }

	// Job sources. To add one, implement JobSource and list it here.
	single<List<JobSource>> {
		fun retrofit(baseUrl: String) = Retrofit.Builder()
			.baseUrl(baseUrl)
			.client(get())
			.addConverterFactory(get<Json>().asConverterFactory("application/json".toMediaType()))
			.build()
		listOf(
			ArbeitnowSource(retrofit("https://www.arbeitnow.com/").create(ArbeitnowApi::class.java)),
			JobicySource(retrofit("https://jobicy.com/").create(JobicyApi::class.java)),
			WeWorkRemotelySource(get()),
		)
	}

	// On-device AI
	single<ModelRepository> { ModelRepositoryImpl(androidContext(), get()) }
	single<LlmEngine> { LlamaEngine(get()) }
	single { CvAnalyzer(get(), get()) }
	single { RequestInterpreter(get(), get()) }
	single { JobScorer(get(), get()) }
	single { KeywordRanker() }
	single { CvImporter(get(AppScope), get(), get(), get()) }
	single { JobSearchCoordinator(get(AppScope), get(), get(), get(), get(), get(), get()) }

	viewModel { SearchViewModel(get(), get(), get(), get(), get()) }
	viewModel { SavedViewModel(get()) }
	viewModel { ProfileViewModel(get(), get()) }
	viewModel { ModelsViewModel(get()) }
	viewModel { params -> JobDetailViewModel(params.get(), get(), get(), get()) }
}
