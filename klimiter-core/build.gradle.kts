plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

val kotlinxCoroutinesVersion: String by rootProject.extra

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:${kotlinxCoroutinesVersion}")
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")

    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${kotlinxCoroutinesVersion}")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("runDemo") {
    group = "application"
    description = "Runs io.klimiter.core.demo.MainKt"
    mainClass.set("io.klimiter.core.demo.MainKt")
    classpath = sourceSets["main"].runtimeClasspath
}