import org.gradle.process.CommandLineArgumentProvider

// Stand-in for your `system-tests/` module. In a real project this lives in the
// repository under test, alongside its environments/ and compose/ directories.

plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(project(":junit"))
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

// Nothing here but system tests, so the default `test` task would only ever fail:
// there is no environment during a plain `./gradlew build`.
tasks.test { enabled = false }

// Discovery without execution, so `terra run --tag x` knows which environments it
// actually needs. Needs no containers.
val systemTestPlan = tasks.register<JavaExec>("systemTestPlan") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "terra.Discover"
    // Bound to a local first: a lambda that reaches for `sourceSets` captures the
    // build script itself, which the configuration cache cannot serialize.
    val classesDirs = sourceSets["test"].output.classesDirs
    argumentProviders.add(CommandLineArgumentProvider { listOf(classesDirs.asPath) })
    listOf("TERRA_TAGS", "TERRA_EXCLUDE_TAGS", "TERRA_SHARD").forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}

// Markdown from the @SuiteDoc/@TestDoc annotations on the tests. Reads compiled
// classes, so it needs no containers either — and it is committed, so a change to
// what a test claims to do shows up in the diff next to the change to the test.
val testDocs = tasks.register<JavaExec>("testDocs") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "terra.Docs"
    val classesDirs = sourceSets["test"].output.classesDirs
    val docsDir = layout.projectDirectory.dir("docs").asFile.path
    argumentProviders.add(CommandLineArgumentProvider { listOf(docsDir, classesDirs.asPath) })
}

val systemTest = tasks.register<Test>("systemTest") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }

    // A system test's real input is a live environment, which Gradle cannot see or
    // hash. Without this, a task that passed once is UP-TO-DATE forever — including
    // against a *different* environment, which is silently no test at all.
    outputs.upToDateWhen { false }
    listOf(
        "TERRA_ENV", "TERRA_ENV_FILE", "TERRA_SHARD", "TERRA_RUN_ID",
        "TERRA_TAGS", "TERRA_EXCLUDE_TAGS",
    ).forEach { name ->
        providers.environmentVariable(name).orNull?.let { environment(name, it) }
    }
}
