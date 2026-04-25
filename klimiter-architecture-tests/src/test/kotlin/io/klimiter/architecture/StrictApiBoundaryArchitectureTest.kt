package io.klimiter.architecture

import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class StrictApiBoundaryArchitectureTest {

//    @Disabled("Enable after removing imports from api packages to internal implementation packages.")
    @Test
    fun `core api should not import core internal implementation`() {
        ArchitectureTestSupport
            .coreScope()
            .files
            .filter { file ->
                file.packagee?.name?.startsWith("io.klimiter.core.api") == true
            }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.klimiter.core.internal")
                }
            }
    }

//    @Disabled("Enable after Redis public API no longer imports redis internal implementation details.")
    @Test
    fun `redis api should not import redis internal implementation`() {
        ArchitectureTestSupport
            .redisScope()
            .files
            .filter { file ->
                file.packagee?.name?.startsWith("io.klimiter.redis.api") == true
            }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.klimiter.redis.internal")
                }
            }
    }
}
