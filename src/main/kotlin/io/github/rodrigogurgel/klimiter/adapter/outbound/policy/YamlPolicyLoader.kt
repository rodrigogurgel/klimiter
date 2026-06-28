package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import io.github.rodrigogurgel.klimiter.core.policy.PolicySnapshot
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.kotlinModule
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Falha ao ler, parsear ou validar o arquivo de políticas. */
class PolicyFileException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Lê e valida o `policies.yaml`, produzindo um [PolicySnapshot]. A validação é por parse tipado
 * estrito (`FAIL_ON_UNKNOWN_PROPERTIES` pega campos desconhecidos) somado às invariantes dos
 * Value Objects na conversão (ver [toSnapshot]) — equivalente ao JSON Schema, sem dependência
 * de um motor de schema em runtime.
 */
@Component
class YamlPolicyLoader {
    private val mapper = YAMLMapper.builder()
        .addModule(kotlinModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true)
        .build()

    /**
     * Carrega o snapshot a partir de [path].
     *
     * @throws PolicyFileException se o arquivo não puder ser lido, o YAML for malformado ou o
     *   conteúdo violar o contrato (campo ausente, unit inválida, prefetch ambíguo, etc.).
     */
    fun load(path: Path): PolicySnapshot {
        val content = try {
            Files.readString(path)
        } catch (e: IOException) {
            fail("não foi possível ler o arquivo de políticas em $path", e)
        }

        val document = try {
            mapper.readValue(content, PolicyDocument::class.java)
        } catch (e: JacksonException) {
            fail("YAML de políticas inválido em $path: ${e.message}", e)
        }

        return try {
            document.toSnapshot()
        } catch (e: IllegalArgumentException) {
            fail("política inválida em $path: ${e.message}", e)
        }
    }

    private fun fail(message: String, cause: Throwable): Nothing = throw PolicyFileException(message, cause)
}
