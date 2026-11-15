plugins {
	alias(libs.plugins.android.library)
}

// ABIs to build llama.cpp for. Override with -Pjobq.abis=arm64-v8a,x86_64 (e.g. for an x86 emulator).
val nativeAbis = (providers.gradleProperty("jobq.abis").orNull ?: "arm64-v8a")
	.split(',').map { it.trim() }.filter { it.isNotEmpty() }

android {
	namespace = "com.jbsoft.jobq.llama"
	compileSdk {
		version = release(37)
	}

	defaultConfig {
		minSdk = 26
		consumerProguardFiles("consumer-rules.pro")

		ndk {
			abiFilters += nativeAbis
		}
		externalNativeBuild {
			cmake {
				// Always optimise: an unoptimised llama.cpp is unusably slow, even in debug builds.
				arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
			}
		}
	}

	externalNativeBuild {
		cmake {
			path = file("src/main/cpp/CMakeLists.txt")
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
}
