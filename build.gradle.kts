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
version = "0.1.2"

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
    // Concurrency limiting adaptativo (Gradient2/Vegas) para short-circuit sob saturação, inferida
    // pela latência (§ load shedding). O interceptor gRPC nativo vem do módulo -grpc; o -core traz
    // os algoritmos e a SPI de métricas (declarado runtime no pom do -grpc, mas usado em compile aqui).
    implementation(libs.concurrency.limits.core)
    implementation(libs.concurrency.limits.grpc)
    // Versões gerenciadas pelo BOM do Spring Boot / plugin Kotlin (sem versão própria).
    implementation("com.google.protobuf:protobuf-kotlin")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    // Redis: contador global por janela (§4). API coroutines do Lettuce (bridge do Reactor →
    // exige kotlinx-coroutines-reactive, transitivo de -reactor). Versão gerenciada pelo BOM.
    implementation("io.lettuce:lettuce-core")
    detektPlugins(libs.detekt.rules.ktlint.wrapper)

    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testImplementation("org.springframework.boot:spring-boot-starter-grpc-server-test")
    testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testImplementation(libs.mockk)
    testImplementation(libs.konsist)
    // Redis real nos testes de integração do adapter. O Spring Boot não gerencia o Testcontainers;
    // a versão vem do BOM do próprio Testcontainers (catálogo), e os módulos ficam sem versão.
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
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
// Empacotamento
// ---------------------------------------------------------------------------
// Só o bootJar executável vai para a imagem; o jar "plain" não é necessário e tornaria ambíguo o
// glob de extração de camadas no Dockerfile.
tasks.jar {
	enabled = false
}

// ---------------------------------------------------------------------------
// Testes + cobertura (JaCoCo)
// ---------------------------------------------------------------------------
tasks.withType<Test> {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
	// Testcontainers (testes de integração do adapter Redis). Sem Docker, os ITs anotados com
	// @Testcontainers(disabledWithoutDocker = true) são pulados; os unitários seguem rodando.
	// Ryuk é dispensável em CI/dev local; o docker-java (shaded) negocia uma API antiga (1.32) por
	// default — daemons modernos exigem >= 1.40, então fixa-se um piso compatível, com override.
	environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true")
	systemProperty("api.version", System.getProperty("api.version") ?: System.getenv("DOCKER_API_VERSION") ?: "1.43")
	// Docker Desktop expõe o socket fora do padrão; repassa DOCKER_HOST do host quando setado.
	System.getenv("DOCKER_HOST")?.let { environment("DOCKER_HOST", it) }
}

jacoco {
	toolVersion = "0.8.12"
}

// Fora da métrica de cobertura: código gerado (protobuf/gRPC), o bootstrap, o composition root e a
// infra Redis (esta coberta pelos testes de integração com Docker, não pelos unitários). Aplicado
// igualmente ao relatório e à verificação para o piso refletir a lógica testável sem Docker.
val coverageExclusions = listOf(
	"**/grpc/v1/**",
	"**/KlimiterApplication*",
	"**/config/**",
	"**/adapter/outbound/redis/**",
)

fun org.gradle.testing.jacoco.tasks.JacocoReportBase.excludeNonCoverable() {
	classDirectories.setFrom(
		files(classDirectories.files.map { fileTree(it) { exclude(coverageExclusions) } }),
	)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		// XML é consumido pelo Sonar; HTML para leitura local.
		xml.required.set(true)
		html.required.set(true)
		csv.required.set(false)
	}
	excludeNonCoverable()
}

tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.jacocoTestReport)
	excludeNonCoverable()
	violationRules {
		rule {
			limit {
				// Piso da lógica testável. Suba conforme o projeto amadurece (ver AGENTS.md).
				minimum = "0.80".toBigDecimal()
			}
		}
	}
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
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
