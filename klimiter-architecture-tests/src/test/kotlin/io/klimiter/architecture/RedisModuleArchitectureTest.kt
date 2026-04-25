package io.klimiter.architecture

import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

class RedisModuleArchitectureTest {

    @Test
    fun `redis module may depend on core`() {
        ArchitectureTestSupport
            .redisScope()
            .files
            .assertTrue { file ->
                file.packagee?.name?.startsWith("io.klimiter.redis") == true
            }
    }

    @Test
    fun `redis module should not depend on service module`() {
        ArchitectureTestSupport
            .redisScope()
            .files
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.klimiter.klimiterservice")
                }
            }
    }

    @Test
    fun `redis module should not depend on spring`() {
        ArchitectureTestSupport
            .redisScope()
            .files
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("org.springframework")
                }
            }
    }

    @Test
    fun `redis api should not expose lettuce types`() {
        ArchitectureTestSupport
            .redisScope()
            .files
            .filter { file ->
                file.packagee?.name?.startsWith("io.klimiter.redis.api") == true
            }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.lettuce")
                }
            }
    }

    @Test
    fun `redis internal classes should be internal or private`() {
        ArchitectureTestSupport
            .redisScope()
            .classes()
            .filter { clazz ->
                clazz.packagee?.name?.startsWith("io.klimiter.redis.internal") == true
            }
            .assertTrue { clazz ->
                clazz.hasInternalModifier || clazz.hasPrivateModifier
            }
    }

    @Test
    fun `redis command executors should stay inside internal command package`() {
        ArchitectureTestSupport
            .redisScope()
            .classes()
            .filter { clazz ->
                clazz.name.endsWith("RedisCommandExecutor")
            }
            .assertTrue { clazz ->
                clazz.packagee?.name == "io.klimiter.redis.internal.command"
            }
    }

    @Test
    fun `redis lua scripts should stay inside internal script package`() {
        ArchitectureTestSupport
            .redisScope()
            .objects()
            .filter { obj ->
                obj.name.endsWith("Scripts")
            }
            .assertTrue { obj ->
                obj.packagee?.name == "io.klimiter.redis.internal.script"
            }
    }
}
