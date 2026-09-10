plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.baton.player"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
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
}

dependencies {
    api(project(":ports"))
    implementation(project(":loudness"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")

    api("androidx.media3:media3-exoplayer:1.4.1")
    api("androidx.media3:media3-ui:1.4.1")
    api("androidx.media3:media3-common:1.4.1")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
