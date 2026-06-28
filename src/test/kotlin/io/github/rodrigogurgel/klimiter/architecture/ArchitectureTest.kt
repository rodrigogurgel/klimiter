package io.github.rodrigogurgel.klimiter.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture
import com.lemonappdev.konsist.api.architecture.Layer
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

/**
 * Fronteira executável (R7 da ARQUITETURA.md): falha o build quando a Arquitetura Hexagonal é
 * violada. Cada teste mapeia uma regra normativa (R1–R6).
 */
class ArchitectureTest {
    @Test
    fun `adapter depends on core and core depends on nothing (R4)`() {
        Konsist.scopeFromProduction().assertArchitecture {
            val core = Layer("Core", "$BASE.core..")
            val adapter = Layer("Adapter", "$BASE.adapter..")

            adapter.dependsOn(core)
            core.dependsOnNothing()
        }
    }

    @Test
    fun `core does not import frameworks, IO, generated proto or adapters (R1)`() {
        Konsist.scopeFromProduction()
            .files
            .filter { it.inPackage("$BASE.core") }
            .assertFalse { file ->
                file.imports.any { import -> FORBIDDEN_IN_CORE.any(import.name::startsWith) }
            }
    }

    @Test
    fun `generated proto is referenced only by the inbound grpc adapter (R2, R6)`() {
        Konsist.scopeFromProduction()
            .files
            .filter { file -> file.imports.any { it.name.startsWith("$BASE.grpc") } }
            .filterNot { it.inPackage("$BASE.grpc") }
            .assertTrue { it.inPackage("$BASE.adapter.inbound.grpc") }
    }

    @Test
    fun `core port holds only interfaces (R3)`() {
        Konsist.scopeFromProduction()
            .files
            .filter { it.inPackage("$BASE.core.port") }
            .assertTrue { file -> file.classes().isEmpty() }
    }

    private companion object {
        const val BASE = "io.github.rodrigogurgel.klimiter"

        val FORBIDDEN_IN_CORE = listOf(
            "$BASE.adapter", // R4: sentido único — core nunca conhece adapter
            "$BASE.grpc", // R6: proto gerado é referenciado só na borda
            "org.springframework",
            "io.grpc",
            "io.lettuce",
            "redis.",
            "com.fasterxml.jackson",
            "tools.jackson",
            "jakarta.",
            "java.io",
            "java.nio.file",
        )

        private fun KoFileDeclaration.inPackage(prefix: String): Boolean = packagee?.name?.startsWith(prefix) == true
    }
}
