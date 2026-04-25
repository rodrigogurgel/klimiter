package io.klimiter.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.container.KoScope

internal object ArchitectureTestSupport {

    private const val CORE_MAIN = "klimiter-core/src/main/kotlin"
    private const val REDIS_MAIN = "klimiter-redis/src/main/kotlin"
    private const val SERVICE_MAIN = "klimiter-service/src/main/kotlin"

    fun coreScope(): KoScope = Konsist.scopeFromDirectory(CORE_MAIN)

    fun redisScope(): KoScope = Konsist.scopeFromDirectory(REDIS_MAIN)

    fun serviceScope(): KoScope = Konsist.scopeFromDirectory(SERVICE_MAIN)

    fun productionScope(): KoScope = Konsist.scopeFromDirectories(
        listOf(
            CORE_MAIN,
            REDIS_MAIN,
            SERVICE_MAIN,
        ),
    )
}
