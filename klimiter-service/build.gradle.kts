import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.kotlin.jvm)
    kotlin("kapt")
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.protobuf)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    implementation(project(":klimiter-core"))
    implementation(project(":klimiter-redis"))

    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.opentelemetry)
    implementation(libs.reactor.kotlin.extensions)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.spring.grpc.server.starter)
    implementation(libs.grpc.kotlin.stub)
    implementation(libs.protobuf.kotlin)
    implementation(libs.jackson.module.kotlin)

    developmentOnly(libs.spring.boot.devtools)
    developmentOnly(libs.spring.boot.docker.compose)

    kapt(libs.spring.boot.configuration.processor)

    testImplementation(libs.spring.boot.actuator.test)
    testImplementation(libs.spring.boot.opentelemetry.test)
    testImplementation(libs.spring.boot.webflux.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.spring.grpc.test)

    testRuntimeOnly(libs.junit.platform.launcher)
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
        mavenBom("org.springframework.grpc:spring-grpc-dependencies:${libs.versions.spring.grpc.get()}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java"
        }

        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpc.kotlin.get()}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpc") {
                    option("@generated=omit")
                }
                id("grpckt") {
                    option("@generated=omit")
                }
            }

            it.builtins {
                id("kotlin")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
