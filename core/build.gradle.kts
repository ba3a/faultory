plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(libs.gdxCore)
    implementation(libs.kotlinxSerializationJson)
    testImplementation(kotlin("test"))
}
