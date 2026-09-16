import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// The offline track builder. Pure JVM: it runs on a workstation, never on the
// device.
//
// :pipeline must never become a dependency of :app. It is a build-time tool,
// and the live slice adds the AWS SDKs to it -- Transcribe, Bedrock, Polly --
// which have no business inside a 1 GB Fire TV Stick's APK. The dependency
// arrow points one way only: :pipeline -> :director.
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
    api(project(":director"))

    implementation("software.amazon.awssdk:polly:2.54.19") {
        // Netty backs only the async client. The sync PollyClient finds its HTTP client
        // through the sync service loader, which picks Apache 5 (sdk-core,
        // ClasspathSdkHttpServiceProvider).
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }

    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
}

// One live Polly call, about $0.001, into the real ledger. Never wired into build or test:
//   gradlew :pipeline:pollySmoke --args=build/polly-smoke.wav
tasks.register<JavaExec>("pollySmoke") {
    description = "Makes ONE paid Amazon Polly call through the spend guard and writes it as a WAV."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.baton.pipeline.live.PollySmokeKt")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Lets the golden-file test rewrite its expectation on demand. Gradle's own
    // -D does not reach the forked test JVM, so it is forwarded explicitly.
    systemProperty("baton.updateGolden", providers.systemProperty("baton.updateGolden").getOrElse("false"))
}
