plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.baton.tv"
    // compileSdk/targetSdk 35: androidx.tv:tv-foundation:1.0.0's AAR metadata
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
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// androidx.tv:tv-material / tv-foundation 1.0.0 pull in their own, much
// newer transitive graph when left unpinned: tv-foundation's POM carries a
// hard dependency constraint on kotlin-stdlib 2.1.20 (incompatible with the
// Kotlin 1.9.24 compiler this project builds with -- "Incompatible classes
// were found in dependencies"), and drags compose-ui-tooling up to 1.10.x
// along with it, overriding the compose-bom 2024.06.00 pin. Excluding those
// transitive groups and re-declaring them ourselves keeps the whole graph on
// versions that were current when tv-material/tv-foundation 1.0.0 shipped.
// This is unrelated to compileSdk, which is 35 (see comment above).
val tvTransitiveExclusions = listOf(
    "androidx.compose.runtime",
    "androidx.compose.foundation",
    "androidx.compose.ui",
    "androidx.compose.animation",
    "androidx.activity",
    "androidx.lifecycle",
    "androidx.savedstate",
    "androidx.core",
    "org.jetbrains.kotlin",
)

dependencies {
    implementation(project(":player"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    implementation("androidx.tv:tv-material:1.0.0") {
        tvTransitiveExclusions.forEach { exclude(group = it) }
    }
    implementation("androidx.tv:tv-foundation:1.0.0") {
        tvTransitiveExclusions.forEach { exclude(group = it) }
    }

    debugImplementation("androidx.compose.ui:ui-tooling")
}
