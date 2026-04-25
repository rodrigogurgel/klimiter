import dev.detekt.gradle.extensions.DetektExtension

plugins {
    kotlin("jvm") version "2.3.0" apply false
    kotlin("plugin.spring") version "2.3.0" apply false
    id("org.springframework.boot") version "4.0.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("dev.detekt") version "2.0.0-alpha.2" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.8" apply false
}

allprojects {
    group = "io.klimiter"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply {
        plugin("dev.detekt")
    }

    extensions.configure(DetektExtension::class.java) {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom("$rootDir/config/detekt/detekt.yml")
        autoCorrect = true
    }

    dependencies {
        add("detektPlugins", "dev.detekt:detekt-rules-ktlint-wrapper:2.0.0-alpha.2")
    }
}

extra["kotlinxCoroutinesVersion"] = "1.10.2"