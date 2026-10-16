import org.gradle.api.tasks.JavaExec

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinxSerializationJson)
    implementation(libs.mcpKotlinSdk)
    testImplementation(kotlin("test"))
}

application {
    mainClass = "com.faultory.balance.BalanceMainKt"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.file(".")
}
