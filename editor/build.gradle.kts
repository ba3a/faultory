import org.gradle.api.tasks.JavaExec

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gdxCore)
    implementation(libs.gdxBackendLwjgl3)
    implementation(libs.gdxTools)
    implementation(libs.visUi)
    implementation(libs.kotlinxSerializationJson)
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    testImplementation(kotlin("test"))
    testRuntimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
}

application {
    mainClass = "com.faultory.editor.EditorLauncherKt"
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("assets")
}
