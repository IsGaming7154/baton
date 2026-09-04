import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The director is a pure-JVM module on purpose. It has no Android dependency,
// no framework dependency, and -- with the single exception below -- no
// production dependency at all beyond the Kotlin standard library, which is
// what makes the arbitration policy provable with plain JVM tests, on any
// machine, with no device attached.
//
// The exception is kotlinx-serialization. The DescriptionTrack codec has to
// live beside the model it encodes: the app reads a track and the pipeline
// writes one, and neither of those two halves may depend on the other. That
// makes this module the only place the codec can go. It is a deliberate
// exception and not a precedent -- nothing else here acquires a dependency.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-property:5.9.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
