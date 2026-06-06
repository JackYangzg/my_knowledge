plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // P0-SLIM: google.gms.google.services plugin removed — app/google-services.json
    //   does not exist, no Firebase deps present, plugin is no-op. See design doc
    //   ~/.gstack/projects/JackYangzg-my_knowledge/yangzhiguo-main-design-20260606-153749.md (OQ-4)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.my.knowledge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.my.knowledge"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        // P1-N2: LlmHttpClient gates the OkHttp logging interceptor
        // on BuildConfig.DEBUG. AGP 9.x disables BuildConfig
        // generation by default, so opt back in.
        buildConfig = true
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    // P1-N2: HttpLoggingInterceptor is debug-only — release builds
    // get a smaller APK and no risk of leaking the Bearer token /
    // response body into logcat.
    // P0-SLIM: okhttp.logging changed debugImplementation → implementation.
    //   LlmHttpClient.kt:6 imports HttpLoggingInterceptor at file top
    //   (Kotlin resolves imports regardless of BuildConfig.DEBUG guard),
    //   so it must be on the release classpath. ~50 KB cost.
    //   Guarded by if (BuildConfig.DEBUG) so it's INSTALLED only in debug.
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.chinese)
    // P0-SLIM: pdf-viewer-fragment direct import removed; verify via
    //   `./gradlew :app:dependencies` whether PDFBox still transitively
    //   pulls it. If yes, no APK change; if no, ~1 MB savings.
    // P0-SLIM: MarkdownView-Android removed (0 references); project uses
    //   in-house ui/ComposeMarkdown.kt instead.
    kapt(libs.androidx.room.compiler)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // implementation(libs.firebase.ai) // Unused, enable when Firebase AI integration needed
    testImplementation(libs.junit)
    // org.json is part of the Android framework, but unit tests in
    // src/test/ run on the JVM, so we add the standalone artifact
    // explicitly. Without it, the analysis-JSON parsing tests would
    // throw RuntimeException("Stub!") the moment they call
    // JSONArray(string).
    testImplementation(libs.json)
    // P0-1: RebuildDebouncerTest uses kotlinx.coroutines.test
    // (TestScope + virtual time) to verify per-KB debounce + failure
    // isolation without spinning up the Room / WorkManager stack.
    testImplementation(libs.kotlinx.coroutines.test)
    // P0-2: AiGatewayStreamTest uses okhttp3 MockWebServer to drive
    // SSE streaming responses through the real AiGateway.streamJson
    // path, asserting cancellation, chunk accumulation, and parity
    // with chatJson.
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
