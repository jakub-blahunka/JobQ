plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.kotlin.compose)
	alias(libs.plugins.kotlin.serialization)
	alias(libs.plugins.ksp)
}

android {
	namespace = "com.jbsoft.jobq"
	compileSdk {
		version = release(37)
	}

	defaultConfig {
		applicationId = "com.jbsoft.jobq"
		minSdk = 26
		targetSdk = 37
		versionCode = 1
		versionName = "1.0"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

		// Ship only the ABIs llama.cpp is built for (see llama/build.gradle.kts); on any other
		// the app would install and then fail to load the native library.
		ndk {
			abiFilters += (providers.gradleProperty("jobq.abis").orNull ?: "arm64-v8a").split(',').map { it.trim() }
		}
	}

	buildTypes {
		release {
			optimization {
				enable = false
			}
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
	buildFeatures {
		compose = true
	}
}

dependencies {
	implementation(project(":llama"))

	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.material.icons.core)
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.lifecycle.runtime.ktx)
	implementation(libs.androidx.lifecycle.runtime.compose)
	implementation(libs.androidx.lifecycle.viewmodel.compose)
	implementation(libs.androidx.lifecycle.viewmodel.navigation3)
	implementation(libs.androidx.navigation3.runtime)
	implementation(libs.androidx.navigation3.ui)

	implementation(platform(libs.koin.bom))
	implementation(libs.koin.android)
	implementation(libs.koin.androidx.compose)

	implementation(libs.androidx.room.runtime)
	implementation(libs.androidx.sqlite.bundled)
	ksp(libs.androidx.room.compiler)
	implementation(libs.androidx.datastore.preferences)
	implementation(libs.androidx.work.runtime)

	implementation(libs.retrofit)
	implementation(libs.retrofit.converter.kotlinx.serialization)
	implementation(libs.okhttp)
	implementation(libs.jsoup)
	implementation(libs.kotlinx.serialization.json)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.pdfbox.android)

	testImplementation(libs.junit)
	testImplementation(libs.kotlinx.coroutines.test)
	androidTestImplementation(platform(libs.androidx.compose.bom))
	androidTestImplementation(libs.androidx.compose.ui.test.junit4)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
	debugImplementation(libs.androidx.compose.ui.test.manifest)
	debugImplementation(libs.androidx.compose.ui.tooling)
}
