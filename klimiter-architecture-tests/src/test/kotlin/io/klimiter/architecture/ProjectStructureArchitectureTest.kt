package io.klimiter.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class ProjectStructureArchitectureTest {

    @Test
    fun `core module should contain api spi and internal packages`() {
        val packages = ArchitectureTestSupport
            .coreScope()
            .files
            .mapNotNull { it.packagee?.name }
            .toSet()

        assertTrue(packages.any { it.startsWith("io.klimiter.core.api") })
        assertTrue(packages.any { it.startsWith("io.klimiter.core.api.spi") })
        assertTrue(packages.any { it.startsWith("io.klimiter.core.internal") })
    }

    @Test
    fun `redis module should contain api and internal packages`() {
        val packages = ArchitectureTestSupport
            .redisScope()
            .files
            .mapNotNull { it.packagee?.name }
            .toSet()

        assertTrue(packages.any { it.startsWith("io.klimiter.redis.api") })
        assertTrue(packages.any { it.startsWith("io.klimiter.redis.internal") })
    }

    @Test
    fun `service module should follow adapter application domain config structure`() {
        val packages = ArchitectureTestSupport
            .serviceScope()
            .files
            .mapNotNull { it.packagee?.name }
            .toSet()

        assertTrue(packages.any { it.startsWith("io.klimiter.service.adapter") })
        assertTrue(packages.any { it.startsWith("io.klimiter.service.application") })
        assertTrue(packages.any { it.startsWith("io.klimiter.service.config") })
        assertTrue(packages.any { it.startsWith("io.klimiter.service.domain") })
    }
}
