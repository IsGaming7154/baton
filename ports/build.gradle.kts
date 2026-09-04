import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Interface definitions and nothing else. Pure JVM, so that the seam between
// the arbitration policy and the machine it drives can be described without
// either side being able to reach through it: the director never learns that
// gain is a Media3 AudioProcessor, and the player never learns what a cue is.
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
    api(project(":director"))
}
