import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// ITU-R BS.1770-4 loudness and true peak. Pure JVM, Kotlin standard library
// only, and no knowledge of anything else in this repo.
//
// It is its own module because two halves of the system need the same
// measurement and neither may depend on the other. :speech normalises a rendered
// utterance to the delivery target on the device; :pipeline measures programme
// loudness on a workstation; :player meters the content bed live in the render
// chain. :speech and :player are Android libraries, :pipeline is a build-time
// tool that must never reach an APK, so the arrow can only point down into a
// module all three can see. Same argument the :director comment makes about the
// track codec, and the same conclusion.
//
// Nothing here knows what a cue is, what a duck is, or what the numbers get
// compared against. It converts samples into decibels and stops.
plugins {
    id("org.jetbrains.kotlin.jvm")
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
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
