package io.github.rodrigogurgel.klimiter.core.domain

/** Pedido do cliente: um lote de uma ou mais requisições, avaliado all-or-nothing (§7). */
data class Batch(val requests: List<Request>)

/**
 * Resultado do lote (§7): o [overall] status coletivo mais a [decisions] de cada item, na mesma
 * ordem das requisições de entrada.
 */
data class BatchResult(val overall: Status, val decisions: List<Decision>)
