// java-library, because these leak onto the consuming project's test compile
// classpath: a test writes `eventually { … }` and `@SharedEnvTest` directly.
plugins {
    kotlin("jvm")
    `java-library`
}

// 17 rather than 21 so the harness builds on whatever JDK is already installed.
kotlin { jvmToolchain(17) }

dependencies {
    api(platform("org.junit:junit-bom:6.1.3"))
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.junit.platform:junit-platform-launcher")
    api("org.awaitility:awaitility:4.3.0")
    api("org.apache.kafka:kafka-clients:4.3.1")
    api("org.mongodb:mongodb-driver-sync:5.9.1")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.1")

    // Test documentation: the @TestDoc/@SuiteDoc annotations and the Markdown writer
    // behind `terra.Docs`. Packaged as a Maven plugin, but the jar is an ordinary one
    // and the parts we use are plain reflection — non-transitive drops maven-plugin-api
    // and the rest of the Mojo's machinery, which we do not run.
    api("io.skodjob:test-docs-generator-maven-plugin:0.6.0") { isTransitive = false }
}
