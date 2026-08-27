// The code in this file is a convention plugin - a Gradle mechanism for sharing reusable build logic.
// `buildSrc` is a Gradle-recognized directory and every plugin there will be easily available in the rest of the build.
package buildsrc.convention

import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    kotlin("jvm")
    id("io.gitlab.arturbosch.detekt")
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()

    testLogging {
        events(
            TestLogEvent.FAILED,
            TestLogEvent.PASSED,
            TestLogEvent.SKIPPED
        )
    }
}

// Static analysis. One shared rule set at config/detekt/detekt.yml; each module carries its own
// baseline so pre-existing findings stay quiet and only newly written code has to clear the bar.
detekt {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline-${project.name}.xml")
}

// detekt 1.23.x bundles the Kotlin 1.9 compiler, which cannot parse the running JDK's version
// string ("25.0.2") when it derives a default JVM target. Pin it to the toolchain we actually
// target; without this every detekt task dies with IllegalArgumentException: 25.0.2.
tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"

    reports {
        html.required = true
        xml.required = false
        txt.required = false
        sarif.required = false
        md.required = false
    }
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
    jvmTarget = "21"
}
