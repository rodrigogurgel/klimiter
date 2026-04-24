package io.klimiter.core.internal.infra.key

import io.klimiter.core.api.rls.RateLimitDescriptorEntry
import io.klimiter.core.internal.infra.time.SystemTimeProvider
import io.klimiter.core.internal.port.KeyGenerator
import io.klimiter.core.internal.port.TimeProvider

internal object CompositeKeyGenerator : KeyGenerator {
    private const val PREFIX = "klimiter"
    private const val SEPARATOR = "_"

    override fun generate(
        domain: String,
        entries: List<RateLimitDescriptorEntry>,
        windowDivider: Long,
        timeProvider: TimeProvider
    ): String {
        require(domain.isNotBlank()) { "domain não pode ser vazio" }
        require(entries.isNotEmpty()) { "entries não pode ser vazio" }

        val entriesSegment = entries.joinToString(SEPARATOR) { "${it.key}$SEPARATOR${it.value}" }
        val window = windowStart(windowDivider, timeProvider)

        return "$PREFIX$SEPARATOR$domain$SEPARATOR$entriesSegment$SEPARATOR$window"
    }

    private fun windowStart(divider: Long, timeProvider: TimeProvider): Long =
        (timeProvider.now().epochSecond / divider) * divider
}
