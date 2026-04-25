import dev.detekt.gradle.extensions.DetektExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
}

allprojects {
    group = "io.klimiter"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

val detektRulesKtlint = libs.detekt.rules.ktlint

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
        add("detektPlugins", detektRulesKtlint)
    }
}
