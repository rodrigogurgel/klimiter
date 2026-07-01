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
 * chamada (evita tocar o MXBean por request).
 *
 * **Não usa `getProcessCpuLoad()`**: medido, ele divide o tempo de CPU pelo total de CPUs do host,
 * ignorando `-XX:ActiveProcessorCount` e a afinidade (`taskset`) — num host de 16 cores com o
 * processo pinado em 2, dois cores 100% ocupados leem ~0,13 (= 2/16), e o limiar nunca dispara. Em
 * vez disso, deriva a fração de [cores] a partir do delta de [OperatingSystemMXBean.getProcessCpuTime]
 * (nanos de CPU consumidos) sobre o intervalo real: `Δcpu / (Δt × cores)`. [cores] vem de
 * `availableProcessors()`, que **respeita** `ActiveProcessorCount` e a cota de cgroup (K8s) — portável.
 *
 * Retorna a última leitura como `() -> Double` em `[0.0, 1.0]`, ou negativa até haver duas amostras /
 * quando a JVM não reporta o tempo de CPU (o interceptor trata negativo como *fail open*). Ciclo de
 * vida pelo container, como o [EvictionRunner].
 */
class CpuLoadSampler(
    private val intervalMillis: Long,
    private val cores: Int = Runtime.getRuntime().availableProcessors(),
    private val processCpuNanos: () -> Long = defaultProcessCpuNanos(),
    private val nanoTime: () -> Long = System::nanoTime,
) : () -> Double {
    private val scope = CoroutineScope(Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var lastLoad: Double = -1.0
    private var lastCpuNanos: Long = -1L
    private var lastAtNanos: Long = 0L

    override fun invoke(): Double = lastLoad

    /**
     * Recalcula a carga a partir do delta de tempo de CPU desde a amostra anterior. A primeira amostra
     * só fixa a linha de base (mantém `-1` = *fail open*); da segunda em diante publica a fração.
     * Visível para teste (dispara uma amostragem determinística sem o loop de corrotina).
     */
    internal fun sample() {
        val cpuNanos = processCpuNanos()
        val now = nanoTime()
        if (cpuNanos >= 0 && lastCpuNanos >= 0) {
            val elapsed = now - lastAtNanos
            if (elapsed > 0) {
                lastLoad = ((cpuNanos - lastCpuNanos).toDouble() / elapsed / cores).coerceIn(0.0, 1.0)
            }
        }
        lastCpuNanos = cpuNanos
        lastAtNanos = now
    }

    @PostConstruct
    fun start() {
        sample()
        job = scope.launch {
            while (isActive) {
                delay(intervalMillis.milliseconds)
                sample()
            }
        }
    }

    @PreDestroy
    fun stop() {
        job?.cancel()
    }

    private companion object {
        /** Tempo de CPU acumulado do processo (nanos), do MXBean da plataforma. `-1` se indisponível. */
        fun defaultProcessCpuNanos(): () -> Long {
            val os = ManagementFactory.getPlatformMXBean(OperatingSystemMXBean::class.java)
            return os::getProcessCpuTime
        }
    }
}
