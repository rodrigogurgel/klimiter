import dev.detekt.gradle.extensions.DetektExtension

plugins {
    kotlin("jvm") version "2.2.21" apply false
    kotlin("plugin.spring") version "2.2.21" apply false
    id("org.springframework.boot") version "4.0.5" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("dev.detekt") version "2.0.0-alpha.2" apply false
}

allprojects {
    group = "io.klimiter"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "dev.detekt")

    extensions.configure(DetektExtension::class.java) {
        buildUponDefaultConfig = true
        allRules = false
        config.setFrom("$rootDir/config/detekt/detekt.yml")
    }
}