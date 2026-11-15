package com.jbsoft.jobq.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import java.io.IOException

/** Identifies the app honestly to the feeds it reads. */
val userAgentInterceptor = Interceptor { chain ->
	chain.proceed(chain.request().newBuilder().header("User-Agent", "JobQ/1.0 (Android job-search app)").build())
}

/** Runs a feed call off the main thread and reports every failure, including a malformed feed, as [IOException]. */
suspend fun <T> ioCall(block: suspend () -> T): T = withContext(Dispatchers.IO) {
	try {
		block()
	} catch (e: CancellationException) {
		throw e
	} catch (e: IOException) {
		throw e
	} catch (e: Exception) {
		throw IOException(e.message ?: "Feed could not be read", e)
	}
}
