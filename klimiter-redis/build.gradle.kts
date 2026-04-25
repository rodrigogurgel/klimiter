plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":klimiter-core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.caffeine)
    implementation(libs.lettuce.core)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
