plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.baton.tv"
    // compileSdk/targetSdk 35: the androidx.tv artifacts' AAR metadata
    // hard-requires minCompileSdk=35 and AGP>=8.6.0 -- baked into the
    // artifact itself, unrelated to any dependency version we control. See
    // FRICTION_LOG.md. minSdk stays 28: that is the only figure that governs
    // Fire TV Stick HD installability.
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.baton.tv"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // Robolectric composition-tree tests (ShellNarrationTest) need the merged
    // resources and manifest.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Media3 opens the subtitle sidecar with openRawResourceFd, which fails on a
    // compressed APK entry; aapt deflates .srt by default, so playback never started.
    androidResources {
        noCompress += "srt"
    }
}

// androidx.tv:tv-material 1.0.0 pulls in a much newer transitive graph when
// left unpinned, dragging compose artifacts up to 1.10.x and overriding the
// compose-bom 2024.06.00 pin. Excluding those groups and re-declaring them
// ourselves keeps the graph on versions current when tv-material 1.0.0
// shipped. Unrelated to compileSdk, which is 35 (see comment above).
val tvTransitiveExclusions = listOf(
    "androidx.compose.runtime",
    "androidx.compose.foundation",
    "androidx.compose.ui",
    "androidx.compose.animation",
    "androidx.activity",
    "androidx.lifecycle",
    "androidx.savedstate",
    "androidx.core",
)

dependencies {
    implementation(project(":player"))
    implementation(project(":speech"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    implementation("androidx.tv:tv-material:1.0.0") {
        tvTransitiveExclusions.forEach { exclude(group = it) }
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
    // Declares the empty ComponentActivity that createComposeRule() launches.
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.13")
    // The JUnit 4 Robolectric tests run on the same JUnit Platform as kotest;
    // 5.8.2 matches the platform 1.8.2 kotest 5.9.1 requires.
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.8.2")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // The Robolectric composition-tree tests launch the ComponentActivity that
    // ui-test-manifest declares, and ui-test-manifest is debug-only, so the
    // release unit-test variant has no activity to launch. They run under
    // testDebugUnitTest.
    if (name == "testReleaseUnitTest") {
        filter {
            excludeTestsMatching("dev.baton.tv.ShellNarrationTest")
            excludeTestsMatching("dev.baton.tv.SyntheticVoiceNoticeTest")
            excludeTestsMatching("dev.baton.tv.AccessServicesRowTest")
        }
    }
}
