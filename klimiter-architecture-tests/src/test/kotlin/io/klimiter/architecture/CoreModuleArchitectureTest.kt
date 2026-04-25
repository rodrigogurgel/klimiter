package io.klimiter.architecture

import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

class CoreModuleArchitectureTest {

    @Test
    fun `core classes should not depend on redis module`() {
        ArchitectureTestSupport
            .coreScope()
            .files
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.klimiter.redis")
                }
            }
    }

    @Test
    fun `core classes should not depend on service module`() {
        ArchitectureTestSupport
            .coreScope()
            .files
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.klimiter.klimiterservice")
                }
            }
    }

    @Test
    fun `core classes should not depend on spring`() {
        ArchitectureTestSupport
            .coreScope()
            .files
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("org.springframework")
                }
            }
    }

    @Test
    fun `core classes should not depend on lettuce`() {
        ArchitectureTestSupport
            .coreScope()
            .files
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.lettuce")
                }
            }
    }

    @Test
    fun `core api should not depend on redis or service packages`() {
        ArchitectureTestSupport
            .coreScope()
            .files
            .filter { file ->
                file.packagee?.name?.startsWith("io.klimiter.core.api") == true
            }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.klimiter.redis") ||
                        import.name.startsWith("io.klimiter.klimiterservice")
                }
            }
    }

    @Test
    fun `core spi should contain only public extension contracts and simple implementations`() {
        ArchitectureTestSupport
            .coreScope()
            .files
            .filter { file ->
                file.packagee?.name?.startsWith("io.klimiter.core.api.spi") == true
            }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.klimiter.core.internal")
                }
            }
    }

    @Test
    fun `core internal declarations should be internal or private when they are implementation classes`() {
        ArchitectureTestSupport
            .coreScope()
            .classes()
            .filter { clazz ->
                clazz.packagee?.name?.startsWith("io.klimiter.core.internal") == true
            }
            .assertTrue { clazz ->
                clazz.hasInternalModifier || clazz.hasPrivateModifier
            }
    }
}
