# JobQ

> Your CV. Your AI. Your device. Your next job.

JobQ is an Android app that finds job vacancies and ranks them against your CV using an
AI model that runs **on the phone**. There is no OpenAI or Gemini API, no AI backend and
no subscription. The network is used for two things only: fetching job listings and
downloading the model file once.

- [What the app does](#what-the-app-does)
- [How a search works](#how-a-search-works)
- [Privacy and where data lives](#privacy-and-where-data-lives)
- [Project layout](#project-layout)
- [Where is what](#where-is-what)
- [The on-device AI](#the-on-device-ai)
- [Job sources](#job-sources)
- [Building and running](#building-and-running)
- [Testing](#testing)
- [How to…](#how-to)
- [Known limits and what is not built yet](#known-limits-and-what-is-not-built-yet)

## What the app does

| Screen | What you do there |
| --- | --- |
| **Search** | Type a request in plain language, e.g. *"Senior Android Developer jobs in Vienna, €120k+, remote or hybrid"*. See how it was understood, watch progress, and browse ranked results. Before the first search it shows the two setup steps. |
| **Job detail** | The match score, which of the job's requirements you meet and which you lack, the full description, and a button that opens the original posting. Save the job here. |
| **Saved** | Jobs you saved. Available offline. |
| **My CV** | Import a PDF. The model extracts your name, headline, summary, years of experience, skills, roles, education and languages. Re-analyse or remove it. |
| **AI model** | Download, switch between and delete models. |

## How a search works

1. **Understand the request.** The model turns the text into structured criteria: job
   titles to search for, skills, location, minimum salary, remote/hybrid, seniority. If
   the model's answer is unusable, a regex-based reader does the same job.
2. **Refresh listings.** Every job source is fetched in parallel, at most once an hour
   per source and keyword. A source that cannot be reached is reported; the search
   continues with listings already on the phone.
3. **Shortlist by keyword.** All listings on the device are ranked by keyword relevance
   (rare terms weigh more, title matches weigh more than description matches, jobs in the
   wrong place are demoted). Up to 40 become results.
4. **Score with the model.** The best 8 with a search term in the title are compared with
   the CV one by one. For each, the model lists the job's key requirements and marks each
   as met or not, writes a one-sentence verdict and gives a score.
5. **Show results.** Model-scored jobs appear under *Best matches*; the rest under *More
   listings by keyword*, where opening one lets you have the model analyse it on demand.

The final score is not just the model's number. Small models are poor at putting one
number on a fit, but good at the itemised judgement, so the score is 60% the share of
requirements met and 40% the model's number. A skill the CV plainly lists is counted as
met even if the model says otherwise, and a job in the wrong location loses 15 points and
says so. See `JobScorer`.

A search runs in an app-wide scope, so it continues if you leave the screen. It takes one
to several minutes on a phone.

## Privacy and where data lives

Everything is in the app's private storage (`/data/data/com.jbsoft.jobq/`). Nothing about
the CV is ever sent anywhere.

| Data | Where | Notes |
| --- | --- | --- |
| The CV: original PDF, extracted text, extracted profile | `files/documents/cv/cv.pdf.enc` and `cv.json.enc` | Encrypted with AES-256-GCM. The key is generated in the Android Keystore and never leaves it. Not in the database. |
| Job listings, saved jobs, match results | Room database `databases/jobq.db` | Unsaved listings older than 30 days are dropped. |
| Model files | `files/models/*.gguf` | 0.8–2.5 GB each. |
| Active model, last search text | DataStore `files/datastore/settings.preferences_pb` | |

Backup is off: `allowBackup="false"`, and `data_extraction_rules.xml` excludes everything
from cloud backup. A phone-to-phone transfer carries the job data but not the models and
not the CV, because the CV's key does not move to another device. Removing the CV deletes
both files. Uninstalling the app or a factory reset destroys the key.

The model's replies contain CV content, so they are logged only on request:
`adb shell setprop log.tag.JobQLlm DEBUG`.

## Project layout

Two Gradle modules:

- **`:app`** — the application, in three layers under `com.jbsoft.jobq`:
  - `domain` — models, repository interfaces and the logic of the app. Pure Kotlin, no
    Android imports, unit-tested on the JVM.
  - `data` — implementations of the domain's interfaces: Room, files, network, llama.cpp.
  - `ui` — Compose screens and their ViewModels.
  - `di` — the Koin module that wires the three together.
- **`:llama`** — a small Android library that wraps llama.cpp behind a Kotlin class via JNI.

Stack: Kotlin, Jetpack Compose (Material 3), Navigation 3, Koin, Room 3 with the bundled
SQLite driver, DataStore, WorkManager, Retrofit + OkHttp + kotlinx.serialization, Jsoup,
PdfBox-Android, llama.cpp. Versions are in `gradle/libs.versions.toml`.

## Where is what

Paths are relative to `app/src/main/java/com/jbsoft/jobq/` unless they start at the
repository root.

### App entry

| File | What it holds |
| --- | --- |
| `JobQApplication.kt` | Starts Koin. |
| `MainActivity.kt` | The single activity; sets the theme and the navigation host. |
| `di/AppModule.kt` | Every dependency: database, CV storage, HTTP client, job sources, AI classes, ViewModels. **The list of job sources is here.** |

### Domain

| File | What it holds |
| --- | --- |
| `domain/model/Job.kt` | `JobListing`, `JobMatch`, `RankedJob`, `SearchResults`. |
| `domain/model/Cv.kt` | `CvDocument` (file name, text, profile) and `CvProfile`. |
| `domain/model/SearchCriteria.kt` | A search request, structured. |
| `domain/model/AiModel.kt` | A downloadable model and its download state. |
| `domain/repository/Repositories.kt` | All interfaces the data layer implements: `JobSource`, `JobRepository`, `CvRepository`, `CvTextExtractor`, `ModelRepository`, `SettingsRepository`. |
| `domain/ai/LlmEngine.kt` | The contract for on-device text generation, and its errors. |
| `domain/ai/JsonShape.kt` | Describes the JSON a prompt must return and turns it into a llama.cpp grammar. |
| `domain/ai/CvAnalyzer.kt` | Prompt and parsing for CV → `CvProfile`. |
| `domain/ai/RequestInterpreter.kt` | Prompt and parsing for request → `SearchCriteria`, plus the model-free fallback `heuristicCriteria`. |
| `domain/ai/JobScorer.kt` | Prompt and parsing for job + CV → `JobMatch`, and the score blending. |
| `domain/search/KeywordRanker.kt` | Keyword relevance used for the shortlist. |
| `domain/search/JobSearchCoordinator.kt` | Runs a search end to end and exposes its progress. Tunables (shortlist size, refresh interval, retention) are constants at the bottom. |
| `domain/cv/CvImporter.kt` | Imports, analyses, re-analyses and removes the CV, and exposes progress. |

### Data

| File | What it holds |
| --- | --- |
| `data/ai/LlamaEngine.kt` | `LlmEngine` on top of the `:llama` module: keeps the active model loaded, runs one request at a time, relays coroutine cancellation to native code. |
| `data/cv/FileCvRepository.kt` | Stores the CV as two encrypted files. |
| `data/cv/CvCipher.kt` | `KeystoreCipher`: AES-256-GCM with an Android Keystore key. |
| `data/cv/PdfCvTextExtractor.kt` | Reads a PDF picked with the system file picker and extracts its text with PdfBox. |
| `data/local/Entities.kt` | Room tables: `jobs`, `searches`, `job_matches`, `source_fetches`. |
| `data/local/JobQDatabase.kt` | The DAO, type converters, database class and migrations. |
| `data/local/JobRepositoryImpl.kt` | `JobRepository` on Room, and entity ↔ domain mapping. |
| `data/model/ModelCatalog.kt` | **The list of downloadable models**: URLs, sizes, licences. |
| `data/model/ModelDownloadWorker.kt` | WorkManager job that downloads a model in the foreground, resumes interrupted downloads, and verifies size and file type. |
| `data/model/ModelRepositoryImpl.kt` | Download, cancel, delete, select; combines files, WorkManager state and settings into a model list. |
| `data/remote/ArbeitnowSource.kt`, `JobicySource.kt`, `WeWorkRemotelySource.kt` | One job source each: the API definition, its DTOs and the mapping to `JobListing`. |
| `data/remote/HtmlText.kt` | HTML description → plain text. |
| `data/remote/Network.kt` | The User-Agent header and a wrapper that reports every feed failure as `IOException`. |
| `data/settings/SettingsStore.kt` | DataStore: active model id and last search text. |

### UI

| File | What it holds |
| --- | --- |
| `ui/navigation/JobQNavigation.kt` | Navigation 3 keys, the back stack, the bottom bar and the screen for each key. |
| `ui/search/` | `SearchScreen` and `SearchViewModel`. |
| `ui/detail/` | `JobDetailScreen` and `JobDetailViewModel`. |
| `ui/saved/SavedScreen.kt` | Saved jobs screen and its ViewModel. |
| `ui/profile/ProfileScreen.kt` | CV screen and its ViewModel. |
| `ui/models/ModelsScreen.kt` | Model screen and its ViewModel. |
| `ui/components/JobComponents.kt` | Shared pieces: `JobCard`, `ScoreRing`, `Pill`, `EmptyState`. |
| `ui/theme/` | Colours, typography, theme. |

### Outside the Kotlin sources

| Path (from the repository root) | What it holds |
| --- | --- |
| `app/src/main/res/values/strings.xml` | All user-facing text. |
| `app/src/main/AndroidManifest.xml` | Permissions, backup settings, the foreground-service declaration for downloads. |
| `app/src/main/res/xml/data_extraction_rules.xml` | What backup and device transfer may copy. |
| `app/src/test/` | Unit tests. |
| `llama/src/main/cpp/CMakeLists.txt` | Fetches and builds llama.cpp. **The pinned llama.cpp version is here.** |
| `llama/src/main/cpp/llama_jni.cpp` | The JNI bridge: load, generate, cancel, free. |
| `llama/src/main/java/.../LlamaModel.kt` | The Kotlin API of the `:llama` module. |
| `gradle/libs.versions.toml` | Dependency versions. |
| `.github/workflows/build.yml` | CI: unit tests and a debug APK. |

## The on-device AI

**Models** are 4-bit GGUF files downloaded from Hugging Face on demand, never bundled:

| Model | Size | Notes |
| --- | --- | --- |
| Llama 3.2 3B | 2.0 GB | Recommended. Needs roughly 6 GB of device RAM. |
| Gemma 3 4B | 2.5 GB | |
| Phi-4 mini | 2.5 GB | |
| Gemma 3 1B | 0.8 GB | For phones with little RAM; noticeably rougher. |

Check each model's licence before distributing the app; the links are in `ModelCatalog`.

**Inference** runs on the CPU through llama.cpp with a 4096-token context. One request
runs at a time. Cancelling the calling coroutine stops generation.

**Structured output.** Every prompt asks for JSON, and the reply is constrained by a
grammar generated from a `JsonShape`, so the model can only emit compact JSON with exactly
the declared keys in the declared order. The grammar deliberately allows no line breaks or
indentation: when a grammar permits a newline but not the indentation a model wants after
`[`, the likeliest token left is `]`, and every list comes back empty.

**llama.cpp** is not vendored. CMake downloads the pinned release tarball at configure
time and verifies its SHA-256. Its C API changes between releases, so upgrading means
re-checking `llama_jni.cpp` against `include/llama.h`.

## Job sources

| Source | Feed | Notes |
| --- | --- | --- |
| Arbeitnow | Public JSON API | Mostly German and European jobs. No search parameter, so the newest 600 listings are fetched and filtered on the device. |
| Jobicy | Public JSON API | Remote jobs. Searched by one keyword (the API requires a tag of 3–20 characters). |
| We Work Remotely | Public RSS feed | Remote jobs. |

Only feeds that the provider publishes for reuse are used. Every listing keeps its
original URL, shows its source, and "Open original posting" sends the user there to
apply. Each source is refreshed at most once an hour. The app identifies itself with its
own User-Agent.

Not included, deliberately: LinkedIn and Indeed (they prohibit automated access), boards
that would need HTML scraping (karriere.at, StepStone, willhaben and similar), and
RemoteOK (its robots.txt content signals are ambiguous about AI use). Check a provider's
terms before adding it.

## Building and running

Requirements: Android Studio or the command line, a JDK (Gradle provisions the one it
needs), and an Android SDK with licences accepted. On first build the Android Gradle
Plugin installs the NDK and CMake, and CMake downloads llama.cpp (about 40 MB); compiling
it takes a few minutes.

```sh
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # unit tests
./gradlew installDebug           # install on a connected device or emulator
```

**ABIs.** Only `arm64-v8a` is built by default. For an x86 emulator:

```sh
./gradlew assembleDebug -Pjobq.abis=arm64-v8a,x86_64
```

**Emulator.** Give it enough RAM for the model, e.g. 6 GB:
`emulator -avd <name> -memory 6144`. With 4 GB the system kills the app while it loads
the 3B model.

**CI.** `.github/workflows/build.yml` runs the unit tests and builds a debug APK. It does
not run on its own: start it from the repository's Actions tab (Build → Run workflow).
The APK is attached to the run as the `jobq-debug-apk` artifact.

## Testing

Unit tests run on the JVM and cover the logic that does not need a device:

| Test | Covers |
| --- | --- |
| `JsonShapeTest` | The generated grammars. |
| `HeuristicCriteriaTest` | The model-free reading of a search request. |
| `KeywordRankerTest` | Shortlisting. |
| `JobSearchCoordinatorTest` | The search pipeline with a fake model, fake sources and fake storage: shortlisting, score blending, unreachable sources, refresh interval, failure cases. |
| `FileCvRepositoryTest` | CV storage: persistence, nothing readable on disk, replacement, removal, a lost key. |
| `FeedParsingTest` | HTML → text and the RSS parser. |

There are no instrumented tests. Model loading, inference, the Keystore encryption, the
PDF picker and the download worker have been checked by hand on an emulator, not by
automated tests.

## How to…

**Add a job source.** Implement `JobSource` in `data/remote/` (see `ArbeitnowSource` for a
JSON API, `WeWorkRemotelySource` for RSS), map the feed to `JobListing` with a stable
`id` of the form `<sourceId>:<id at the source>`, and add it to the list in
`di/AppModule.kt`. Throw `IOException` on failure (wrap the call in `ioCall`).

**Add a model.** Add an entry to `ModelCatalog` with the exact file size in bytes (it is
used to verify the download) and the licence.

**Change a prompt or what the model returns.** Edit the prompt and the `JsonShape` next to
it in `CvAnalyzer`, `RequestInterpreter` or `JobScorer`. Turn on reply logging (see
[Privacy](#privacy-and-where-data-lives)) to see what the model actually answers.

**Change the database schema.** Edit `Entities.kt`, raise `version` in `JobQDatabase` and
add a `Migration` next to `MIGRATION_1_2`, then register it in `di/AppModule.kt`.

**Upgrade llama.cpp.** Change the tag and hash in `llama/src/main/cpp/CMakeLists.txt` and
re-check `llama_jni.cpp` against the new `llama.h`.

## Known limits and what is not built yet

- **Speed.** Scoring is CPU-only and takes roughly 10–15 seconds per job on a fast
  machine, longer on a phone. Only 8 jobs are scored per search for that reason.
- **Memory.** The recommended model needs about 6 GB of device RAM.
- **CPU.** The arm64 build assumes ARMv8.2 with dot-product instructions (phones from
  about 2018 on) and will crash on older CPUs.
- **Small models make mistakes.** Requirement lists and verdicts are usually sensible but
  not reliable; treat the score as a ranking aid.
- **Scanned CVs** have no text layer and are rejected; there is no OCR.
- **Salary** is passed to the model as text but not compared numerically.
- **English only** UI and prompts; listings in other languages are matched by keyword.
- **Not built:** job alerts, duplicate detection across sources, multiple CV profiles, CV
  tailoring, interview preparation, application tracking, a privacy policy, release
  signing and R8 configuration.

## Licence

MIT — see [LICENSE](LICENSE). The models and llama.cpp have their own licences.
