package io.github.rodrigogurgel.klimiter.core.domain

/**
 * Prioridade **agregada** de um lote respondido (§2.1) — a régua da tag `priority` da métrica por
 * request: um lote homogêneo herda a prioridade dos itens; um lote que mistura ALTA e BAIXA é
 * [MIXED] (visível, em vez de atribuído a uma das duas). O lote vazio classifica como [HIGH] — o
 * default do caminho primário, o mesmo fallback de `PRIORITY_UNSPECIFIED` na borda gRPC.
 */
enum class BatchPriority {
    HIGH,
    LOW,
    MIXED,
    ;

    companion object {
        fun of(requests: List<Request>): BatchPriority {
            var sawHigh = false
            var sawLow = false
            for (request in requests) {
                if (request.priority == Priority.HIGH) sawHigh = true else sawLow = true
            }
            return when {
                sawHigh && sawLow -> MIXED
                sawLow -> LOW
                else -> HIGH
            }
        }
    }
}
