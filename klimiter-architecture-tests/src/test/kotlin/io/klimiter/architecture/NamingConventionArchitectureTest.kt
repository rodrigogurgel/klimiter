package io.klimiter.architecture

import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

class NamingConventionArchitectureTest {

    @Test
    fun `interfaces should not start with I prefix`() {
        ArchitectureTestSupport
            .productionScope()
            .interfaces()
            .assertFalse { iface ->
                iface.name.startsWith("I") &&
                    iface.name.length > 1 &&
                    iface.name[1].isUpperCase()
            }
    }

    @Test
    fun `test support classes should not exist in production source sets`() {
        ArchitectureTestSupport
            .productionScope()
            .classes()
            .assertFalse { clazz ->
                clazz.name.endsWith("Test") ||
                    clazz.name.endsWith("Fake") ||
                    clazz.name.endsWith("Stub") ||
                    clazz.name.endsWith("Mock")
            }
    }

    @Test
    fun `factories should end with Factory`() {
        ArchitectureTestSupport
            .productionScope()
            .classes()
            .filter { clazz ->
                clazz.name.contains("Factory")
            }
            .assertTrue { clazz ->
                clazz.name.endsWith("Factory")
            }
    }

    @Test
    fun `configuration classes should end with Configuration or Properties`() {
        ArchitectureTestSupport
            .serviceScope()
            .classes()
            .filter { clazz ->
                clazz.packagee?.name == "io.klimiter.klimiterservice.config"
            }
            .assertTrue { clazz ->
                clazz.name.endsWith("Configuration") ||
                    clazz.name.endsWith("Properties") ||
                    clazz.name.endsWith("Application")
            }
    }
}
