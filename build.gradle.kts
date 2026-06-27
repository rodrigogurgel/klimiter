import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.DetektCreateBaselineTask

plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.protobuf") version "0.9.6"

	// Qualidade & documentação
	jacoco
	id("io.gitlab.arturbosch.detekt") version "1.23.7"
	id("org.sonarqube") version "6.0.1.5171"
	id("org.jetbrains.dokka") version "2.0.0"
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
	implementation("org.springframework.boot:spring-boot-starter-grpc-server")
	implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	testImplementation("org.springframework.boot:spring-boot-starter-grpc-server-test")
	testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
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
	buildUponDefaultConfig = true
	allRules = false
	autoCorrect = false
	config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
	baseline = file("$rootDir/config/detekt/baseline.xml")
}

tasks.withType<Detekt>().configureEach {
	jvmTarget = "21"
	reports {
		xml.required.set(true) // consumido pelo Sonar
		html.required.set(true)
		sarif.required.set(true)
		md.required.set(false)
	}
}

tasks.withType<DetektCreateBaselineTask>().configureEach {
	jvmTarget = "21"
}

// O detekt 1.23.x embute o compilador do Kotlin 2.0.x. Como o projeto usa Kotlin 2.3.21,
// fixamos o Kotlin do classpath do detekt na versão compatível para evitar o erro
// "detekt was compiled with Kotlin 2.0.x but is currently running with 2.3.21".
configurations.matching { it.name.startsWith("detekt") }.configureEach {
	resolutionStrategy.eachDependency {
		if (requested.group == "org.jetbrains.kotlin") {
			useVersion("2.0.10")
		}
	}
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
// O plugin Dokka v2 registra a task `dokkaGenerate` (HTML em build/dokka/).
// moduleName usa o nome do projeto ("klimiter") por padrão.
//
// O BOM do Spring Boot 4 (via io.spring.dependency-management) força o Jackson do
// classpath do worker do Dokka para uma versão incompatível com o analisador do Dokka
// (NoSuchMethodError em TypeFactory). Fixamos o Jackson só nas configs do Dokka.
configurations.matching { it.name.startsWith("dokka") }.configureEach {
	resolutionStrategy.eachDependency {
		if (requested.group == "com.fasterxml.jackson.core") {
			useVersion("2.15.3")
		}
	}
}
