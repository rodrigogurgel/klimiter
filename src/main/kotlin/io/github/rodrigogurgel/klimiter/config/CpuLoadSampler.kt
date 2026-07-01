package io.github.rodrigogurgel.klimiter.config

import com.sun.management.OperatingSystemMXBean
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.lang.management.ManagementFactory
import kotlin.time.Duration.Companion.milliseconds

/**
 * Amostra a carga de CPU do processo fora do hot path e a publica num campo volátil, lido em O(1)
 * pelo [io.github.rodrigogurgel.klimiter.adapter.inbound.grpc.CpuShedServerInterceptor] a cada
 * chamada (evita tocar o MXBean por request). `getProcessCpuLoad` é ruidoso e atrasado (~1s), então
 * amostrar periodicamente e cachear é suficiente — e mais barato que ler sob demanda.
 *
 * Retorna a última leitura como `() -> Double` em `[0.0, 1.0]`, ou negativa enquanto/quando a JVM
 * não reporta (o interceptor trata negativo como *fail open*). Ciclo de vida pelo container, como o
 * [EvictionRunner].
 */
class CpuLoadSampler(
    private val intervalMillis: Long,
    private val osBean: OperatingSystemMXBean =
        ManagementFactory.getPlatformMXBean(OperatingSystemMXBean::class.java),
) : () -> Double {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var lastLoad: Double = -1.0

    override fun invoke(): Double = lastLoad

    @PostConstruct
    fun start() {
        lastLoad = osBean.processCpuLoad
        job = scope.launch {
            while (isActive) {
                delay(intervalMillis.milliseconds)
                lastLoad = osBean.processCpuLoad
            }
        }
    }

    @PreDestroy
    fun stop() {
        job?.cancel()
    }
}
