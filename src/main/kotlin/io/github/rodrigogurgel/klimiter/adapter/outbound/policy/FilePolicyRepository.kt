package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import io.github.rodrigogurgel.klimiter.core.policy.PolicySnapshot
import io.github.rodrigogurgel.klimiter.core.port.outbound.PolicyRepository
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/**
 * [PolicyRepository] respaldado pelo arquivo apontado por [PolicyProperties.path]. Mantém o
 * snapshot atrás de um [AtomicReference] (troca atômica, §8.1) e o carrega no boot.
 *
 * Resiliência (§8): arquivo ausente no boot → começa vazio (tudo pass-through); falha de
 * parse/validação → mantém a última config boa e loga. [reload] reaplica essa política e é o
 * ponto de entrada para o hot reload (observador de diretório — passo seguinte).
 */
@Component
class FilePolicyRepository(private val properties: PolicyProperties, private val loader: YamlPolicyLoader) :
    PolicyRepository {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val snapshot = AtomicReference(PolicySnapshot.EMPTY)

    override fun current(): PolicySnapshot = snapshot.get()

    @PostConstruct
    fun loadOnStartup() {
        reload()
    }

    /** Recarrega o arquivo e troca o snapshot atomicamente. Ausente/inválido → mantém o atual. */
    fun reload() {
        val path = Path.of(properties.path)
        if (Files.notExists(path)) {
            logger.atWarn()
                .addKeyValue("path", path)
                .setMessage("arquivo de políticas ausente; mantendo o snapshot atual (pass-through)")
                .log()
            return
        }
        try {
            snapshot.set(loader.load(path))
            logger.atInfo()
                .addKeyValue("path", path)
                .addKeyValue("dimensions", snapshot.get().size)
                .setMessage("políticas carregadas")
                .log()
        } catch (e: PolicyFileException) {
            logger.atError()
                .setCause(e)
                .addKeyValue("path", path)
                .setMessage("falha ao carregar políticas; mantendo a última config boa")
                .log()
        }
    }
}
