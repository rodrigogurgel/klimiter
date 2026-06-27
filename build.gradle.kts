import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask
import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
    alias(libs.plugins.protobuf)


	// Qualidade & documentação
	jacoco
    alias(libs.plugins.detekt)
    alias(libs.plugins.dokka)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.versions)
}

group = "io.github.rodrigogurgel"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-grpc-server")
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")

    implementation(libs.grpc.kotlin.stub)
    // Versões gerenciadas pelo BOM do Spring Boot / plugin Kotlin (sem versão própria).
    implementation("com.google.protobuf:protobuf-kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    detektPlugins(libs.detekt.rules.ktlint.wrapper)

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-grpc-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation(libs.mockk)
    testImplementation(libs.konsist)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

// ---------------------------------------------------------------------------
// Protobuf / gRPC (geração de código)
// ---------------------------------------------------------------------------
// O Spring Boot auto-configura `protoc` + builtin `java` + plugin `grpc` ao detectar o plugin
// protobuf (versões alinhadas ao BOM). Aqui só ADICIONAMOS o que falta: o builtin `kotlin`
// (DSL das mensagens) e o `grpckt` (stub coroutine gRPC Kotlin, versão do catálogo grpcKotlin).
protobuf {
    plugins {
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:${libs.versions.grpcKotlin.get()}:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                id("grpckt")
            }
            it.builtins {
                id("kotlin")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Testes + cobertura (JaCoCo)
// ---------------------------------------------------------------------------
tasks.withType<Test> {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

jacoco {
	toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		// XML é consumido pelo Sonar; HTML para leitura local.
		xml.required.set(true)
		html.required.set(true)
		csv.required.set(false)
	}
	classDirectories.setFrom(
		files(classDirectories.files.map {
			fileTree(it) {
				// Código gerado (protobuf/gRPC) e o bootstrap não contam para cobertura.
				exclude("**/generated/**", "**/KlimiterApplication*")
			}
		})
	)
}

tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.jacocoTestReport)
	violationRules {
		rule {
			limit {
				minimum = "0.00".toBigDecimal() // suba a meta conforme o projeto amadurece
			}
		}
	}
}

// ---------------------------------------------------------------------------
// Detekt (lint estático Kotlin)
// ---------------------------------------------------------------------------
detekt {
    autoCorrect = true
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
}


// ---------------------------------------------------------------------------
// SonarQube / SonarCloud
// ---------------------------------------------------------------------------
sonar {
	properties {
		property("sonar.projectKey", "rodrigogurgel_klimiter")
		property("sonar.projectName", "klimiter")
		property("sonar.organization", "rodrigogurgel")
		property("sonar.host.url", System.getenv("SONAR_HOST_URL") ?: "https://sonarcloud.io")
		property("sonar.sources", "src/main/kotlin")
		property("sonar.tests", "src/test/kotlin")
		property(
			"sonar.coverage.jacoco.xmlReportPaths",
			layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml").get().asFile.path,
		)
		property(
			"sonar.kotlin.detekt.reportPaths",
			layout.buildDirectory.file("reports/detekt/detekt.xml").get().asFile.path,
		)
		property("sonar.exclusions", "**/build/generated/**,**/KlimiterApplication.kt")
	}
}

// ---------------------------------------------------------------------------
// Dokka (documentação de API)
// ---------------------------------------------------------------------------
dokka {
    dokkaSourceSets.configureEach {
        reportUndocumented.set(false)
        // O código gerado pelo protobuf/grpc não tem KDoc nossa para validar.
        suppressGeneratedFiles.set(true)
    }
    dokkaPublications.configureEach {
        failOnWarning.set(true)
    }
}

// ---------------------------------------------------------------------------
// Versões de dependências (com.github.ben-manes.versions)
// ---------------------------------------------------------------------------
// `./gradlew dependencyUpdates` reporta libs/plugins desatualizados.
// Step de pré-commit ao mexer em dependências — ver CONTRIBUTING.md / AGENTS.md.
fun isNonStable(version: String): Boolean {
    val stableKeyword = listOf("RELEASE", "FINAL", "GA").any { version.uppercase().contains(it) }
    val stableRegex = "^[0-9,.v-]+(-r)?$".toRegex()
    return !stableKeyword && !stableRegex.matches(version)
}

tasks.withType<DependencyUpdatesTask>().configureEach {
    gradleReleaseChannel = "current"
    // Só sugere candidatos pré-lançamento (alpha/beta/rc) quando a versão atual já é
    // pré-lançamento (ex.: detekt 2.0.0-alpha.3); senão, só releases estáveis.
    rejectVersionIf {
        isNonStable(candidate.version) && !isNonStable(currentVersion)
    }
}
