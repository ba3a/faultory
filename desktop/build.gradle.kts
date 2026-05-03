import org.gradle.api.tasks.JavaExec
import org.gradle.jvm.application.tasks.CreateStartScripts

plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(libs.gdxBackendLwjgl3)
    runtimeOnly("com.badlogicgames.gdx:gdx-platform:${libs.versions.gdx.get()}:natives-desktop")
    runtimeOnly("com.badlogicgames.gdx:gdx-freetype-platform:${libs.versions.gdx.get()}:natives-desktop")
}

application {
    mainClass = "com.faultory.desktop.DesktopLauncherKt"
}

sourceSets {
    main {
        resources.srcDir(rootProject.file("assets"))
    }
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.file("assets")
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        windowsScript.writeText(
            windowsScript.readText().replace(
                Regex("""^set CLASSPATH=.*$""", RegexOption.MULTILINE),
                Regex.escapeReplacement("""set CLASSPATH=%APP_HOME%\lib\*""")
            )
        )
    }
}
