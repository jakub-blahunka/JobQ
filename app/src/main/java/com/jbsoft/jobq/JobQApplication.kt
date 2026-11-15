package com.jbsoft.jobq

import android.app.Application
import com.jbsoft.jobq.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class JobQApplication : Application() {
	override fun onCreate() {
		super.onCreate()
		startKoin {
			androidContext(this@JobQApplication)
			modules(appModule)
		}
	}
}
