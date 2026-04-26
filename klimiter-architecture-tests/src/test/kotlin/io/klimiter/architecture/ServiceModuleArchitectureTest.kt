package io.klimiter.architecture

import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

class ServiceModuleArchitectureTest {

    @Test
    fun `service domain should not depend on spring`() {
        ArchitectureTestSupport
            .serviceScope()
            .files
            .filter { file ->
                file.packagee?.name?.startsWith("io.klimiter.service.domain") == true
            }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("org.springframework")
                }
            }
    }

    @Test
    fun `service domain should not depend on grpc generated code`() {
        ArchitectureTestSupport
            .serviceScope()
            .files
            .filter { file ->
                file.packagee?.name?.startsWith("io.klimiter.service.domain") == true
            }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.klimiter.service.proto")
                }
            }
    }

    @Test
    fun `service application should depend only on domain ports and models`() {
        ArchitectureTestSupport
            .serviceScope()
            .files
            .filter { file ->
                file.packagee?.name?.startsWith("io.klimiter.service.application") == true
            }
            .assertFalse { file ->
                file.imports.any { import ->
                    import.name.startsWith("io.klimiter.service.adapter") ||
                        import.name.startsWith("io.klimiter.service.config") ||
                        import.name.startsWith("io.klimiter.service.proto")
                }
            }
    }

    @Test
    fun `grpc adapters should stay inside adapter input grpc package`() {
        ArchitectureTestSupport
            .serviceScope()
            .classes()
            .filter { clazz ->
                clazz.name.endsWith("GrpcAdapter")
            }
            .assertTrue { clazz ->
                clazz.packagee?.name == "io.klimiter.service.adapter.input.grpc"
            }
    }

    @Test
    fun `configuration classes should stay inside config package`() {
        ArchitectureTestSupport
            .serviceScope()
            .classes()
            .filter { clazz ->
                clazz.name.endsWith("Configuration") || clazz.name.endsWith("Properties")
            }
            .assertTrue { clazz ->
                clazz.packagee?.name == "io.klimiter.service.config"
            }
    }

    @Test
    fun `input ports should stay inside domain port input package`() {
        ArchitectureTestSupport
            .serviceScope()
            .interfaces()
            .filter { it.name.endsWith("UseCase") }
            .assertTrue { iface ->
                iface.packagee?.name == "io.klimiter.service.domain.port.input"
            }
    }

    @Test
    fun `output ports should stay inside domain port output package`() {
        ArchitectureTestSupport
            .serviceScope()
            .interfaces()
            .filter { it.name.endsWith("Port") }
            .assertTrue { iface ->
                iface.packagee?.name == "io.klimiter.service.domain.port.output"
            }
    }
}
