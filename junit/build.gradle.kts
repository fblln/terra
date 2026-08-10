// java-library, because these leak onto the consuming project's test compile
// classpath: a test writes `eventually { … }` and `@SharedEnvTest` directly.
plugins {
    kotlin("jvm")
    `java-library`
}

// 17 rather than 21 so the harness builds on whatever JDK is already installed.
kotlin { jvmToolchain(17) }

dependencies {
    api(platform("org.junit:junit-bom:5.11.4"))
    api("org.junit.jupiter:junit-jupiter-api")
    api("org.junit.platform:junit-platform-launcher")
    api("org.awaitility:awaitility:4.2.2")
    api("org.apache.kafka:kafka-clients:3.8.1")
    api("org.mongodb:mongodb-driver-sync:5.2.1")
    api("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.2")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")
}
