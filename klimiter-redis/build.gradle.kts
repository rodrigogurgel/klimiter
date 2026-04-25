plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

val kotlinxCoroutinesVersion: String by rootProject.extra

dependencies {
    api(project(":klimiter-core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${kotlinxCoroutinesVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:${kotlinxCoroutinesVersion}")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    implementation("io.lettuce:lettuce-core:7.5.1.RELEASE")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${kotlinxCoroutinesVersion}")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
