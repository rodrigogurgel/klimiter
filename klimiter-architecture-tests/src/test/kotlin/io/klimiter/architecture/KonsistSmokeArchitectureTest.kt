package io.klimiter.architecture

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class KonsistSmokeArchitectureTest {

    @Test
    fun `konsist should read core production scope`() {
        val files = ArchitectureTestSupport.coreScope().files

        assertTrue(files.isNotEmpty())
    }

    @Test
    fun `konsist should read redis production scope`() {
        val files = ArchitectureTestSupport.redisScope().files

        assertTrue(files.isNotEmpty())
    }

    @Test
    fun `konsist should read service production scope`() {
        val files = ArchitectureTestSupport.serviceScope().files

        assertTrue(files.isNotEmpty())
    }
}
