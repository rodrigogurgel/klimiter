package io.klimiter.core.api.spi

import io.klimiter.core.api.rls.RateLimitDescriptorEntry

/**
 * Default [KeyGenerator]. Produces keys of the form
 * `klimiter|<domain>|<k1>=<v1>|<k2>=<v2>|...|<windowStart>`.
 *
 * Uses `|` as a separator — chosen because it is legal in Redis keys yet uncommon in the
 * identifiers typically used as descriptor entries (user ids, ip addresses, route names).
 * Entries containing the separator would make the key ambiguous, so we reject them instead
 * of silently producing colliding buckets.
 */
object CompositeKeyGenerator : KeyGenerator {
    private const val PREFIX = "klimiter"
    private const val SEPARATOR = '|'
    private const val KV_DELIMITER = '='
    private const val ESTIMATED_WINDOW_SUFFIX_LENGTH = 22

    override fun generate(
        domain: String,
        entries: List<RateLimitDescriptorEntry>,
        windowDivider: Long,
        timeProvider: TimeProvider,
    ): String {
        require(domain.isNotBlank()) { "domain must not be blank" }
        require(entries.isNotEmpty()) { "entries must not be empty" }
        rejectSeparator(domain, "domain")
        entries.forEach {
            rejectSeparator(it.key, "entry key")
            rejectSeparator(it.value, "entry value")
        }

        val builder = StringBuilder(estimatedSize(domain, entries))
        builder.append(PREFIX).append(SEPARATOR).append(domain)
        for (entry in entries) {
            builder.append(SEPARATOR).append(entry.key).append(KV_DELIMITER).append(entry.value)
        }
        builder.append(SEPARATOR).append(windowStart(windowDivider, timeProvider))
        return builder.toString()
    }

    private fun rejectSeparator(value: String, label: String) {
        require(value.indexOf(SEPARATOR) == -1) {
            "$label must not contain the '$SEPARATOR' separator: '$value'"
        }
    }

    private fun estimatedSize(domain: String, entries: List<RateLimitDescriptorEntry>): Int {
        // PREFIX + sep + domain + (sep + key + '=' + value) per entry + sep + window timestamp
        var size = PREFIX.length + 1 + domain.length + ESTIMATED_WINDOW_SUFFIX_LENGTH
        for (e in entries) size += e.key.length + e.value.length + 2
        return size
    }

    private fun windowStart(divider: Long, timeProvider: TimeProvider): Long =
        (timeProvider.now().epochSecond / divider) * divider
}
