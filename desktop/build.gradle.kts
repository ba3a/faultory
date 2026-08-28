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
    // `-D` on the gradlew command line sets the Gradle *daemon's* properties, not this JavaExec's -
    // forward the faultory.* ones through explicitly so `-Dfaultory.capture=...` (and the
    // pre-existing `-Dfaultory.debug.shapes`) actually reach the game.
    systemProperties(
        System.getProperties().entries
            .mapNotNull { (key, value) -> (key as? String)?.takeIf { it.startsWith("faultory.") }?.let { it to value } }
            .toMap()
    )
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
