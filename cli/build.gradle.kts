plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":junit"))          // for the shared descriptor
    implementation("org.yaml:snakeyaml:2.6")
}

application { mainClass = "terra.cli.MainKt" }
