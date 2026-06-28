package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Uma requisição individual do lote (§2.1): o eixo do limite ([dimension] / [value]), quanto quer
 * consumir ([hits]) e a [priority]. Corresponde a um `RateLimitDescriptor` do proto, traduzido na
 * borda gRPC (R2 da ARQUITETURA.md).
 */
data class Request(val dimension: Dimension, val value: DimensionValue, val hits: Hits, val priority: Priority)
