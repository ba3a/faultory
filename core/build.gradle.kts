plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
}

dependencies {
    implementation(libs.gdxCore)
    implementation(libs.jbox2dLibrary)
    implementation(libs.kotlinxSerializationJson)
    testImplementation(kotlin("test"))
}
