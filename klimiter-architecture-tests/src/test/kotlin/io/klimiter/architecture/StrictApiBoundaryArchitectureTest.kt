package io.klimiter.architecture

import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.jupiter.api.Test

class StrictApiBoundaryArchitectureTest {

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
