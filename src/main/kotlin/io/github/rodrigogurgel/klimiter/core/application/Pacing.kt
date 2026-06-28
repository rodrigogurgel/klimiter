package io.github.rodrigogurgel.klimiter.core.application

import io.github.rodrigogurgel.klimiter.core.domain.Bucket
import io.github.rodrigogurgel.klimiter.core.domain.ReleaseLine
import io.github.rodrigogurgel.klimiter.core.port.outbound.GlobalCounter

/**
 * Caminho BAIXA / portão de pacing (§6). A BAIXA consome o crédito local já contado e arrenda só o
 * faltante (`faltante = max(0, hits − local)`, §6.1); a admissão é sempre confirmada pelo contador
 * contra a linha verdadeira — o atalho local só **nega** (pré-portão, §6.3), nunca admite sozinho.
 *
 * A operação central é a **fundida** (§6.4): valida a linha E arrenda o faltante num passo atômico,
 * fechando o TOCTOU; com `faltante = 0` degenera numa validação somente-leitura da linha.
 */
object Pacing {
    suspend fun reserveLow(bucket: Bucket, counter: GlobalCounter, hits: Long, nowMillis: Long): Reservation {
        // §6.1: serve do crédito local já contado e arrenda só o faltante.
        val localUsed = bucket.tryConsumeUpTo(hits)
        val missing = hits - localUsed

        // Pré-portão (§6.3): negação local sem round-trip (sound — o snapshot é lower-bound do consumo).
        val line = ReleaseLine.line(bucket.capacity, bucket.window.elapsed(nowMillis), bucket.window.duration)
        val estLeased = bucket.capacity - bucket.freeGlobal
        if (estLeased + missing > line) {
            bucket.refundLocal(localUsed) // nada admitido → devolve o local drenado
            return Reservation.Final(bucket.denied(nowMillis))
        }

        // §6.4: valida a linha e arrenda o faltante num passo atômico (read-only se missing == 0).
        val result = counter.paceLease(
            bucket.storageKey,
            bucket.capacity,
            missing,
            bucket.window.elapsed(nowMillis),
            bucket.window.duration,
            bucket.window.ttl(nowMillis),
        )
        bucket.publishFreeGlobal(result.freeGlobal)
        return if (result.admitted) {
            Reservation.Low(bucket, localUsed, bucket.allowed(nowMillis))
        } else {
            bucket.refundLocal(localUsed)
            Reservation.Final(bucket.denied(nowMillis))
        }
    }
}
