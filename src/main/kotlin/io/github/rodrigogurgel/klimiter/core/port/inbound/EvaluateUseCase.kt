package io.github.rodrigogurgel.klimiter.core.port.inbound

import io.github.rodrigogurgel.klimiter.core.domain.Batch
import io.github.rodrigogurgel.klimiter.core.domain.BatchResult

/**
 * Porta de entrada (§7): avaliar um lote all-or-nothing. Implementada em `core.application` e
 * dirigida pela borda gRPC (R2/R3 da ARQUITETURA.md). `suspend` porque a avaliação pode tocar o
 * contador central (§4) sem bloquear thread.
 */
interface EvaluateUseCase {
    suspend fun evaluate(batch: Batch): BatchResult
}
