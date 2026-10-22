plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// Offline tests only; the live checks against your Floatplane account run with
// `make smoke-floatplane`.
tasks.test {
    exclude("**/*SmokeTest*")
}

tasks.register<Test>("smokeTest") {
    description = "Live checks against Floatplane with your own account: login, subscriptions, posts, delivery."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*SmokeTest*")
    // Credentials come from the environment (see FloatplaneSmokeTest); the token cache lives
    // in the git-ignored .secrets/ at the repo root.
    listOf("FP_CLIENT_ID", "FP_BEARER", "FP_SESSION").forEach { k -> System.getenv(k)?.let { environment(k, it) } }
    systemProperty("fp.secrets", rootProject.file(".secrets").absolutePath)
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
