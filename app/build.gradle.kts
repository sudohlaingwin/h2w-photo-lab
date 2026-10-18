plugins {
    alias(libs.plugins.agp)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lumora.photoeditor"
    compileSdk = 35

    buildToolsVersion = "35.0.0"
    // Phase 0 of the approved RAW program: NDK r27c installed into the local
    // toolchain (~/workspace/android/toolchain/android-sdk/ndk/27.2.12479018).
    // Pinned so every build uses the same toolchain; resolved from the local
    // SDK dir, so --offline builds keep working.
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "com.lumora.photoeditor"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Stable debug signing key (2026-10-18): the default ~/.android/debug.keystore
    // lives outside the workspace and is regenerated whenever the VM is
    // replaced — each regen changes the APK signature, so install-over-the-top
    // fails and the user must uninstall, wiping his Gemini API key and all app
    // data. debug.keystore is pinned in the repo (same well-known android/
    // androiddebugkey debug credentials AGP uses; a debug key is not a
    // secret) so every future build carries the same signature forever.
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            // Debug APKs are delivered directly to the user's arm64 phone via
            // Drive, not the Play Store. Ship arm64-v8a only: drops ~30MB of
            // unused native ABIs (MediaPipe .so) and speeds downloads.
            ndk { abiFilters += "arm64-v8a" }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // JNI smoke module (Phase 0 of the approved RAW program): proves
    // externalNativeBuild + CMake work in this environment. The .so is
    // arm64-v8a-only like the rest of the debug deliverable.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Kotlin/Native linkdata duplicated across artifacts; not needed at runtime.
            excludes += "**/*.knm"
            // Kotlin multiplatform metadata manifests duplicated across artifacts.
            excludes += "**/commonMain/default/manifest"
            excludes += "**/nativeMain/default/manifest"
            excludes += "**/nonJvmMain/default/**"
            excludes += "**/commonMain/default/linkdata/**"
            excludes += "**/nativeMain/default/linkdata/**"
            excludes += "META-INF/kotlin-project-structure-metadata.json"
            // MediaPipe / TFLite native libs: keep first occurrence wins
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

// androidx.navigation 2.7.0 pulls in the ancient collection-ktx:1.2.0 whose
// classes duplicate collection-jvm:1.4.0. The newer artifact supersedes it.
// Room 2.6.1 pulls lifecycle-viewmodel-ktx:2.6.1 which duplicates the
// renamed lifecycle-viewmodel-android:2.8.6.
configurations.all {
    exclude(group = "androidx.collection", module = "collection-ktx")
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.core.ktx)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.exifinterface)
    // Phase C#8 slice 2: WorkManager for the auto-sync engine. Two of
    // work-runtime's transitive edges are excluded: lifecycle-livedata and
    // lifecycle-service exist at no other version in this graph (nothing
    // here observes WorkManager via LiveData — the scheduler uses futures),
    // and their 2.5.1 artifacts are not vendored. Everything else resolves
    // to the project's newer cached versions (core, room, sqlite) or the
    // vendored ones (startup-runtime, listenablefuture,
    // annotation-experimental) in ~/workspace/android/local-repo.
    implementation(libs.work.runtime.ktx) {
        exclude(group = "androidx.lifecycle", module = "lifecycle-livedata")
        exclude(group = "androidx.lifecycle", module = "lifecycle-service")
    }

    testImplementation(libs.junit)
}

/**
 * Runs the debug unit tests on the plain JVM through JUnitCore.
 *
 * The Gradle Test worker is broken in this environment: every executor dies
 * with an internal NPE (`TestResultProcessor.resultProcessor is null` in
 * `SuiteTestClassProcessor.stop`) before running a single test, so
 * `:app:testDebugUnitTest` can never produce results here (verified again
 * 2026-10-18; cleaning the worker tmp dir and dropping --offline did not
 * help). This task is the working verification path instead: it runs the
 * exact same compiled test classes, with the exact same test runtime
 * classpath, but drives them with `org.junit.runner.JUnitCore` in a plain
 * `JavaExec`, which has no worker protocol to break.
 *
 * Every test class under src/test is JVM-safe (pure-Kotlin assertions;
 * AiMaskStoreTest only mentions Bitmap in comments), so no Robolectric or
 * android runtime behavior is needed. The platform android.jar rides along
 * on the test runtime classpath so classes that merely *reference* android
 * types still link.
 *
 * Usage: ./gradlew :app:runJvmUnitTests --offline
 */
tasks.register<JavaExec>("runJvmUnitTests") {
    group = "verification"
    description = "Runs all debug unit tests on the plain JVM via JUnitCore (works around the broken Gradle Test worker in this environment)."
    dependsOn("compileDebugUnitTestKotlin")
    val testClassesDir = layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")
    classpath(
        testClassesDir,
        configurations.named("debugUnitTestRuntimeClasspath"),
    )
    mainClass.set("org.junit.runner.JUnitCore")
    doFirst {
        val dir = testClassesDir.get().asFile
        val names = dir.walkTopDown()
            .filter { f -> f.isFile && f.name.endsWith("Test.class") && '$' !in f.name }
            .map { f ->
                f.relativeTo(dir).invariantSeparatorsPath.removeSuffix(".class").replace('/', '.')
            }
            .sorted()
            .toList()
        if (names.isEmpty()) throw GradleException("runJvmUnitTests: no *Test classes found under $dir")
        args(names)
        println("runJvmUnitTests: running ${names.size} test classes: ${names.joinToString()}")
    }
}
