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
    // The same for Transcribe and S3: only their sync clients are used.
    implementation("software.amazon.awssdk:transcribe:2.54.19") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:s3:2.54.19") {
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    // And for Bedrock Runtime: only the sync BedrockRuntimeClient's Converse is used.
    implementation("software.amazon.awssdk:bedrockruntime:2.54.19") {
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

// One live Transcribe job, priced by the spend guard at AwsPrices.TRANSCRIBE_BATCH_USD_PER_SECOND
// (89 s of demo clip: $0.0089), into the real ledger. The bucket must
// already exist in us-east-1. Never wired into build or test:
//   gradlew :pipeline:transcribeSmoke --args=<media>,<bucket>,<out.json>,<duration ms>
tasks.register<JavaExec>("transcribeSmoke") {
    description = "Runs ONE paid Amazon Transcribe job through the spend guard and writes its raw output JSON."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.baton.pipeline.live.TranscribeSmokeKt")
}

// A live Nova drafting run over one clip: the real builder, a transcript from disk and simulated
// synthesis, so only Nova is billed, each call through the spend guard (it prints the run's worst
// case first). Never wired into build or test:
//   gradlew :pipeline:novaSmoke --args=<media>,<transcript.json>,<model id>,<duration ms>,<out track.json>
tasks.register<JavaExec>("novaSmoke") {
    description = "Drafts a track with live Amazon Bedrock Nova calls through the spend guard."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.baton.pipeline.live.NovaSmokeKt")
}

// The same run with the Gemini API's free tier drafting in Nova's place (AWS declined Bedrock
// access for this account, friction-log entry 21). Needs GEMINI_API_KEY. Never wired into build or test:
//   gradlew :pipeline:geminiSmoke --args=<media>,<transcript.json>,<model id>,<duration ms>,<out track.json>
tasks.register<JavaExec>("geminiSmoke") {
    description = "Drafts a track with live Gemini API calls on the free tier."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.baton.pipeline.live.GeminiSmokeKt")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Lets the golden-file test rewrite its expectation on demand. Gradle's own
    // -D does not reach the forked test JVM, so it is forwarded explicitly.
    systemProperty("baton.updateGolden", providers.systemProperty("baton.updateGolden").getOrElse("false"))
}
