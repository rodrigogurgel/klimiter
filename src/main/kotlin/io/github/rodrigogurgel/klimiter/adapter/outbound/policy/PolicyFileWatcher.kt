package io.github.rodrigogurgel.klimiter.adapter.outbound.policy

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hot reload de políticas (§8): observa o DIRETÓRIO do arquivo de políticas e dispara
 * [FilePolicyRepository.reload] em criação/modificação. Observa o diretório (não o arquivo)
 * porque editores salvam via rename/replace e o arquivo pode nem existir no boot.
 *
 * - **Deleção é ignorada** de propósito (§8): um save transitório não derruba a config.
 * - **Debounce** ([PolicyProperties.reloadDebounce]): uma rajada de eventos vira um único reload.
 * - A resiliência (parse/validação falha → mantém a última config boa) vem do próprio
 *   [FilePolicyRepository.reload]; o observador segue vivo.
 *
 * Gerenciado como [SmartLifecycle]: inicia após os beans (o snapshot já foi carregado no boot)
 * e encerra o serviço de watch de forma limpa no shutdown.
 */
@Component
class PolicyFileWatcher(properties: PolicyProperties, private val repository: FilePolicyRepository) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)

    private val debounceMillis = properties.reloadDebounce.toMillis()
    private val file: Path = Path.of(properties.path).toAbsolutePath()
    private val directory: Path = file.parent
    private val fileName: Path = file.fileName

    private var watchService: WatchService? = null
    private var thread: Thread? = null

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val service = try {
            directory.fileSystem.newWatchService().also {
                directory.register(it, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
            }
        } catch (e: IOException) {
            running.set(false)
            logger.warn("não foi possível observar {} para hot reload — seguindo sem watcher", directory, e)
            return
        }
        watchService = service
        thread = Thread({ watchLoop(service) }, "policy-file-watcher").apply {
            isDaemon = true
            start()
        }
        logger.info("hot reload de políticas ativo em {} (debounce {}ms)", directory, debounceMillis)
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        watchService?.close() // desbloqueia take()/poll() lançando ClosedWatchServiceException
        thread?.join(STOP_TIMEOUT_MILLIS)
        watchService = null
        thread = null
    }

    override fun isRunning(): Boolean = running.get()

    private fun watchLoop(service: WatchService) {
        try {
            while (running.get()) {
                val key = service.take()
                if (consumeRelevant(key)) {
                    coalesceBurstAndReload(service)
                }
            }
        } catch (e: ClosedWatchServiceException) {
            logger.debug("watch service encerrado — finalizando o observador de políticas", e)
        } catch (e: InterruptedException) {
            logger.debug("observador de políticas interrompido", e)
            Thread.currentThread().interrupt()
        }
    }

    /** Drena os eventos da chave; `true` se algum for criação/modificação do arquivo vigiado. */
    private fun consumeRelevant(key: WatchKey): Boolean {
        var relevant = false
        for (event in key.pollEvents()) {
            val kind = event.kind()
            when {
                // overflow: pode ter perdido eventos → reavalia o arquivo por segurança.
                kind == OVERFLOW -> relevant = true

                // §8: deleção ignorada de propósito.
                kind == ENTRY_DELETE -> Unit

                // criação/modificação do arquivo vigiado.
                (event.context() as? Path)?.fileName == fileName -> relevant = true
            }
        }
        key.reset()
        return relevant
    }

    /**
     * Debounce: espera um período de silêncio drenando novos eventos e então recarrega uma única
     * vez, coalescendo a rajada de rename/replace num só [FilePolicyRepository.reload].
     */
    private fun coalesceBurstAndReload(service: WatchService) {
        var next = service.poll(debounceMillis, TimeUnit.MILLISECONDS)
        while (next != null) {
            next.pollEvents()
            next.reset()
            next = service.poll(debounceMillis, TimeUnit.MILLISECONDS)
        }
        repository.reload()
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
