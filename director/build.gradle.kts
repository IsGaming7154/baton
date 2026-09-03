import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The director is a pure-JVM module on purpose. It has no Android dependency,
// no framework dependency, and no production dependency at all beyond the
// Kotlin standard library -- which is what makes the arbitration policy
// provable with plain JVM tests, on any machine, with no device attached.
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
    testImplementation("io.kotest:kotest-property:5.9.1")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
