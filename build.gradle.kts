import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.DetektExtension
import kotlinx.kover.gradle.plugin.dsl.AggregationType
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover)
}

dependencies {
    kover(project(":klimiter-core"))
    kover(project(":klimiter-redis"))
    kover(project(":klimiter-service"))
}

allprojects {
    group = "io.klimiter"
    version = "0.0.1-SNAPSHOT"
}

val detektRulesKtlint = libs.detekt.rules.ktlint

subprojects {
    apply {
        plugin("dev.detekt")
        plugin("org.jetbrains.kotlinx.kover")
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

    tasks.withType<Detekt>().configureEach {
        exclude("**/build/**")
        exclude("**/generated/**")
        exclude("**/proto/**")
        exclude("**/*Proto.kt")
        exclude("**/*ProtoKt.kt")
        exclude("**/*Grpc.kt")
        exclude("**/*GrpcKt.kt")
    }
}

kover {
    reports {
        filters {
            excludes {
                classes(
                    // Generated code
                    "*.generated.*",
                    "*.generated.**",
                    "*.proto.*",
                    "*.proto.**",
                    "*Proto",
                    "*ProtoKt",
                    "*GrpcKt",
                    "*OuterClass",

                    // Spring Boot entrypoints/configs
                    "*.Application",
                    "*Application",
                    "*.config.*",
                    "*.configuration.*",

                    // DTOs simples, se você não quiser cobrar cobertura deles
                    "*.dto.*",
                    "*.model.*Request",
                    "*.model.*Response",
                )

                annotatedBy(
                    "org.springframework.boot.context.properties.ConfigurationProperties",
                    "org.springframework.context.annotation.Configuration",
                )
            }
        }

        total {
            html {
                onCheck = false
                htmlDir = layout.buildDirectory.dir("reports/kover/html")
            }

            xml {
                onCheck = true
                xmlFile = layout.buildDirectory.file("reports/kover/report.xml")
            }

            verify {
                rule("Minimum aggregated line coverage") {
                    bound {
                        minValue = 80
                        coverageUnits = CoverageUnit.LINE
                        aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    }
                }

                rule("Minimum aggregated branch coverage") {
                    bound {
                        minValue = 70
                        coverageUnits = CoverageUnit.BRANCH
                        aggregationForGroup = AggregationType.COVERED_PERCENTAGE
                    }
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named("koverVerify"))
}
