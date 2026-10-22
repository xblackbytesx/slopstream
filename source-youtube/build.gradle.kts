plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(project(":core"))
    api(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// Offline tests only; the live checks against YouTube run with `make smoke`.
tasks.test {
    exclude("**/*SmokeTest*")
}

tasks.register<Test>("smokeTest") {
    description = "Live checks against YouTube: RSS feeds, the Shorts split and a stream plan."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*SmokeTest*")
    // -PsmokeVideo=<id> points the deep-range check at a video that misbehaves on a device.
    project.findProperty("smokeVideo")?.let { systemProperty("smoke.video", it.toString()) }
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped", "standardOut", "standardError")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
